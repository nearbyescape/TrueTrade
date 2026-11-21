package com.signalbot.engine.strategy

import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whole-engine tests on 420 days of synthetic hourly data. They check the properties that must
 * ALWAYS hold, whatever the market does: no look-ahead, every hard gate respected in every signal,
 * shorts exactly mirroring longs, and filters (BTC, weekends, news) actually blocking.
 */
class SignalEngineTest {
    private val days = 420
    private val up = (1..3).map { seed -> Synthetic.marketData(Assets.BTC, Synthetic.hourly(24 * days, 0.00015, seed)) }
    private val down = Synthetic.marketData(Assets.BTC, Synthetic.hourly(24 * days, -0.00015, 7, startPrice = 90_000.0))

    private fun runAll(engine: SignalEngine, data: MarketData): List<Evaluation> {
        val p = engine.prepare(data)
        return (0 until data.h1.size).flatMap { i -> engine.directions().map { engine.evaluate(p, i, it) } }
    }

    @Test fun findsSignalsInUptrendsAndNoShortsThere() {
        val engine = SignalEngine(StrategyConfig(allowShorts = true))
        for (data in up) {
            val signals = runAll(engine, data).filterIsInstance<Signal>()
            assertTrue(signals.count { it.direction == Direction.LONG && it.grade != Grade.LOG_ONLY } >= 20, "expected plenty of long alerts")
            assertTrue(signals.none { it.direction == Direction.SHORT }, "no shorts in a strong uptrend")
        }
    }

    @Test fun findsShortsInDowntrendsOnlyWhenAllowed() {
        val withShorts = runAll(SignalEngine(StrategyConfig(allowShorts = true)), down).filterIsInstance<Signal>()
        assertTrue(withShorts.count { it.direction == Direction.SHORT } >= 20)
        assertTrue(withShorts.none { it.direction == Direction.LONG }, "no longs in a strong downtrend")
        val longOnly = runAll(SignalEngine(StrategyConfig(allowShorts = false)), down).filterIsInstance<Signal>()
        assertTrue(longOnly.isEmpty())
    }

    @Test fun everySignalRespectsTheHardGatesAndPlan() {
        val cfg = StrategyConfig(allowShorts = true)
        val engine = SignalEngine(cfg)
        var checked = 0
        for (data in up + down) {
            val p = engine.prepare(data)
            for (s in runAll(engine, data).filterIsInstance<Signal>()) {
                checked++
                val plan = s.plan
                val sign = if (s.direction == Direction.LONG) 1.0 else -1.0
                val r = plan.risk
                assertTrue(r > 0)
                assertTrue(sign * (plan.entry - plan.stop) > 0, "stop on the losing side")
                assertEquals(cfg.tp1R, plan.rAt(plan.tp1), 1e-9)
                assertEquals(cfg.tp2R, plan.rAt(plan.tp2), 1e-9)
                assertTrue(plan.rAt(plan.target) >= cfg.minRoomR - 1e-9, "target at least 3R away")
                assertTrue(plan.roomR >= cfg.minRoomR - 1e-9)
                assertEquals(plan.roomR, plan.rAt(plan.target), 1e-6)
                assertEquals(1.0, plan.tp1Fraction + plan.tp2Fraction + plan.runnerFraction, 1e-12)

                // Stop width within the ATR limits, measured on the data the engine saw.
                val side = if (s.direction == Direction.LONG) p.long else p.short
                val j4 = side.h4.series.closedIndexAt(Timeframe.H4, s.timeMs)
                assertTrue(r <= cfg.maxStopAtr4h * side.h4.atr[j4] + 1e-9, "stop too wide slipped through")
                assertTrue(r >= cfg.minStopAtr1h * side.h1.atr[s.barIndex] - 1e-9, "stop too tight slipped through")
                assertEquals(plan.trailDistance, cfg.runnerTrailAtr4h * side.h4.atr[j4], 1e-9)

                // Entry is the trigger candle's close.
                assertEquals(data.h1.close[s.barIndex], plan.entry, 0.0)
                assertTrue(s.volumeRatio >= cfg.minVolumeRatio)
                assertTrue(s.volatilityRatio in cfg.regimeMin..cfg.regimeMax)
                assertTrue(s.adx4h >= cfg.minAdx)

                // Score parts within their maximums, grade matches the total.
                with(s.score) {
                    assertTrue(trend in 0..Score.MAX_TREND && location in 0..Score.MAX_LOCATION && trigger in 0..Score.MAX_TRIGGER)
                    assertTrue(volume in 0..Score.MAX_VOLUME && room in 0..Score.MAX_ROOM && context in 0..Score.MAX_CONTEXT)
                    assertTrue(total in 0..100)
                }
                val expected = when {
                    s.score.total >= cfg.aPlusScore -> Grade.A_PLUS
                    s.score.total >= cfg.alertScore -> Grade.ALERT
                    else -> Grade.LOG_ONLY
                }
                assertEquals(expected, s.grade)
                assertTrue(s.setups.isNotEmpty() && s.confluences.isNotEmpty())
                assertTrue(s.reasons.size >= 5 && s.reasons.none { it.isBlank() })
            }
        }
        assertTrue(checked >= 80, "checked $checked signals")
    }

    @Test fun neverLooksIntoTheFuture() {
        // Judge bars with the full history loaded, then again with only what existed at that bar's
        // close. Any difference means a rule peeked ahead.
        val engine = SignalEngine(StrategyConfig(allowShorts = true))
        for (data in listOf(up[0], down)) {
            val full = engine.prepare(data)
            val all = runAll(engine, data)
            val signalBars = all.filterIsInstance<Signal>().map { it.barIndex }.distinct()
            val firstJudged = all.first { !(it is Rejected && it.gate == Gate.INSUFFICIENT_DATA) }.barIndex
            val sample = (signalBars + (firstJudged until data.h1.size step 97)).distinct()
            for (i in sample) {
                val past = engine.prepare(Synthetic.truncatedAt(data, i))
                for (d in engine.directions()) {
                    assertEquals(engine.evaluate(full, i, d), engine.evaluate(past, i, d), "bar $i $d changed when future candles were removed")
                }
            }
            assertTrue(signalBars.isNotEmpty())
        }
    }

    @Test fun shortsAreExactMirrorsOfLongs() {
        val engine = SignalEngine(StrategyConfig(allowShorts = true))
        val data = up[1]
        val longP = engine.prepare(data)
        val flipP = engine.prepare(data.mirrored())
        var compared = 0
        for (i in 0 until data.h1.size) {
            val l = engine.evaluate(longP, i, Direction.LONG)
            val sEval = engine.evaluate(flipP, i, Direction.SHORT)
            if (l is Signal) {
                assertTrue(sEval is Signal, "bar $i: long signal but no mirrored short")
                val s = sEval as Signal
                assertEquals(l.score, s.score)
                assertEquals(l.setups, s.setups)
                assertEquals(l.plan.entry, -s.plan.entry, 0.0)
                assertEquals(l.plan.stop, -s.plan.stop, 1e-9)
                assertEquals(l.plan.target, -s.plan.target, 1e-9)
                assertEquals(l.plan.roomR, s.plan.roomR, 1e-12)
                compared++
            } else {
                assertEquals((l as Rejected).gate, (sEval as Rejected).gate, "bar $i")
            }
        }
        assertTrue(compared > 10)
    }

    @Test fun shortPlanPricesAreOnTheRightSide() {
        val s = runAll(SignalEngine(StrategyConfig(allowShorts = true)), down).filterIsInstance<Signal>().first()
        assertEquals(Direction.SHORT, s.direction)
        with(s.plan) { assertTrue(stop > entry && entry > tp1 && tp1 > tp2 && tp2 >= target - 1e-9) }
        assertTrue(s.reasons.first().contains("down"))
    }

    @Test fun btcDowntrendBlocksAltcoinLongs() {
        val h1 = up[0].h1.candles
        val btcUp = Series(Resample.from1h(h1, Timeframe.H4))
        val btcDown = Series(Resample.from1h(down.h1.candles, Timeframe.H4).map { it.copy() })
        val engine = SignalEngine(StrategyConfig(allowShorts = false))
        val withUp = runAll(engine, Synthetic.marketData(Assets.ETH, h1, btcUp))
        val withDown = runAll(engine, Synthetic.marketData(Assets.ETH, h1, btcDown))
        val signalsUp = withUp.filterIsInstance<Signal>()
        val signalsDown = withDown.filterIsInstance<Signal>()
        assertTrue(signalsUp.size >= 20)
        assertTrue(withDown.count { it is Rejected && it.gate == Gate.BTC_FILTER } >= signalsUp.size / 2)
        assertTrue(signalsDown.size < signalsUp.size / 4, "most ETH longs blocked while BTC falls")
        // Same candles as BTC itself with BTC agreeing: same signal bars as for BTC.
        val btcSelf = runAll(engine, up[0]).filterIsInstance<Signal>().map { it.barIndex }
        assertEquals(btcSelf, signalsUp.map { it.barIndex })
        // BTC either agrees (+6 context) or is neutral, dipping below its EMA 50 (+3, no "agrees" line).
        val (agrees, neutral) = signalsUp.partition { s -> s.reasons.any { it.contains("BTC 4H trend agrees") } }
        assertTrue(agrees.isNotEmpty())
        assertTrue(neutral.all { it.score.context <= 3 + 4 })
        assertTrue(signalsDown.isEmpty(), "every ETH long blocked while BTC's 4H trend is down")
    }

    @Test fun missingBtcDataDoesNotBlockButCostsContextPoints() {
        val engine = SignalEngine()
        val eth = runAll(engine, Synthetic.marketData(Assets.ETH, up[0].h1.candles, btcH4 = null)).filterIsInstance<Signal>()
        assertTrue(eth.isNotEmpty())
        assertTrue(eth.all { it.score.context <= 4 && it.reasons.any { r -> r.contains("BTC data unavailable") } })
    }

    @Test fun metalsSkipTheWeekend() {
        val gold = Synthetic.marketData(Assets.GOLD, up[0].h1.candles.map { it.copy(open = it.open / 30, high = it.high / 30, low = it.low / 30, close = it.close / 30) })
        val results = runAll(SignalEngine(), gold)
        for (e in results) {
            if (Assets.GOLD.underlyingClosedAt(e.timeMs)) assertTrue(e is Rejected && e.gate == Gate.MARKET_CLOSED)
            else assertTrue(!(e is Rejected && e.gate == Gate.MARKET_CLOSED))
        }
        assertTrue(results.any { it is Signal }, "gold still signals on weekdays")
        assertTrue(results.filterIsInstance<Signal>().none { Assets.GOLD.underlyingClosedAt(it.timeMs) })
    }

    @Test fun everyGateCanFire() {
        val data = up[0]
        val seen = HashSet<Gate>()
        fun collect(cfg: StrategyConfig) = runAll(SignalEngine(cfg), data).filterIsInstance<Rejected>().forEach { seen += it.gate }
        collect(StrategyConfig())
        collect(StrategyConfig(minStopAtr1h = 5.0))
        collect(StrategyConfig(regimeMin = 0.99, regimeMax = 1.01))
        collect(StrategyConfig(newsBlackouts = listOf(Synthetic.START..Synthetic.START + 365L * 86_400_000)))
        collect(StrategyConfig(minVolumeRatio = 3.0))
        runAll(SignalEngine(), Synthetic.marketData(Assets.ETH, data.h1.candles, Series(Resample.from1h(down.h1.candles, Timeframe.H4))))
            .filterIsInstance<Rejected>().forEach { seen += it.gate }
        runAll(SignalEngine(), Synthetic.marketData(Assets.GOLD, data.h1.candles)).filterIsInstance<Rejected>().forEach { seen += it.gate }
        assertEquals(Gate.entries.toSet(), seen, "gates never seen: ${Gate.entries - seen}")
    }

    @Test fun rejectionsExplainThemselves() {
        val rejected = runAll(SignalEngine(), up[0]).filterIsInstance<Rejected>()
        assertTrue(rejected.all { it.detail.isNotBlank() })
        val room = rejected.first { it.gate == Gate.NOT_ENOUGH_ROOM }
        assertTrue(room.detail.contains("R away (need 3.0R)"), room.detail)
    }

    @Test fun shortsAreOnByDefaultForFutures() = assertEquals(listOf(Direction.LONG, Direction.SHORT), SignalEngine().directions())

    @Test fun evaluateLatestJudgesTheNewestBar() {
        val engine = SignalEngine(StrategyConfig(allowShorts = true))
        val latest = engine.evaluateLatest(engine.prepare(up[0]))
        assertEquals(2, latest.size)
        assertTrue(latest.all { it.barIndex == up[0].h1.lastIndex })
        assertEquals(up[0].h1.openTime.last() + Timeframe.H1.millis, latest[0].timeMs)
    }

    @Test fun tooLittleHistoryIsReportedNotGuessed() {
        val short = Synthetic.marketData(Assets.GOLD, up[0].h1.candles.take(24 * 150))
        val longOnly = SignalEngine(StrategyConfig(allowShorts = false))
        val e = longOnly.evaluateLatest(longOnly.prepare(short)).single()
        assertTrue(e is Rejected && (e.gate == Gate.INSUFFICIENT_DATA || e.gate == Gate.MARKET_CLOSED))
        val weekday = (short.h1.lastIndex downTo 0).first { !Assets.GOLD.underlyingClosedAt(short.h1.openTime[it] + 3_600_000) }
        val e2 = SignalEngine().evaluate(SignalEngine().prepare(short), weekday, Direction.LONG) as Rejected
        assertEquals(Gate.INSUFFICIENT_DATA, e2.gate)
        assertTrue(Regex("""1D 1[45]\d/203""").containsMatchIn(e2.detail), e2.detail)
    }
}
