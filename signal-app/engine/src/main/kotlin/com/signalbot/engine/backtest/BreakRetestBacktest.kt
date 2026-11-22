package com.signalbot.engine.backtest

import com.signalbot.engine.history.FundingEvent
import com.signalbot.engine.model.Series
import com.signalbot.engine.strategy.BrAction
import com.signalbot.engine.strategy.BreakRetestReplay
import com.signalbot.engine.strategy.Direction
import kotlin.math.abs

/** One break-and-retest trade with costs. R = distance from the actual fill to the initial stop. */
data class BrResult(
    val assetId: String,
    val direction: Direction,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val initialStop: Double,
    val target: Double?,
    val grossR: Double,
    val feesR: Double,
    val fundingR: Double,
    val reason: String,
    val openAtEnd: Boolean,
) {
    val netR: Double get() = grossR - feesR - fundingR
    fun row() = Stats.Row(entryTime, exitTime, netR, feesR, fundingR,
        when { openAtEnd -> Outcome.OPEN_AT_END; netR > 0 -> Outcome.RUNNER; else -> Outcome.LOSS })
}

/**
 * Prices a replay's trades as they would really fill: entry at the next candle's open, stop exits at the
 * stop (or the open, if price gapped through), target exits at the target. Each side pays the taker fee
 * plus slippage (0.05% + 0.03% by default) and funding is charged while the trade is open.
 * Runs on the phone too, so it depends on nothing but the replay and the candles.
 */
object BreakRetestBacktest {
    fun price(
        assetId: String,
        replay: BreakRetestReplay,
        funding: List<FundingEvent> = emptyList(),
        feePerSide: Double = 0.0005,
        slippagePerSide: Double = 0.0003,
    ): List<BrResult> {
        val s: Series = replay.series
        val side = feePerSide + slippagePerSide
        val out = ArrayList<BrResult>()
        for (t in replay.trades) {
            if (t.entryIndex > s.lastIndex) continue // signal on the newest candle: not filled yet
            val sign = if (t.direction == Direction.LONG) 1.0 else -1.0
            val fill = s.open[t.entryIndex] * (1 + sign * side)
            val r = sign * (fill - t.initialStop)
            if (r <= 0) continue // opened beyond the stop: skip, as a trader would
            val open = t.exitIndex == null
            val rawExit = if (open) s.close[s.lastIndex] else t.exitPrice!!
            val exitTime = if (open) s.openTime[s.lastIndex] + (s.openTime[1] - s.openTime[0]) else s.openTime[t.exitIndex!!]
            val exitPx = rawExit * (1 - sign * side)
            val entryTime = s.openTime[t.entryIndex]
            out += BrResult(
                assetId, t.direction, entryTime, exitTime, fill, exitPx, t.initialStop, t.target,
                grossR = sign * (exitPx - fill) / r,
                feesR = side * (abs(fill) + abs(exitPx)) / r,
                fundingR = EnsembleBacktest.fundingR(funding, entryTime, exitTime, sign, fill, r),
                reason = when (t.exitReason) { BrAction.EXIT_TARGET -> "target"; BrAction.EXIT_STOP -> "stop"; else -> "open" },
                openAtEnd = open,
            )
        }
        return out
    }
}
