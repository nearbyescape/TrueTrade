package com.signalbot.engine.strategy

import com.signalbot.engine.backtest.EnsembleBacktest
import com.signalbot.engine.history.FundingEvent
import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Timeframe
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnsembleEngineTest {
    private val hour = Timeframe.H1.millis
    private val day = Timeframe.D1.millis

    /** 24 hourly candles per day at one price, so each daily candle opens and closes at that price. */
    private fun days(prices: List<Double>) = Synthetic.marketData(Assets.BTC, prices.flatMapIndexed { d, p ->
        (0 until 24).map { h ->
            val t = Synthetic.START + (d * 24L + h) * hour
            Candle(t, p, p + 0.1, p - 0.1, p, 10.0, t + hour - 1)
        }
    })

    // Hand-worked example with one 3-day leg and a 3-day EMA (alpha 0.5):
    //  day:    0   1   2   3   4   5   6   7   8     9   10    11
    //  close: 10  10  10  10  10  11  12  13  12.4  11  10.5  10.5
    //  EMA3:  -   -   10  10  10  10.5 11.25 12.125 12.2625 11.63 ...
    //  day 5: close 11 > prior 3-day high 10, above EMA -> LONG, stop = (11 + 10)/2 = 10.5
    //  day 6: range 10..12 -> stop 11;  day 7: range 11..13 -> stop 12
    //  day 8: range 12..13 -> stop 12.5; close 12.4 < 12.5 -> EXIT
    //  day 9: close 11 < prior 3-day low 12, below EMA 11.63 -> SHORT, stop = (13 + 11)/2 = 12
    //  day 10: range 10.5..12.4 -> stop 11.45; day 11: range 10.5..11 -> stop 10.75 (still open)
    private val prices = listOf(10.0, 10.0, 10.0, 10.0, 10.0, 11.0, 12.0, 13.0, 12.4, 11.0, 10.5, 10.5)
    private val small = EnsembleEngine(EnsembleConfig(legs = listOf(3), regimeEma = 3))

    @Test fun handWorkedLeg() {
        val r = small.replay(days(prices))
        assertEquals(
            listOf(Triple(LegAction.ENTER, Direction.LONG, 5), Triple(LegAction.EXIT, Direction.LONG, 8), Triple(LegAction.ENTER, Direction.SHORT, 9)),
            r.events.map { Triple(it.action, it.direction, it.dayIndex) },
        )
        assertEquals(10.5, r.events[0].stop, 1e-12)
        assertEquals(12.5, r.events[1].stop, 1e-12)
        assertEquals(12.0, r.events[2].stop, 1e-12)
        assertEquals(Synthetic.START + 6 * day, r.events[0].timeMs, "decided at day 5's close")
        val open = r.open.single()
        assertEquals(Direction.SHORT, open.direction)
        assertEquals(10.75, open.stop, 1e-12)
        assertEquals(12.0, open.initialStop, 1e-12)
        assertTrue(r.events[0].reasons.first().contains("3-day closing high"))
    }

    @Test fun backtestFillsAtNextOpenWithCosts() {
        val trades = EnsembleBacktest(small).run(days(prices), emptyList())
        assertEquals(2, trades.size)
        val long = trades.first { it.direction == Direction.LONG }
        val side = 0.0008
        val fill = 12.0 * (1 + side)          // day 6 open
        val r = fill - 10.5
        val exit = 11.0 * (1 - side)          // day 9 open
        assertEquals(Synthetic.START + 6 * day, long.entryTime)
        assertEquals(Synthetic.START + 9 * day, long.exitTime)
        assertEquals((exit - fill) / r, long.grossR, 1e-12)
        assertEquals(side * fill / r + side * exit / r, long.feesR, 1e-12)
        val short = trades.first { it.direction == Direction.SHORT }
        assertTrue(short.openAtEnd, "the short is still open: marked at the last close")
        assertEquals(10.5 * (1 + side), short.exitPrice, 1e-12)
    }

    @Test fun fundingLongsPayShortsReceive() {
        val f = listOf(FundingEvent(1_000, 0.0001), FundingEvent(2_000, 0.0002), FundingEvent(3_000, 0.0003))
        // Events at 2,000 and 3,000 fall inside [1,500, 3,500).
        assertEquals((0.0002 + 0.0003) * 100 / 2, EnsembleBacktest.fundingR(f, 1_500, 3_500, 1.0, 100.0, 2.0), 1e-15)
        assertEquals(-(0.0002 + 0.0003) * 100 / 2, EnsembleBacktest.fundingR(f, 1_500, 3_500, -1.0, 100.0, 2.0), 1e-15)
        // Past the end of the archive, the last rate repeats every 8 hours.
        val eightH = 8 * 3_600_000L
        assertEquals(2 * 0.0003, EnsembleBacktest.fundingR(f, 3_500, 3_000 + 2 * eightH + 1, 1.0, 1.0, 1.0), 1e-15)
    }

    @Test fun shortsCanBeTurnedOff() {
        val longOnly = EnsembleEngine(EnsembleConfig(legs = listOf(3), regimeEma = 3, allowShorts = false))
        assertTrue(longOnly.replay(days(prices)).events.none { it.direction == Direction.SHORT })
    }

    @Test fun trendFilterBlocksLongsBelowTheEma() {
        // A long fall, then a bounce to a 3-day high that is still under the EMA.
        val p = (0 until 40).map { 100.0 - it } + listOf(62.0, 63.0, 64.0, 66.0)
        val r = EnsembleEngine(EnsembleConfig(legs = listOf(3), regimeEma = 30, allowShorts = false)).replay(days(p))
        assertTrue(r.events.isEmpty())
    }

    @Test fun tinyStopsAreSkipped() {
        val r = EnsembleEngine(EnsembleConfig(legs = listOf(3), regimeEma = 3, minRiskFraction = 0.2)).replay(days(prices))
        assertTrue(r.events.none { it.action == LegAction.ENTER }, "every stop here is closer than 20% of price")
    }

    @Test fun legsAlternateEnterExitAndAreIndependent() {
        val data = Synthetic.marketData(Assets.BTC, Synthetic.hourly(24 * 600, 0.0001, 4))
        val r = EnsembleEngine().replay(data)
        assertTrue(r.events.size > 10)
        for ((leg, ev) in r.events.groupBy { it.leg }) {
            ev.forEachIndexed { k, e -> assertEquals(if (k % 2 == 0) LegAction.ENTER else LegAction.EXIT, e.action, "leg $leg event $k") }
            ev.zipWithNext().forEach { (a, b) -> if (a.action == LegAction.ENTER) assertEquals(a.direction, b.direction) }
        }
        assertEquals(setOf(20, 30, 60, 90), r.events.map { it.leg }.toSet())
    }

    @Test fun neverLooksIntoTheFuture() {
        val data = Synthetic.marketData(Assets.BTC, Synthetic.hourly(24 * 600, 0.00008, 9))
        val engine = EnsembleEngine()
        val full = engine.replay(data)
        for (k in listOf(250, 333, 420, 517, 598)) {
            val past = engine.replay(data, lastDay = k)
            assertEquals(full.events.filter { it.dayIndex <= k }, past.events, "day $k")
            // Same answer when the future candles are physically removed.
            val i = data.h1.size.coerceAtMost((k + 1) * 24) - 1
            val cut = engine.replay(Synthetic.truncatedAt(data, i))
            assertEquals(past.events, cut.events, "day $k truncated")
            assertEquals(past.open, cut.open)
        }
    }
}
