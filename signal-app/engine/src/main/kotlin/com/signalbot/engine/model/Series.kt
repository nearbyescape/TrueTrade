package com.signalbot.engine.model

/**
 * Column view of a candle list (oldest first), the shape every indicator works on.
 * Indicators return a DoubleArray of the same length, with Double.NaN where a value
 * is not yet available (TradingView's `na`).
 */
class Series(val candles: List<Candle>) {
    val size: Int = candles.size
    val openTime = LongArray(size) { candles[it].openTime }
    val open = DoubleArray(size) { candles[it].open }
    val high = DoubleArray(size) { candles[it].high }
    val low = DoubleArray(size) { candles[it].low }
    val close = DoubleArray(size) { candles[it].close }
    val volume = DoubleArray(size) { candles[it].volume }

    init {
        for (i in 1 until size) {
            require(openTime[i] > openTime[i - 1]) { "Candles must be sorted oldest first with no duplicates (index $i)" }
        }
    }

    val lastIndex: Int get() = size - 1
}
