package com.signalbot.engine.strategy

import com.signalbot.engine.backtest.AssetTape
import com.signalbot.engine.backtest.BacktestSettings
import com.signalbot.engine.backtest.Backtester
import com.signalbot.engine.backtest.ExitStyle
import com.signalbot.engine.indicators.Indicators
import com.signalbot.engine.model.Asset
import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Timeframe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrendEngineTest {
    private val hour = Timeframe.H1.millis
    private val engine = TrendEngine()

    /** Hourly candles that sit at one price per day (high/low 1 either side), from [Synthetic.START]. */
    private fun daily(prices: List<Double>, start: Long = Synthetic.START): List<Candle> = prices.flatMapIndexed { d, p ->
        (0 until 24).map { h ->
            val t = start + (d * 24L + h) * hour
            Candle(t, p, p + 1, p - 1, p, 10.0, t + hour - 1)
        }
    }

    /** 260 days rising, 40 days flat just under the top, then one breakout day and a few after. */
    private fun breakoutPrices(): List<Double> {
        val rise = (0 until 260).map { 100.0 + it * 0.5 }            // 100 -> 229.5
        val flat = List(40) { 226.0 }                                // below the 230.5 high
        return rise + flat + listOf(236.0) + List(5) { 237.0 }
    }

    private fun run(asset: Asset, candles: List<Candle>, dir: Direction = Direction.LONG): List<Evaluation> {
        val data = Synthetic.marketData(asset, candles)
        val p = engine.prepare(data)
        return (0 until data.h1.size).map { engine.evaluate(p, it, dir) }
    }

    @Test fun oneSignalOnTheBreakoutDayClose() {
        val candles = daily(breakoutPrices())
        val signals = run(Assets.BTC, candles).filterIsInstance<Signal>()
        assertEquals(1, signals.size, "only the first close above the 55-day high")
        val s = signals.single()
        val breakoutDay = 300
        assertEquals(Synthetic.START + (breakoutDay + 1) * 86_400_000L, s.timeMs, "decided at that day's close, 00:00 UTC")
        assertEquals(breakoutDay * 24 + 23, s.barIndex, "on the last 1H candle of the day")
        val d1 = Synthetic.marketData(Assets.BTC, candles).d1
        val atr = Indicators.atr(d1.high, d1.low, d1.close, 14)[breakoutDay]
        with(s.plan) {
            assertEquals(236.0, entry)
            assertEquals(236.0 - 2 * atr, stop, 1e-9)
            assertEquals(5 * atr, trailDistance, 1e-9)
            assertEquals(0.0, tp1Fraction); assertEquals(1.0, runnerFraction)
        }
        assertTrue(s.reasons.first().contains("55-day high"))
    }

    @Test fun aSteadyTrendGivesNoLateEntries() {
        // Every day is a new high, so by the time the 200-day EMA exists the breakout is long "old".
        val steady = daily((0 until 320).map { 100.0 + it * 0.5 })
        assertTrue(run(Assets.BTC, steady).none { it is Signal })
    }

    @Test fun onlyDailyClosesAreJudged() {
        val all = run(Assets.BTC, daily(breakoutPrices()))
        for (e in all) {
            val dailyClose = e.timeMs % 86_400_000L == 0L
            if (!dailyClose) assertTrue(e is Rejected && e.detail.startsWith("Waiting for the daily close"))
        }
    }

    @Test fun shortsMirrorLongs() {
        val up = daily(breakoutPrices())
        val down = daily(breakoutPrices().map { 1000.0 - it })
        val l = run(Assets.BTC, up).filterIsInstance<Signal>().single()
        val s = run(Assets.BTC, down, Direction.SHORT).filterIsInstance<Signal>().single()
        assertEquals(l.barIndex, s.barIndex)
        assertEquals(1000.0 - l.plan.entry, s.plan.entry, 1e-9)
        assertEquals(1000.0 - l.plan.stop, s.plan.stop, 1e-9)
        assertEquals(l.plan.trailDistance, s.plan.trailDistance, 1e-9)
        assertTrue(s.reasons.first().contains("55-day low"))
    }

    @Test fun noLongsBelowThe200DayEma() {
        // Same breakout shape, but after a long fall the price is under its 200-day EMA.
        val fall = (0 until 260).map { 400.0 - it * 0.8 }           // 400 -> ~192
        val flat = List(40) { 190.0 }
        val prices = fall + flat + listOf(200.0) + List(5) { 201.0 }
        val e = run(Assets.BTC, daily(prices))
        assertTrue(e.none { it is Signal })
        assertTrue(e.any { it is Rejected && it.gate == Gate.TREND })
    }

    @Test fun goldSkipsBreakoutsOnFridaysClose() {
        // Shift the start so the breakout day (index 300) is a Friday: its close is Saturday 00:00 UTC.
        val start0 = Synthetic.START
        val shift = (0..6).first { k ->
            Instant.ofEpochMilli(start0 + (300 + k) * 86_400_000L).atZone(ZoneOffset.UTC).dayOfWeek == DayOfWeek.FRIDAY
        }
        val candles = daily(breakoutPrices(), start = start0 + shift * 86_400_000L)
        assertTrue(run(Assets.BTC, candles).any { it is Signal }, "crypto takes it")
        val gold = run(Assets.GOLD, candles.map { it.copy() })
        assertTrue(gold.none { it is Signal }, "gold skips it")
        assertTrue(gold.any { it is Rejected && it.gate == Gate.MARKET_CLOSED })
    }

    @Test fun neverLooksIntoTheFuture() {
        val data = Synthetic.marketData(Assets.BTC, Synthetic.hourly(24 * 700, 0.00008, 3))
        val full = engine.prepare(data)
        val closes = (0 until data.h1.size).filter { (data.h1.openTime[it] + hour) % 86_400_000L == 0L }
        var signals = 0
        for (i in closes.filter { it > 24 * 260 }) {
            val past = engine.prepare(Synthetic.truncatedAt(data, i))
            for (d in engine.directions()) {
                val a = engine.evaluate(full, i, d)
                assertEquals(a, engine.evaluate(past, i, d), "bar $i $d")
                if (a is Signal) signals++
            }
        }
        assertTrue(signals > 0)
    }

    @Test fun backtestsWithTrailExits() {
        val data = Synthetic.marketData(Assets.BTC, Synthetic.hourly(24 * 700, 0.00012, 5))
        val tape = AssetTape.buildTrend(engine, data, emptyList())
        val signalBars = (tape.long.indices).filter { tape.long[it] != null || tape.short[it] != null }
        assertTrue(signalBars.isNotEmpty())
        assertTrue(signalBars.all { (data.h1.openTime[it] + hour) % 86_400_000L == 0L })
        val res = Backtester(BacktestSettings(exitStyle = ExitStyle.TRAIL, minScore = 0, timeStopBars = 0)).run(listOf(tape))
        assertTrue(res.trades.isNotEmpty())
        assertTrue(res.trades.all { t -> t.fills.size == 1 && t.fills.single().fraction == 1.0 }, "whole position, one exit")
    }
}
