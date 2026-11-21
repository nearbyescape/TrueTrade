package com.signalbot.engine.backtest

import com.signalbot.engine.history.FundingEvent
import com.signalbot.engine.model.Asset
import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Series
import com.signalbot.engine.strategy.Direction
import com.signalbot.engine.strategy.Grade
import com.signalbot.engine.strategy.MarketData
import com.signalbot.engine.strategy.Prepared
import com.signalbot.engine.strategy.Score
import com.signalbot.engine.strategy.SetupType
import com.signalbot.engine.strategy.Signal
import com.signalbot.engine.strategy.StrategyConfig
import com.signalbot.engine.strategy.Synthetic
import com.signalbot.engine.strategy.TradePlan
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hand-built price paths with known answers. Long plan used throughout:
 * entry 100, stop 98 (R = 2), TP1 103 (1.5R), TP2 106 (3R), target 110, trail 4.
 */
class BacktesterTest {
    private val hour = 3_600_000L
    private val free = CostModel(0.0, 0.0, 0.0, funding = false)
    private val noLimits = BacktestSettings(costs = free, maxOpenTrades = 10, maxPerGroup = 10, dailyLossLimitR = 99.0, weeklyLossLimitR = 99.0)

    /** Bars as (open, high, low, close); a signal fires at the close of bar 0. */
    private fun bars(vararg b: List<Double>) = b.toList()
    private fun bar(o: Double, h: Double, l: Double, c: Double) = listOf(o, h, l, c)

    private fun signal(asset: Asset, direction: Direction, barIndex: Int, score: Int = 80): Signal {
        val long = direction == Direction.LONG
        fun p(v: Double) = if (long) v else 200 - v // shorts: mirror around 100
        val plan = TradePlan(direction, p(100.0), p(98.0), p(103.0), p(106.0), p(110.0), 5.0, false, 0.4, 0.3, 0.3, 4.0)
        val sc = Score(25, 20, 10, 10, 15, score - 80) // first five parts make 80; the last makes the total [score]
        return Signal(asset.id, direction, Synthetic.START + (barIndex + 1) * hour, barIndex, setOf(SetupType.TREND_PULLBACK),
            emptySet(), plan, sc, Grade.ALERT, listOf("test"), 30.0, 1.5, 1.0)
    }

    private fun tape(
        asset: Asset,
        path: List<List<Double>>,
        signals: Map<Int, Direction> = mapOf(0 to Direction.LONG),
        funding: List<FundingEvent> = emptyList(),
        scores: Map<Int, Int> = emptyMap(),
        startHour: Int = 0,
    ): AssetTape {
        val candles = path.mapIndexed { k, (o, h, l, c) ->
            val t = Synthetic.START + (startHour + k) * hour
            Candle(t, o, h, l, c, 100.0, t + hour - 1)
        }
        val data = MarketData(asset, Series(candles), Series(emptyList()), Series(emptyList()))
        val long = arrayOfNulls<Signal>(candles.size)
        val short = arrayOfNulls<Signal>(candles.size)
        for ((i, d) in signals) {
            val s = signal(asset, d, startHour + i, scores[i] ?: 80).copy(barIndex = i)
            if (d == Direction.LONG) long[i] = s else short[i] = s
        }
        return AssetTape(Prepared(data, StrategyConfig()), long, short, funding)
    }

    private fun one(path: List<List<Double>>, settings: BacktestSettings = noLimits, direction: Direction = Direction.LONG,
                    funding: List<FundingEvent> = emptyList()): Trade {
        val res = Backtester(settings).run(listOf(tape(Assets.BTC, path, mapOf(0 to direction), funding)))
        assertEquals(1, res.trades.size, "expected one trade, skipped=${res.skipped}")
        return res.trades.single()
    }

    private val signalBar = bar(99.0, 100.5, 98.5, 100.0)

    @Test fun fullLossAtStop() {
        val t = one(bars(signalBar, bar(100.0, 100.8, 97.5, 98.2)))
        assertEquals(-1.0, t.grossR, 1e-12)
        assertEquals(Outcome.LOSS, t.outcome)
        assertEquals(Synthetic.START + hour, t.entryTime)
    }

    @Test fun tp1ThenBreakeven() {
        val t = one(bars(signalBar, bar(100.0, 103.5, 100.5, 102.0), bar(101.0, 101.5, 99.9, 100.2)))
        assertEquals(0.4 * 1.5, t.grossR, 1e-12)
        assertEquals(Outcome.TP1_THEN_BREAKEVEN, t.outcome)
        assertEquals(listOf(ExitReason.TP1, ExitReason.BREAKEVEN_STOP), t.fills.map { it.reason })
    }

    @Test fun sameCandleTp1AndBreakevenAssumesTheWorse() {
        // TP1 and a dip below entry in one hour: assume TP1 then the dip, so the rest exits at breakeven.
        val t = one(bars(signalBar, bar(100.0, 103.5, 99.5, 101.0)))
        assertEquals(0.6, t.grossR, 1e-12)
    }

    @Test fun stopAndTargetInOneCandleCountsAsStop() {
        val t = one(bars(signalBar, bar(100.0, 104.0, 97.0, 103.0)))
        assertEquals(-1.0, t.grossR, 1e-12)
        assertEquals(Outcome.LOSS, t.outcome)
    }

    @Test fun tp2ThenProfitStop() {
        val t = one(bars(signalBar,
            bar(100.0, 103.2, 100.2, 103.0),   // TP1 -> stop 100
            bar(103.0, 106.5, 102.5, 105.0),   // TP2 -> stop 102, trail from 106.5 -> 102.5
            bar(104.0, 104.5, 102.0, 102.3)))  // runner stopped at 102.5
        assertEquals(0.4 * 1.5 + 0.3 * 3.0 + 0.3 * (2.5 / 2), t.grossR, 1e-12)
        assertEquals(Outcome.TP2, t.outcome)
        assertEquals(listOf(ExitReason.TP1, ExitReason.TP2, ExitReason.PROFIT_STOP), t.fills.map { it.reason })
    }

    @Test fun runnerTrailsToBigWin() {
        val t = one(bars(signalBar,
            bar(100.0, 103.2, 100.2, 103.0),
            bar(103.0, 106.5, 102.6, 106.0),
            bar(106.0, 110.0, 105.0, 109.5),
            bar(109.5, 115.0, 109.0, 114.0),   // best 115 -> trail 111
            bar(114.0, 114.5, 110.5, 111.0)))  // out at 111 = 5.5R on the runner
        assertEquals(0.6 + 0.9 + 0.3 * 5.5, t.grossR, 1e-12)
        assertEquals(Outcome.RUNNER, t.outcome)
    }

    @Test fun runnerCanExitAtTargetInstead() {
        val path = bars(signalBar, bar(100.0, 103.2, 100.2, 103.0), bar(103.0, 106.5, 102.6, 106.0), bar(106.0, 110.5, 105.0, 109.5))
        val t = one(path, noLimits.copy(runnerExitAtTarget = true))
        assertEquals(0.6 + 0.9 + 0.3 * 5.0, t.grossR, 1e-12)
        assertEquals(ExitReason.TARGET, t.fills.last().reason)
    }

    @Test fun gapThroughStopFillsAtTheOpen() {
        val t = one(bars(signalBar, bar(100.0, 100.5, 99.0, 99.5), bar(96.0, 96.5, 95.0, 96.2)))
        assertEquals(-2.0, t.grossR, 1e-12)
    }

    @Test fun timeStopAfter48Hours() {
        val flat = List(60) { bar(100.4, 101.0, 99.5, 100.6) }
        val t = one(listOf(signalBar, bar(100.0, 101.0, 99.5, 100.6)) + flat)
        assertEquals(Outcome.TIME_EXIT, t.outcome)
        assertEquals(0.3, t.grossR, 1e-12)
        assertEquals(48.0, t.holdHours, 1e-6)
    }

    @Test fun skipsWhenPriceAlreadyRanAway() {
        val res = Backtester(noLimits).run(listOf(tape(Assets.BTC, bars(signalBar, bar(101.5, 102.0, 101.0, 101.8)))))
        assertTrue(res.trades.isEmpty())
        assertEquals(1, res.skipped[SkipReason.CHASED_TOO_FAR])
    }

    @Test fun shortsMirrorLongs() {
        fun mirror(b: List<Double>) = bar(200 - b[0], 200 - b[2], 200 - b[1], 200 - b[3])
        val longPath = bars(signalBar, bar(100.0, 103.2, 100.2, 103.0), bar(103.0, 106.5, 102.6, 106.0),
            bar(106.0, 110.0, 105.0, 109.5), bar(109.5, 115.0, 109.0, 114.0), bar(114.0, 114.5, 110.5, 111.0))
        val l = one(longPath)
        val s = one(longPath.map(::mirror), direction = Direction.SHORT)
        assertEquals(l.grossR, s.grossR, 1e-12)
        assertEquals(l.outcome, s.outcome)
        assertEquals(200 - l.fills.last().price, s.fills.last().price, 1e-9)
    }

    @Test fun feesAndSlippageInR() {
        val costs = CostModel(takerFee = 0.0005, makerFee = 0.0002, slippage = 0.0005, funding = false)
        val t = one(bars(signalBar, bar(100.0, 100.8, 97.5, 98.2)), noLimits.copy(costs = costs))
        val fill = 100.0 * 1.0005
        val r = fill - 98.0
        val exit = 98.0 * (1 - 0.0005)
        assertEquals((exit - fill) / r, t.grossR, 1e-12)
        assertEquals((0.0005 * fill + 0.0005 * exit) / r, t.feesR, 1e-12)
        assertTrue(t.netR < -1.05, "a stop-out costs a bit more than 1R once costs are in")
    }

    @Test fun takeProfitsPayMakerFee() {
        val costs = CostModel(takerFee = 0.0005, makerFee = 0.0002, slippage = 0.0, funding = false)
        val t = one(bars(signalBar, bar(100.0, 103.5, 100.5, 102.0), bar(101.0, 101.5, 99.9, 100.2)), noLimits.copy(costs = costs))
        val expected = (0.0005 * 100 + 0.4 * 0.0002 * 103 + 0.6 * 0.0005 * 100) / 2
        assertEquals(expected, t.feesR, 1e-12)
    }

    @Test fun fundingLongsPayShortsReceive() {
        val costs = CostModel(0.0, 0.0, 0.0, funding = true)
        val path = listOf(signalBar) + List(20) { bar(100.0, 100.5, 99.5, 100.0) } + listOf(bar(100.0, 100.2, 97.0, 97.5))
        // Funding at hours 4, 12 and 20 (every 8h), 0.01% each, while the full position is open.
        val funding = listOf(4, 12, 20).map { FundingEvent(Synthetic.START + it * hour + 1, 0.0001) }
        val l = one(path, noLimits.copy(costs = costs), funding = funding)
        assertEquals(3 * 0.0001 * 100.0 / 2, l.fundingR, 1e-12)
        fun mirror(b: List<Double>) = bar(200 - b[0], 200 - b[2], 200 - b[1], 200 - b[3])
        val s = one(path.map(::mirror), noLimits.copy(costs = costs), Direction.SHORT, funding)
        assertEquals(-3 * 0.0001 * 100.0 / 2, s.fundingR, 1e-12)
    }

    @Test fun fundingRepeatsLastKnownRateWhenArchiveEnds() {
        val costs = CostModel(0.0, 0.0, 0.0, funding = true)
        val path = listOf(signalBar) + List(20) { bar(100.0, 100.5, 99.5, 100.0) } + listOf(bar(100.0, 100.2, 97.0, 97.5))
        // Known events end at hour 4 (8h apart), so hours 12 and 20 are filled in with the last rate.
        val funding = listOf(FundingEvent(Synthetic.START - 4 * hour, 0.0001), FundingEvent(Synthetic.START + 4 * hour, 0.0002))
        val t = one(path, noLimits.copy(costs = costs), funding = funding)
        assertEquals(3 * 0.0002 * 100.0 / 2, t.fundingR, 1e-12)
    }

    @Test fun portfolioLimitsPerGroupAndTotal() {
        val path = bars(signalBar, bar(100.0, 100.8, 99.0, 100.5), bar(100.5, 100.8, 99.0, 100.5))
        val tapes = listOf(Assets.BTC, Assets.ETH, Assets.SOL, Assets.GOLD, Assets.SILVER).mapIndexed { k, a ->
            tape(a, path, scores = mapOf(0 to 90 - k))
        }
        val res = Backtester(noLimits.copy(maxOpenTrades = 3, maxPerGroup = 2)).run(tapes)
        // Crypto: BTC (90) and ETH (89) fit, SOL hits the group limit; GOLD fills the 3rd slot; SILVER hits max open.
        assertEquals(setOf("BTC", "ETH", "GOLD"), res.trades.map { it.assetId }.toSet())
        assertEquals(1, res.skipped[SkipReason.GROUP_LIMIT])
        assertEquals(1, res.skipped[SkipReason.MAX_OPEN_TRADES])
        assertTrue(res.trades.all { it.outcome == Outcome.OPEN_AT_END })
    }

    @Test fun dailyLossLimitPausesNewTrades() {
        // Three quick losers in a row on one asset, cooldown off: the third is blocked after -2R.
        val loser = listOf(signalBar, bar(100.0, 100.8, 97.5, 98.2))
        val path = loser + loser + loser + listOf(bar(98.0, 98.5, 97.5, 98.0))
        val t = tape(Assets.BTC, path, signals = mapOf(0 to Direction.LONG, 2 to Direction.LONG, 4 to Direction.LONG))
        val res = Backtester(noLimits.copy(dailyLossLimitR = 2.0, cooldownHours = 0)).run(listOf(t))
        assertEquals(2, res.trades.size)
        assertEquals(1, res.skipped[SkipReason.LOSS_LIMIT_PAUSE])
    }

    @Test fun cooldownSkipsRepeatSignals() {
        val loser = listOf(signalBar, bar(100.0, 100.8, 97.5, 98.2))
        val t = tape(Assets.BTC, loser + loser + listOf(bar(98.0, 98.5, 97.5, 98.0)), signals = mapOf(0 to Direction.LONG, 2 to Direction.LONG))
        val res = Backtester(noLimits.copy(cooldownHours = 12)).run(listOf(t))
        assertEquals(1, res.trades.size)
        assertEquals(1, res.skipped[SkipReason.COOLDOWN])
    }

    @Test fun minScoreFiltersWeakSignals() {
        val t = tape(Assets.BTC, bars(signalBar, bar(100.0, 100.8, 97.5, 98.2)), scores = mapOf(0 to 72))
        assertTrue(Backtester(noLimits.copy(minScore = 75)).run(listOf(t)).trades.isEmpty())
        assertEquals(1, Backtester(noLimits.copy(minScore = 70)).run(listOf(t)).trades.size)
    }

    @Test fun statsFromKnownTrades() {
        val win = one(bars(signalBar, bar(100.0, 103.2, 100.2, 103.0), bar(103.0, 106.5, 102.6, 106.0),
            bar(106.0, 110.0, 105.0, 109.5), bar(109.5, 115.0, 109.0, 114.0), bar(114.0, 114.5, 110.5, 111.0)))
        val loss = one(bars(signalBar, bar(100.0, 100.8, 97.5, 98.2)))
        val st = Stats.of(listOf(loss, win, loss), riskPerTrade = 0.01)
        assertEquals(3, st.trades)
        assertEquals(1.0 / 3, st.winRate, 1e-12)
        assertEquals((3.15 - 2) / 3, st.avgR, 1e-12)
        assertEquals(3.15 / 2, st.profitFactor, 1e-12)
        assertEquals(2, st.longestLosingStreak, "ordered by exit time, both losers close before the winner")
        assertEquals(2.0, st.maxDrawdownR, 1e-12)
        assertTrue(st.checklist().any { !it.second })
    }

    @Test fun trailModeRidesTheWholePositionAndOnlyRaisesTheStop() {
        // Trail 4 behind the highest high; no partial exits at 103/106.
        val trail = noLimits.copy(exitStyle = ExitStyle.TRAIL)
        val t = one(bars(signalBar,
            bar(100.0, 103.0, 99.0, 102.5),    // best 103 -> stop max(98, 99) = 99
            bar(102.5, 108.0, 101.0, 107.5),   // best 108 -> stop 104
            bar(107.5, 112.0, 106.0, 111.0),   // best 112 -> stop 108
            bar(111.0, 111.5, 107.0, 107.5)),  // low 107 <= 108: out at 108
            trail)
        assertEquals(listOf(ExitReason.PROFIT_STOP), t.fills.map { it.reason })
        assertEquals(1.0, t.fills.single().fraction, 1e-12)
        assertEquals((108.0 - 100.0) / 2, t.grossR, 1e-12)
        assertEquals(Outcome.RUNNER, t.outcome)
    }

    @Test fun trailModeLossAndNoTimeStop() {
        val trail = noLimits.copy(exitStyle = ExitStyle.TRAIL)
        val loss = one(bars(signalBar, bar(100.0, 100.8, 97.5, 98.2)), trail)
        assertEquals(-1.0, loss.grossR, 1e-12)
        assertEquals(Outcome.LOSS, loss.outcome)
        val flat = listOf(signalBar) + List(80) { bar(100.4, 101.0, 99.5, 100.6) }
        val held = one(flat, trail)
        assertEquals(Outcome.OPEN_AT_END, held.outcome, "no 48h time stop in trend mode")
    }

    @Test fun weekStartIsMondayUtc() {
        val monday = Synthetic.START // 2025-01-06 was a Monday
        assertEquals(monday, Backtester.weekStart(monday + 3 * 86_400_000L + 5))
        assertEquals(monday, Backtester.weekStart(monday))
        assertEquals(monday - 7 * 86_400_000L, Backtester.weekStart(monday - 1))
    }
}
