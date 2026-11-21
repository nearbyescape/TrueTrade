package com.signalbot.engine.strategy

enum class Direction { LONG, SHORT }

enum class SetupType { TREND_PULLBACK, BREAKOUT_RETEST, LIQUIDITY_SWEEP }

/** Independent reasons the entry level matters. Two or more at once = full location points. */
enum class Confluence { EMA_ZONE, FIB_ZONE, BREAKOUT_LEVEL, SWEPT_LEVEL }

enum class Grade { A_PLUS, ALERT, LOG_ONLY }

/** Hard gates, in the order they are checked. The first one that fails is reported. */
enum class Gate {
    INSUFFICIENT_DATA, MARKET_CLOSED, NEWS, TREND, BTC_FILTER, VOLATILITY,
    NO_SETUP, NO_TRIGGER, LOW_VOLUME, STOP_TOO_WIDE, STOP_TOO_TIGHT, NOT_ENOUGH_ROOM,
}

data class Score(
    val trend: Int,
    val location: Int,
    val trigger: Int,
    val volume: Int,
    val room: Int,
    val context: Int,
) {
    val total: Int get() = trend + location + trigger + volume + room + context

    companion object {
        const val MAX_TREND = 25
        const val MAX_LOCATION = 20
        const val MAX_TRIGGER = 15
        const val MAX_VOLUME = 15
        const val MAX_ROOM = 15
        const val MAX_CONTEXT = 10
    }
}

/**
 * The trade, in real prices. For a SHORT, stop and targets sit on the other side of entry.
 * Exits: [tp1Fraction] at TP1 (then stop to entry), [tp2Fraction] at TP2, the rest trails
 * [trailDistance] behind the best price.
 */
data class TradePlan(
    val direction: Direction,
    val entry: Double,
    val stop: Double,
    val tp1: Double,
    val tp2: Double,
    val target: Double,
    /** Distance to the final target in R. */
    val roomR: Double,
    /** True when no resistance was found overhead and the target was set at a fixed R. */
    val openSky: Boolean,
    val tp1Fraction: Double,
    val tp2Fraction: Double,
    val runnerFraction: Double,
    val trailDistance: Double,
) {
    /** Price distance from entry to stop: 1R. */
    val risk: Double get() = kotlin.math.abs(entry - stop)

    /** Profit or loss at [price], in R. */
    fun rAt(price: Double): Double = (if (direction == Direction.LONG) price - entry else entry - price) / risk
}

sealed interface Evaluation {
    val assetId: String
    val direction: Direction
    /** Decision time: the close of the 1H candle that was judged (epoch ms). */
    val timeMs: Long
    /** Index of that 1H candle in the input series. */
    val barIndex: Int
}

data class Signal(
    override val assetId: String,
    override val direction: Direction,
    override val timeMs: Long,
    override val barIndex: Int,
    val setups: Set<SetupType>,
    val confluences: Set<Confluence>,
    val plan: TradePlan,
    val score: Score,
    val grade: Grade,
    /** Plain-language reasons for the notification and the signal screen. */
    val reasons: List<String>,
    val adx4h: Double,
    val volumeRatio: Double,
    val volatilityRatio: Double,
) : Evaluation

data class Rejected(
    override val assetId: String,
    override val direction: Direction,
    override val timeMs: Long,
    override val barIndex: Int,
    val gate: Gate,
    val detail: String,
) : Evaluation
