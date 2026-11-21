package com.signalbot.engine.strategy

import com.signalbot.engine.indicators.Indicators
import com.signalbot.engine.model.Timeframe

/**
 * Version 3 strategy: Donchian breakout ENSEMBLE (after Zarattini, Pagani & Barbon, "Catching Crypto
 * Trends", 2025), chosen by deep research and confirmed on unseen Binance futures data
 * (Jul 2025 - Nov 2026). See README "Version 3".
 *
 * Each asset runs four independent "legs", one per lookback N in 20, 30, 60, 90 days:
 *  - ENTER LONG  when a daily close is above the highest daily CLOSE of the previous N days and above
 *                the 200-day EMA. SHORT is the mirror (below the lowest close, below the EMA).
 *  - STOP        the middle of the N-day closing range, (highest close + lowest close) / 2. Each day
 *                it moves to the new middle if that is better, never back.
 *  - EXIT        when a daily CLOSE is beyond the stop. Orders go in at the next candle's open.
 *  - A leg that is in a trade ignores new breakouts until it exits; after an exit it may re-enter on
 *    a later close.
 *
 * Signals happen only at the daily close (00:00 UTC, 05:30 IST). Legs on one asset often agree, so
 * risk per leg should be small (0.5% suggested: up to 2% on one asset).
 */
data class EnsembleConfig(
    val legs: List<Int> = listOf(20, 30, 60, 90),
    val regimeEma: Int = 200,
    val allowShorts: Boolean = true,
    /** Skip entries whose stop is closer than this fraction of price: costs would swamp them. */
    val minRiskFraction: Double = 0.002,
) {
    init {
        require(legs.isNotEmpty() && legs.all { it >= 2 }) { "legs must be lookbacks of 2+ days" }
        require(legs.toSet().size == legs.size) { "duplicate legs" }
    }
}

enum class LegAction { ENTER, EXIT }

/** One leg decision at a daily close. Act on it at the next candle's open. */
data class LegEvent(
    val assetId: String,
    val leg: Int,
    val action: LegAction,
    val direction: Direction,
    /** Index of the daily candle whose close produced the event. */
    val dayIndex: Int,
    /** That candle's close time (epoch ms, 00:00 UTC). */
    val timeMs: Long,
    val close: Double,
    /** ENTER: the initial stop. EXIT: the stop that was crossed. */
    val stop: Double,
    val reasons: List<String>,
)

/** A leg in a trade as of the replay's last day, with its current stop. */
data class OpenLeg(
    val assetId: String,
    val leg: Int,
    val direction: Direction,
    val entryDayIndex: Int,
    val signalClose: Double,
    val initialStop: Double,
    val stop: Double,
)

class EnsembleReplay(val events: List<LegEvent>, val open: List<OpenLeg>)

class EnsembleEngine(val cfg: EnsembleConfig = EnsembleConfig()) {

    /**
     * Replays every leg over the daily history up to and including [lastDay]. Deterministic: the same
     * candles always give the same events, so the app, the scanner and the backtest agree. Each day's
     * decision uses only candles up to that day.
     */
    fun replay(data: MarketData, lastDay: Int = data.d1.lastIndex): EnsembleReplay {
        val d = data.d1
        val id = data.asset.id
        val ema = Indicators.ema(d.close, cfg.regimeEma)
        val events = ArrayList<LegEvent>()
        val open = ArrayList<OpenLeg>()
        for (n in cfg.legs) {
            val maxC = Indicators.highest(d.close, n)
            val minC = Indicators.lowest(d.close, n)
            var dir = 0
            var stop = 0.0
            var initialStop = 0.0
            var entryDay = -1
            var signalClose = 0.0
            for (j in n + 1..minOf(lastDay, d.lastIndex)) {
                val c = d.close[j]
                val t = d.openTime[j] + Timeframe.D1.millis
                val mid = (maxC[j] + minC[j]) / 2
                if (dir != 0) {
                    stop = if (dir > 0) maxOf(stop, mid) else minOf(stop, mid)
                    if (if (dir > 0) c < stop else c > stop) {
                        events += LegEvent(id, n, LegAction.EXIT, if (dir > 0) Direction.LONG else Direction.SHORT, j, t, c, stop,
                            listOf("Daily close ${formatPrice(c)} is ${if (dir > 0) "below" else "above"} the $n-day leg's stop " +
                                "${formatPrice(stop)}: close this leg at the next open"))
                        dir = 0
                    }
                    continue
                }
                val trendKnown = !ema[j].isNaN()
                val newDir = when {
                    trendKnown && c > maxC[j - 1] && c > ema[j] -> 1
                    cfg.allowShorts && trendKnown && c < minC[j - 1] && c < ema[j] -> -1
                    else -> 0
                }
                if (newDir == 0) continue
                val st = (maxOf(maxC[j], c) + minOf(minC[j], c)) / 2
                if (newDir * (c - st) <= c * cfg.minRiskFraction) continue
                dir = newDir; stop = st; initialStop = st; entryDay = j; signalClose = c
                val long = dir > 0
                events += LegEvent(id, n, LegAction.ENTER, if (long) Direction.LONG else Direction.SHORT, j, t, c, st, listOf(
                    "Daily close ${formatPrice(c)} broke the $n-day closing ${if (long) "high" else "low"} of " +
                        formatPrice(if (long) maxC[j - 1] else minC[j - 1]),
                    "Trend: ${if (long) "above" else "below"} the ${cfg.regimeEma}-day EMA (${formatPrice(ema[j])})",
                    "Stop ${formatPrice(st)}: the middle of the $n-day closing range. It moves with the range's middle, " +
                        "only in your favour; exit when a daily close crosses it",
                ))
            }
            if (dir != 0) open += OpenLeg(id, n, if (dir > 0) Direction.LONG else Direction.SHORT, entryDay, signalClose, initialStop, stop)
        }
        return EnsembleReplay(events.sortedWith(compareBy({ it.dayIndex }, { it.leg })), open.sortedBy { it.leg })
    }
}
