package com.signalbot.engine.model

/**
 * One OHLCV candle. Times are epoch milliseconds (UTC).
 * [closeTime] is the last millisecond of the candle (openTime + timeframe - 1), as Binance reports it.
 */
data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
) {
    init {
        require(high >= low) { "high < low at $openTime" }
        require(volume >= 0.0) { "negative volume at $openTime" }
    }

    /** A candle is closed once the current time is past its last millisecond. */
    fun isClosedAt(nowMs: Long): Boolean = nowMs > closeTime
}

/** Timeframes the app uses, with each exchange's interval code. */
enum class Timeframe(val label: String, val millis: Long, val binanceCode: String, val bybitCode: String) {
    M5("5m", 5L * 60_000, "5m", "5"),
    M15("15m", 15L * 60_000, "15m", "15"),
    H1("1h", 60L * 60_000, "1h", "60"),
    H4("4h", 4L * 60 * 60_000, "4h", "240"),
    D1("1d", 24L * 60 * 60_000, "1d", "D");

    /** Open time of the candle that contains [timeMs]. Exchanges align all of these to UTC. */
    fun floor(timeMs: Long): Long = timeMs - Math.floorMod(timeMs, millis)

    companion object {
        fun parse(text: String): Timeframe =
            entries.firstOrNull { it.label.equals(text, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown timeframe '$text'. Use one of ${entries.joinToString { it.label }}")
    }
}
