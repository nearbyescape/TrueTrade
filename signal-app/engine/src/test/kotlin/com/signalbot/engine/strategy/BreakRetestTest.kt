package com.signalbot.engine.strategy

import com.signalbot.engine.backtest.BreakRetestBacktest
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BreakRetestTest {

    /** Candles whose closes follow straight lines between key points; opens sit halfway to the last close. */
    private fun path(vararg pts: Pair<Int, Double>, mirror: Boolean = false): Series {
        val closes = ArrayList<Double>()
        for (k in 0 until pts.size - 1) {
            val (i0, p0) = pts[k]; val (i1, p1) = pts[k + 1]
            for (i in i0 until i1) closes += p0 + (p1 - p0) * (i - i0).toDouble() / (i1 - i0)
        }
        closes += pts.last().second
        val c = if (mirror) closes.map { 200.0 - it } else closes
        return Series(c.indices.map { i ->
            val close = c[i]
            val open = if (i == 0) close else (c[i - 1] + close) / 2
            val t = Synthetic.START + i * Timeframe.H1.millis
            Candle(t, open, maxOf(open, close) + 0.3, minOf(open, close) - 0.3, close, 100.0, t + Timeframe.H1.millis - 1)
        })
    }

    // Three falling highs (10, 30, 50) make a resistance line; price breaks it near candle 59, runs to 104,
    // comes back to touch the line at candle 68 and closes above it, then rallies.
    private val keys = arrayOf(0 to 100.0, 10 to 110.0, 20 to 95.0, 30 to 106.0, 40 to 95.5, 50 to 102.0,
        55 to 96.0, 62 to 104.0, 68 to 99.0, 90 to 115.0)

    @Test
    fun `break, retest and close give a long signal at the retest candle`() {
        val r = BreakRetestEngine().replay(path(*keys))
        val brk = r.events.first { it.action == BrAction.BREAK }
        assertEquals(Direction.LONG, brk.direction)
        assertTrue(brk.index in 57..61, "break at ${brk.index}")
        assertEquals(3, brk.line!!.touches)
        val entry = r.events.single { it.action == BrAction.ENTRY }
        assertEquals(68, entry.index)
        assertEquals(Direction.LONG, entry.direction)
        assertTrue(r.events.none { it.action == BrAction.CANCELLED })
        val trade = r.trades.single()
        assertEquals(69, trade.entryIndex)
        assertTrue(trade.initialStop < 98.7 && trade.initialStop > 97.0, "stop ${trade.initialStop}")
        assertEquals(null, trade.exitIndex) // still running at the end
        // Trailing: the stop only ever moves up.
        assertTrue(trade.stops.zipWithNext().all { (a, b) -> b.second > a.second })
    }

    @Test
    fun `the mirror image gives the same short`() {
        val r = BreakRetestEngine().replay(path(*keys, mirror = true))
        val entry = r.events.single { it.action == BrAction.ENTRY }
        assertEquals(68, entry.index)
        assertEquals(Direction.SHORT, entry.direction)
        val trade = r.trades.single()
        assertTrue(trade.initialStop > 200 - 98.7, "stop ${trade.initialStop}")
    }

    @Test
    fun `no retest within the window means no trade`() {
        // After the break price never comes back to the line.
        val r = BreakRetestEngine().replay(path(0 to 100.0, 10 to 110.0, 20 to 95.0, 30 to 106.0, 40 to 95.5,
            50 to 102.0, 55 to 96.0, 62 to 104.0, 140 to 140.0))
        assertTrue(r.events.any { it.action == BrAction.BREAK })
        assertTrue(r.trades.isEmpty())
    }

    @Test
    fun `a close back through the broken line cancels the setup`() {
        val r = BreakRetestEngine().replay(path(0 to 100.0, 10 to 110.0, 20 to 95.0, 30 to 106.0, 40 to 95.5,
            50 to 102.0, 55 to 96.0, 62 to 104.0, 64 to 92.0, 80 to 90.0))
        assertTrue(r.events.any { it.action == BrAction.CANCELLED })
        assertTrue(r.trades.none { it.direction == Direction.LONG })
    }

    @Test
    fun `stop is hit when price falls back`() {
        val r = BreakRetestEngine().replay(path(*keys.copyOfRange(0, 9), 75 to 104.0, 85 to 90.0))
        val t = r.trades.first()
        assertEquals(BrAction.EXIT_STOP, t.exitReason)
        val priced = BreakRetestBacktest.price("X", r)
        assertTrue(priced.first().netR < 0)
    }

    @Test
    fun `decisions never use later candles`() {
        val all = Synthetic.hourly(4000, 0.00005, seed = 7)
        val full = BreakRetestEngine().replay(Series(all))
        assertTrue(full.events.count { it.action == BrAction.ENTRY } >= 3, "need some signals to compare")
        for (cut in listOf(900, 1777, 2500, 3333)) {
            val part = BreakRetestEngine().replay(Series(all.subList(0, cut + 1)))
            val a = full.events.filter { it.index <= cut }
            assertEquals(a, part.events, "events up to candle $cut must match")
        }
    }

    @Test
    fun `target mode exits at the nearest resistance`() {
        // The swing high at 62 matches the one at 30 (106.3): a two-touch resistance level.
        val cfg = BreakRetestConfig(exit = ExitMode.TARGET)
        val r = BreakRetestEngine(cfg).replay(path(0 to 100.0, 10 to 110.0, 20 to 95.0, 30 to 106.0, 40 to 95.5,
            50 to 102.0, 55 to 96.0, 62 to 106.0, 68 to 99.0, 90 to 115.0))
        val t = r.trades.single()
        val target = assertNotNull(t.target)
        assertTrue(target > 105.5 && target < 106.3, "target $target")
        assertEquals(BrAction.EXIT_TARGET, t.exitReason)
    }

    @Test
    fun `her stop follows the opposing line and is tighter than the flat swing stop`() {
        val flat = BreakRetestEngine().replay(path(*keys)).trades.single()
        val line = BreakRetestEngine(BreakRetestConfig(stop = StopMode.LINE)).replay(path(*keys)).trades.single()
        val sl = assertNotNull(line.stopLine)
        assertEquals(TrendLine.Kind.SUPPORT, sl.kind)
        assertTrue(sl.p2 > sl.p1, "opposing line must rise")
        assertTrue(line.initialStop > flat.initialStop, "line stop ${line.initialStop} vs flat ${flat.initialStop}")
        assertTrue(line.stops.zipWithNext().all { (a, b) -> b.second >= a.second }, "stop never moves back")
    }

    @Test
    fun `top-down lines never use higher candles that have not closed`() {
        val all = Synthetic.hourly(3000, 0.00004, seed = 11)
        fun run(n: Int): List<BrEvent> {
            val c = all.subList(0, n)
            val h4 = Series(Resample.to(c, Timeframe.H4))
            val d1 = Series(Resample.to(c, Timeframe.D1))
            val cfg = BreakRetestConfig(stop = StopMode.LINE)
            return BreakRetestEngine(cfg).replay(Series(c), higher = listOf(
                d1 to BreakRetestPresets.forTimeframe(Timeframe.D1), h4 to BreakRetestPresets.forTimeframe(Timeframe.H4))).events
        }
        val full = run(all.size)
        assertTrue(full.any { it.action == BrAction.BREAK && it.line?.sourceMs != 0L }, "some breaks should come from higher-timeframe lines")
        for (cut in listOf(1200, 2201, 2777)) {
            assertEquals(full.filter { it.index < cut }, run(cut).filter { it.index < cut }, "events before candle $cut must match")
        }
    }
}
