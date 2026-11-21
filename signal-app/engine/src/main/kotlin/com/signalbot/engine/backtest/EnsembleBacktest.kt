package com.signalbot.engine.backtest

import com.signalbot.engine.history.FundingEvent
import com.signalbot.engine.strategy.Direction
import com.signalbot.engine.strategy.EnsembleEngine
import com.signalbot.engine.strategy.LegAction
import com.signalbot.engine.strategy.MarketData
import kotlin.math.abs

/** One completed (or still open) ensemble leg trade. R = distance from the actual fill to the initial stop. */
data class LegTrade(
    val assetId: String,
    val leg: Int,
    val direction: Direction,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val initialStop: Double,
    val grossR: Double,
    val feesR: Double,
    val fundingR: Double,
    val openAtEnd: Boolean,
) {
    val netR: Double get() = grossR - feesR - fundingR
    fun row() = Stats.Row(entryTime, exitTime, netR, feesR, fundingR,
        when { openAtEnd -> Outcome.OPEN_AT_END; netR > 0 -> Outcome.RUNNER; else -> Outcome.LOSS })
}

/**
 * Backtests the ensemble exactly as it would be traded from the app: every signal at a daily close is
 * filled at the next day's open, entries and exits both. Costs per side default to 0.05% taker fee plus
 * 0.03% slippage; funding is charged on the entry value at each funding time while the leg is open
 * (the archive's last known rate is repeated every 8 hours after it ends).
 *
 * Legs are independent; there are no portfolio limits here, so size each leg small.
 */
class EnsembleBacktest(
    private val engine: EnsembleEngine = EnsembleEngine(),
    private val feePerSide: Double = 0.0005,
    private val slippagePerSide: Double = 0.0003,
) {
    /** Entries that couldn't be taken because the next open was already beyond the stop. */
    var skippedGaps = 0
        private set

    fun run(data: MarketData, funding: List<FundingEvent>, tradeFrom: Long = Long.MIN_VALUE, tradeUntil: Long = Long.MAX_VALUE): List<LegTrade> {
        val d = data.d1
        val side = feePerSide + slippagePerSide
        val events = engine.replay(data).events
        val out = ArrayList<LegTrade>()
        // Pair each ENTER with the next EXIT of the same leg.
        val pending = HashMap<Int, com.signalbot.engine.strategy.LegEvent>()
        fun close(enter: com.signalbot.engine.strategy.LegEvent, exitDay: Int?) {
            val j = enter.dayIndex
            if (j + 1 > d.lastIndex) return // no next candle to fill on
            val entryTime = d.openTime[j + 1]
            if (entryTime < tradeFrom || entryTime >= tradeUntil) return
            val sign = if (enter.direction == Direction.LONG) 1.0 else -1.0
            val fill = d.open[j + 1] * (1 + sign * side)
            val r = sign * (fill - enter.stop)
            if (r <= 0) { skippedGaps++; return }
            val openAtEnd = exitDay == null || exitDay + 1 > d.lastIndex
            val exitRaw = if (openAtEnd) d.close[d.lastIndex] else d.open[exitDay!! + 1]
            val exitTime = if (openAtEnd) d.openTime[d.lastIndex] + 86_400_000L else d.openTime[exitDay!! + 1]
            val exitPx = exitRaw * (1 - sign * side)
            out += LegTrade(
                assetId = data.asset.id, leg = enter.leg, direction = enter.direction,
                entryTime = entryTime, exitTime = exitTime, entryPrice = fill, exitPrice = exitPx, initialStop = enter.stop,
                grossR = sign * (exitPx - fill) / r,
                feesR = side * abs(fill) / r + side * abs(exitPx) / r,
                fundingR = fundingR(funding, entryTime, exitTime, sign, fill, r),
                openAtEnd = openAtEnd,
            )
        }
        for (e in events) {
            when (e.action) {
                LegAction.ENTER -> pending[e.leg] = e
                LegAction.EXIT -> pending.remove(e.leg)?.let { close(it, e.dayIndex) }
            }
        }
        pending.values.forEach { close(it, null) }
        return out.sortedBy { it.exitTime }
    }

    companion object {
        private const val EIGHT_HOURS = 8 * 3_600_000L

        /** Funding paid (positive) or received over [from, to), in R. Longs pay positive rates. */
        fun fundingR(f: List<FundingEvent>, from: Long, to: Long, sign: Double, price: Double, r: Double): Double {
            if (f.isEmpty()) return 0.0
            var sum = 0.0
            var i = f.binarySearchBy(from) { it.timeMs }.let { if (it < 0) -it - 1 else it }
            while (i < f.size && f[i].timeMs < to) { sum += f[i].rate; i++ }
            if (to > f.last().timeMs) {
                // Keep the exchange's fixed 8-hour schedule, counted on from the last known event.
                var t = f.last().timeMs + EIGHT_HOURS
                if (t < from) t += ((from - t + EIGHT_HOURS - 1) / EIGHT_HOURS) * EIGHT_HOURS
                while (t < to) { sum += f.last().rate; t += EIGHT_HOURS }
            }
            return sign * sum * price / r
        }
    }
}
