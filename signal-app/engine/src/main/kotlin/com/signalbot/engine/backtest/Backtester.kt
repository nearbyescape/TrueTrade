package com.signalbot.engine.backtest

import com.signalbot.engine.history.FundingEvent
import com.signalbot.engine.model.Timeframe
import com.signalbot.engine.strategy.Direction
import com.signalbot.engine.strategy.MarketData
import com.signalbot.engine.strategy.Prepared
import com.signalbot.engine.strategy.Signal
import com.signalbot.engine.strategy.SignalEngine
import com.signalbot.engine.strategy.StrategyConfig
import com.signalbot.engine.strategy.TrendEngine
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.max

/** Trading costs. Defaults: Binance USD-M regular tier, taker for market orders, maker for limit take-profits. */
data class CostModel(
    val takerFee: Double = 0.0005,
    val makerFee: Double = 0.0002,
    /** Price slippage on every market fill (entry, stop, time exit), as a fraction of price. */
    val slippage: Double = 0.0005,
    val funding: Boolean = true,
)

/**
 * SCALED = version 1 exits: 40% at TP1 (stop to entry), 30% at TP2 (stop to +1R), runner trails.
 * TRAIL  = trend-following: whole position rides; the stop only ever moves up, trailing
 *          [com.signalbot.engine.strategy.TradePlan.trailDistance] behind the highest high since entry.
 */
enum class ExitStyle { SCALED, TRAIL }

data class BacktestSettings(
    val exitStyle: ExitStyle = ExitStyle.SCALED,
    /** Only signals scoring at least this are traded (75 = alert threshold). */
    val minScore: Int = 75,
    /** Skip the trade if the next candle opens more than this many R past the planned entry. */
    val maxChaseR: Double = 0.5,
    /** Close at market if TP1 hasn't been hit after this many hours. 0 = off. */
    val timeStopBars: Int = 48,
    /** Close the runner at the final target instead of letting the trail decide. */
    val runnerExitAtTarget: Boolean = false,
    val maxOpenTrades: Int = 3,
    val maxPerGroup: Int = 2,
    val dailyLossLimitR: Double = 2.0,
    val weeklyLossLimitR: Double = 5.0,
    val cooldownHours: Int = 12,
    /** Capital risked per trade, for the % equity curve. */
    val riskPerTrade: Double = 0.01,
    val costs: CostModel = CostModel(),
    /** Only signals at or after this time open trades (warm-up before it still feeds the indicators). */
    val tradeFrom: Long = Long.MIN_VALUE,
    val tradeUntil: Long = Long.MAX_VALUE,
)

enum class ExitReason { STOP, BREAKEVEN_STOP, PROFIT_STOP, TP1, TP2, TARGET, TIME, END_OF_DATA }

/** How a trade ended overall. */
enum class Outcome { LOSS, TIME_EXIT, TP1_THEN_BREAKEVEN, TP2, RUNNER, OPEN_AT_END }

data class Fill(val timeMs: Long, val price: Double, val fraction: Double, val reason: ExitReason)

data class Trade(
    val assetId: String,
    val group: String,
    val direction: Direction,
    val signal: Signal,
    val entryTime: Long,
    val entryPrice: Double,
    val initialStop: Double,
    val fills: List<Fill>,
    /** Price result in R (1R = entry-to-stop distance at the actual fill). */
    val grossR: Double,
    val feesR: Double,
    /** Funding paid (positive) or received (negative), in R. */
    val fundingR: Double,
    val outcome: Outcome,
) {
    val netR: Double get() = grossR - feesR - fundingR
    val exitTime: Long get() = fills.last().timeMs
    val holdHours: Double get() = (exitTime - entryTime) / 3_600_000.0
}

/** Why a signal at or above the score threshold was not traded. */
enum class SkipReason { ALREADY_IN_TRADE, COOLDOWN, LOSS_LIMIT_PAUSE, MAX_OPEN_TRADES, GROUP_LIMIT, CHASED_TOO_FAR, NO_NEXT_CANDLE }

/** One asset's history, prepared, with every bar already judged in each direction. */
class AssetTape(
    val prepared: Prepared,
    val long: Array<Signal?>,
    val short: Array<Signal?>,
    val funding: List<FundingEvent>,
) {
    val asset get() = prepared.data.asset
    val h1 get() = prepared.data.h1

    companion object {
        /** Judge every 1H bar once. Simulations with different trade settings then reuse this. */
        fun build(engine: SignalEngine, prepared: Prepared, funding: List<FundingEvent>): AssetTape {
            val n = prepared.data.h1.size
            val long = arrayOfNulls<Signal>(n)
            val short = arrayOfNulls<Signal>(n)
            for (i in 0 until n) {
                for (d in engine.directions()) {
                    val e = engine.evaluate(prepared, i, d) as? Signal ?: continue
                    if (d == Direction.LONG) long[i] = e else short[i] = e
                }
            }
            return AssetTape(prepared, long, short, funding)
        }

        /** Same, for the version 2 daily trend strategy. Backtest it with [ExitStyle.TRAIL]. */
        fun buildTrend(engine: TrendEngine, data: MarketData, funding: List<FundingEvent>): AssetTape {
            val p = engine.prepare(data)
            val n = data.h1.size
            val long = arrayOfNulls<Signal>(n)
            val short = arrayOfNulls<Signal>(n)
            for (i in 0 until n) {
                if ((data.h1.openTime[i] + HOUR_MS) % DAY_MS != 0L) continue // only daily closes can signal
                for (d in engine.directions()) {
                    val e = engine.evaluate(p, i, d) as? Signal ?: continue
                    if (d == Direction.LONG) long[i] = e else short[i] = e
                }
            }
            return AssetTape(Prepared(data, StrategyConfig()), long, short, funding)
        }

        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
    }
}

class BacktestResult(
    val trades: List<Trade>,
    val skipped: Map<SkipReason, Int>,
    val settings: BacktestSettings,
)

/**
 * Portfolio simulation across assets, hour by hour, as you would trade it by hand:
 *  - a signal at a 1H close is entered at the NEXT candle's open, with slippage (skipped if price ran > 0.5R);
 *  - 40% exits at TP1 and the stop moves to entry; 30% at TP2 and the stop moves to +1R;
 *    the last 30% trails 3 x 4H ATR behind the best price;
 *  - no TP1 within 48 hours -> close at market;
 *  - fees on every fill, funding every 8 hours, max open trades, per-group limit, cooldown, loss limits.
 *
 * Hourly candles don't show the order of the high and low inside the hour, so the simulator assumes
 * the WORSE order every time: if a candle touches both the stop and a target, the stop came first;
 * if TP1 fills and the same candle also reaches the new breakeven stop, that stop is taken too.
 * Real results should therefore be equal to or better than these, never flattered.
 */
class Backtester(private val s: BacktestSettings = BacktestSettings()) {

    fun run(tapes: List<AssetTape>): BacktestResult {
        val trades = ArrayList<Trade>()
        val skipped = HashMap<SkipReason, Int>()
        fun skip(r: SkipReason) { skipped.merge(r, 1, Int::plus) }

        val states = tapes.map { AssetState(it) }
        val times = sortedSetOf<Long>().apply { tapes.forEach { t -> t.h1.openTime.forEach { add(it) } } }
        val lastCooldown = HashMap<Pair<String, Direction>, Long>()
        val dayR = HashMap<Long, Double>()
        val weekR = HashMap<Long, Double>()
        var pausedUntil = Long.MIN_VALUE

        fun record(trade: Trade) {
            trades += trade
            val day = Math.floorDiv(trade.exitTime, DAY)
            val week = weekStart(trade.exitTime)
            val d = dayR.merge(day, trade.netR, Double::plus)!!
            val w = weekR.merge(week, trade.netR, Double::plus)!!
            if (d <= -s.dailyLossLimitR) pausedUntil = max(pausedUntil, trade.exitTime + DAY)
            if (w <= -s.weeklyLossLimitR) pausedUntil = max(pausedUntil, week + 7 * DAY)
        }

        for (t in times) {
            // 1. Each asset's candle opening at t: fill pending entries, then manage open trades.
            for (st in states) {
                val i = st.indexAt(t) ?: continue
                st.pending?.let { sig ->
                    st.pending = null
                    val open = st.open(sig, i)
                    if (open == null) skip(SkipReason.CHASED_TOO_FAR) else st.trade = open
                }
                st.trade?.let { tr -> if (tr.onBar(i, last = i == st.tape.h1.lastIndex)) { record(tr.toTrade()); st.trade = null } }
            }
            // 2. Signals at the close of the candle that opened at t, best score first.
            val candidates = ArrayList<Pair<AssetState, Signal>>()
            for (st in states) {
                val i = st.indexAt(t) ?: continue
                for (sig in listOfNotNull(st.tape.long[i], st.tape.short[i])) {
                    if (sig.score.total < s.minScore || sig.timeMs < s.tradeFrom || sig.timeMs >= s.tradeUntil) continue
                    candidates += st to sig
                }
            }
            candidates.sortByDescending { it.second.score.total }
            for ((st, sig) in candidates) {
                val key = st.tape.asset.id to sig.direction
                val cooling = lastCooldown[key]?.let { sig.timeMs - it < s.cooldownHours * HOUR } ?: false
                if (st.trade != null || st.pending != null) { skip(SkipReason.ALREADY_IN_TRADE); continue }
                if (cooling) { skip(SkipReason.COOLDOWN); continue }
                lastCooldown[key] = sig.timeMs
                if (sig.timeMs < pausedUntil) { skip(SkipReason.LOSS_LIMIT_PAUSE); continue }
                val active = states.filter { it.trade != null || it.pending != null }
                if (active.size >= s.maxOpenTrades) { skip(SkipReason.MAX_OPEN_TRADES); continue }
                if (active.count { it.tape.asset.correlationGroup == st.tape.asset.correlationGroup } >= s.maxPerGroup) {
                    skip(SkipReason.GROUP_LIMIT); continue
                }
                if (st.indexAt(sig.timeMs) == null) { skip(SkipReason.NO_NEXT_CANDLE); continue }
                st.pending = sig
            }
        }
        // Anything still open is closed at its last price (marked OPEN_AT_END).
        for (st in states) st.trade?.let { record(it.forceClose()) }
        return BacktestResult(trades.sortedBy { it.exitTime }, skipped, s)
    }

    /** Per-asset cursor through its candles. */
    private inner class AssetState(val tape: AssetTape) {
        var pending: Signal? = null
        var trade: OpenTrade? = null
        private var cursor = 0

        /** Index of this asset's candle opening at [t], or null if it has none. Times only move forward. */
        fun indexAt(t: Long): Int? {
            val ot = tape.h1.openTime
            while (cursor < ot.size && ot[cursor] < t) cursor++
            return if (cursor < ot.size && ot[cursor] == t) cursor else null
        }

        fun open(sig: Signal, i: Int): OpenTrade? {
            val long = sig.direction == Direction.LONG
            fun lf(p: Double) = if (long) p else -p // long-frame price
            val o = lf(tape.h1.open[i])
            val fill = o + s.costs.slippage * abs(o)
            val stop = lf(sig.plan.stop)
            val plannedR = lf(sig.plan.entry) - stop
            if (fill <= stop || fill - lf(sig.plan.entry) > s.maxChaseR * plannedR) return null
            return OpenTrade(tape, sig, i, fill, stop)
        }
    }

    /**
     * A live position, computed in "long frame" prices (shorts are negated) so one set of rules
     * serves both directions. Sizes are normalised so the initial stop-out loses exactly 1R.
     */
    private inner class OpenTrade(val tape: AssetTape, val sig: Signal, val entryIndex: Int, val fill: Double, stop0: Double) {
        private val long = sig.direction == Direction.LONG
        private fun lf(p: Double) = if (long) p else -p
        private val r = fill - stop0
        private val tp1 = lf(sig.plan.tp1)
        private val tp2 = lf(sig.plan.tp2)
        private val target = lf(sig.plan.target)
        private val trail = sig.plan.trailDistance
        private var stop = stop0
        private val initialStop = stop0
        private var remaining = 1.0
        private var tp1Done = false
        private var tp2Done = false
        private var best = fill
        private var gross = 0.0
        private var fees = s.costs.takerFee * abs(fill) / r // entry fee, in R
        private var funding = 0.0
        private val fills = ArrayList<Fill>()
        private var lastIndex = entryIndex
        private var fundingPos = tape.funding.binarySearchBy(tape.h1.openTime[entryIndex]) { it.timeMs }.let { if (it < 0) -it - 1 else it }
        private val fundingStep: Long = tape.funding.takeLast(2).let { if (it.size == 2) it[1].timeMs - it[0].timeMs else 8 * HOUR }
        private var syntheticFundingTime: Long = tape.funding.lastOrNull()?.timeMs?.plus(fundingStep) ?: Long.MAX_VALUE

        private fun exit(timeMs: Long, price: Double, fraction: Double, reason: ExitReason, maker: Boolean) {
            val f = minOf(fraction, remaining)
            if (f <= 0) return
            gross += f * (price - fill) / r
            fees += f * (if (maker) s.costs.makerFee else s.costs.takerFee) * abs(price) / r
            fills += Fill(timeMs, lf(price), f, reason)
            remaining -= f
        }

        private fun stopReason() = when {
            s.exitStyle == ExitStyle.TRAIL -> if (stop > fill) ExitReason.PROFIT_STOP else ExitReason.STOP
            tp2Done -> ExitReason.PROFIT_STOP
            tp1Done -> ExitReason.BREAKEVEN_STOP
            else -> ExitReason.STOP
        }

        private fun marketExitAll(timeMs: Long, price: Double, reason: ExitReason) =
            exit(timeMs, price - s.costs.slippage * abs(price), remaining, reason, maker = false)

        private fun applyFunding(barOpenTime: Long, barOpenPrice: Double) {
            if (!s.costs.funding) return
            val end = barOpenTime + HOUR
            val sign = if (long) 1.0 else -1.0
            val fs = tape.funding
            while (fundingPos < fs.size && fs[fundingPos].timeMs < end) {
                if (fs[fundingPos].timeMs >= barOpenTime) funding += sign * fs[fundingPos].rate * remaining * abs(barOpenPrice) / r
                fundingPos++
            }
            // The archive has no funding for the current month yet: repeat the last known rate on schedule.
            if (fundingPos >= fs.size && fs.isNotEmpty()) {
                while (syntheticFundingTime < end) {
                    if (syntheticFundingTime >= barOpenTime) funding += sign * fs.last().rate * remaining * abs(barOpenPrice) / r
                    syntheticFundingTime += fundingStep
                }
            }
        }

        /** Process candle [i]. Returns true when the position is fully closed. */
        fun onBar(i: Int, last: Boolean): Boolean {
            lastIndex = i
            val h1 = tape.h1
            val t = h1.openTime[i]
            val o = lf(h1.open[i])
            val hi = if (long) h1.high[i] else -h1.low[i]
            val lo = if (long) h1.low[i] else -h1.high[i]
            val c = lf(h1.close[i])
            applyFunding(t, o)

            // Price opened through the stop (gap): out at the open.
            if (o <= stop) { marketExitAll(t, o, stopReason()); return true }
            // Stop touched inside the candle: assumed BEFORE any target in the same candle.
            if (lo <= stop) { marketExitAll(t + HOUR - 1, stop, stopReason()); return true }

            if (s.exitStyle == ExitStyle.TRAIL) {
                // Raise the stop from this candle's high; it applies from the next candle on.
                best = max(best, hi)
                stop = max(stop, best - trail)
                if (last) { marketExitAll(t + HOUR - 1, c, ExitReason.END_OF_DATA); return true }
                return false
            }

            if (!tp1Done && hi >= tp1) {
                exit(t + HOUR - 1, tp1, sig.plan.tp1Fraction, ExitReason.TP1, maker = true)
                tp1Done = true
                stop = fill
                if (lo <= stop) { marketExitAll(t + HOUR - 1, stop, ExitReason.BREAKEVEN_STOP); return true }
            }
            if (tp1Done && !tp2Done && hi >= tp2) {
                exit(t + HOUR - 1, tp2, sig.plan.tp2Fraction, ExitReason.TP2, maker = true)
                tp2Done = true
                stop = fill + r
                if (lo <= stop) { marketExitAll(t + HOUR - 1, stop, ExitReason.PROFIT_STOP); return true }
            }
            if (s.runnerExitAtTarget && tp2Done && hi >= target) {
                exit(t + HOUR - 1, target, remaining, ExitReason.TARGET, maker = true); return true
            }
            // Trail the runner from this candle's high (used from the next candle on).
            best = max(best, hi)
            if (tp2Done) stop = max(stop, best - trail)

            if (s.timeStopBars > 0 && !tp1Done && i - entryIndex + 1 >= s.timeStopBars) {
                marketExitAll(t + HOUR - 1, c, ExitReason.TIME); return true
            }
            if (last) { marketExitAll(t + HOUR - 1, c, ExitReason.END_OF_DATA); return true }
            return false
        }

        fun forceClose(): Trade {
            if (remaining > 1e-12) marketExitAll(tape.h1.openTime[lastIndex] + HOUR - 1, lf(tape.h1.close[lastIndex]), ExitReason.END_OF_DATA)
            return toTrade()
        }

        fun toTrade(): Trade {
            val lastFill = fills.last()
            val outcome = when {
                lastFill.reason == ExitReason.END_OF_DATA -> Outcome.OPEN_AT_END
                s.exitStyle == ExitStyle.TRAIL -> if (gross > 0) Outcome.RUNNER else Outcome.LOSS
                tp2Done && (lastFill.reason == ExitReason.TARGET || lf(lastFill.price) > tp2) -> Outcome.RUNNER
                tp2Done -> Outcome.TP2
                tp1Done -> Outcome.TP1_THEN_BREAKEVEN
                lastFill.reason == ExitReason.TIME -> Outcome.TIME_EXIT
                else -> Outcome.LOSS
            }
            return Trade(tape.asset.id, tape.asset.correlationGroup, sig.direction, sig, tape.h1.openTime[entryIndex],
                lf(fill), lf(initialStop), fills.toList(), gross, fees, funding, outcome)
        }
    }

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR

        /** Monday 00:00 UTC of the week containing [t]. */
        fun weekStart(t: Long): Long = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        init { check(Timeframe.H1.millis == HOUR) }
    }
}
