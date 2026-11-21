package com.signalbot.engine.indicators

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Technical indicators written to match TradingView Pine Script v5/v6 built-ins exactly
 * (ta.sma, ta.ema, ta.rma, ta.tr, ta.atr, ta.rsi, ta.macd, ta.dmi, ta.obv, ta.bb, ta.stdev).
 *
 * Conventions:
 *  - Inputs and outputs are oldest-first arrays of equal length.
 *  - Double.NaN means "no value yet" (Pine's `na`).
 *  - EMA and RMA are seeded with the SMA of their first `length` values, as Pine does.
 *    TradingView starts that seed at the first bar of the chart's full history, so very
 *    long averages (EMA 200) only match TradingView after a warm-up of several hundred bars.
 */
object Indicators {

    // ---------------------------------------------------------------- moving averages

    /** ta.sma: average of the last [length] values; NaN if any value in the window is NaN. */
    fun sma(src: DoubleArray, length: Int): DoubleArray {
        require(length >= 1) { "length must be >= 1" }
        val out = DoubleArray(src.size) { Double.NaN }
        var sum = 0.0
        var nanCount = 0
        for (i in src.indices) {
            val v = src[i]
            if (v.isNaN()) nanCount++ else sum += v
            if (i >= length) {
                val old = src[i - length]
                if (old.isNaN()) nanCount-- else sum -= old
            }
            if (i >= length - 1 && nanCount == 0) {
                // A running sum slowly collects rounding error; re-add the window from scratch
                // every 512 bars so long backtests stay exact to ~1e-12.
                if (i % 512 == 0) sum = windowSum(src, i, length)
                out[i] = sum / length
            }
        }
        return out
    }

    private fun windowSum(src: DoubleArray, end: Int, length: Int): Double {
        var s = 0.0
        for (j in end - length + 1..end) s += src[j]
        return s
    }

    /** ta.ema: alpha = 2 / (length + 1), seeded with ta.sma. */
    fun ema(src: DoubleArray, length: Int): DoubleArray = smoothed(src, length, 2.0 / (length + 1))

    /** ta.rma (Wilder's smoothing): alpha = 1 / length, seeded with ta.sma. Used by RSI, ATR and ADX. */
    fun rma(src: DoubleArray, length: Int): DoubleArray = smoothed(src, length, 1.0 / length)

    /**
     * Pine's recursive average:
     *   sum := na(sum[1]) ? ta.sma(src, length) : alpha * src + (1 - alpha) * sum[1]
     */
    private fun smoothed(src: DoubleArray, length: Int, alpha: Double): DoubleArray {
        require(length >= 1) { "length must be >= 1" }
        val seed = sma(src, length)
        val out = DoubleArray(src.size) { Double.NaN }
        for (i in src.indices) {
            val prev = if (i > 0) out[i - 1] else Double.NaN
            out[i] = if (prev.isNaN()) seed[i] else alpha * src[i] + (1 - alpha) * prev
        }
        return out
    }

    // ---------------------------------------------------------------- basic helpers

    /** ta.change: src - src[length]. */
    fun change(src: DoubleArray, length: Int = 1): DoubleArray =
        DoubleArray(src.size) { i -> if (i >= length) src[i] - src[i - length] else Double.NaN }

    /** ta.highest over [length] bars, NaN until the window is full. */
    fun highest(src: DoubleArray, length: Int): DoubleArray = rollingExtreme(src, length, true)

    /** ta.lowest over [length] bars, NaN until the window is full. */
    fun lowest(src: DoubleArray, length: Int): DoubleArray = rollingExtreme(src, length, false)

    private fun rollingExtreme(src: DoubleArray, length: Int, max: Boolean): DoubleArray {
        require(length >= 1)
        val out = DoubleArray(src.size) { Double.NaN }
        for (i in length - 1 until src.size) {
            var best = src[i]
            for (j in i - length + 1 until i) {
                best = if (max) max(best, src[j]) else minOf(best, src[j])
            }
            out[i] = best
        }
        return out
    }

    /** Pine fixnan: replace NaN with the last non-NaN value seen. */
    fun fixnan(src: DoubleArray): DoubleArray {
        val out = src.copyOf()
        var last = Double.NaN
        for (i in out.indices) {
            if (out[i].isNaN()) out[i] = last else last = out[i]
        }
        return out
    }

    /** ta.stdev with biased = true (population standard deviation), Pine's default. */
    fun stdev(src: DoubleArray, length: Int): DoubleArray {
        require(length >= 1)
        val mean = sma(src, length)
        val out = DoubleArray(src.size) { Double.NaN }
        for (i in src.indices) {
            val m = mean[i]
            if (m.isNaN()) continue
            var ss = 0.0
            for (j in i - length + 1..i) {
                val d = src[j] - m
                ss += d * d
            }
            out[i] = sqrt(ss / length)
        }
        return out
    }

    /** Median of the last [length] values; NaN if the window holds any NaN. Used for the volatility regime. */
    fun rollingMedian(src: DoubleArray, length: Int): DoubleArray {
        require(length >= 1)
        val out = DoubleArray(src.size) { Double.NaN }
        val window = DoubleArray(length)
        outer@ for (i in length - 1 until src.size) {
            for (k in 0 until length) {
                val v = src[i - length + 1 + k]
                if (v.isNaN()) continue@outer
                window[k] = v
            }
            val sorted = window.sortedArray()
            out[i] = if (length % 2 == 1) sorted[length / 2] else (sorted[length / 2 - 1] + sorted[length / 2]) / 2
        }
        return out
    }

    // ---------------------------------------------------------------- volatility

    /**
     * ta.tr(handle_na). The first bar has no previous close: with [handleNa] it is high - low,
     * otherwise NaN. The plain `ta.tr` variable (used inside ta.dmi) is handleNa = false;
     * ta.atr uses handleNa = true.
     */
    fun trueRange(high: DoubleArray, low: DoubleArray, close: DoubleArray, handleNa: Boolean): DoubleArray =
        DoubleArray(high.size) { i ->
            if (i == 0) {
                if (handleNa) high[0] - low[0] else Double.NaN
            } else {
                val pc = close[i - 1]
                max(high[i] - low[i], max(abs(high[i] - pc), abs(low[i] - pc)))
            }
        }

    /** ta.atr: rma(ta.tr(true), length). */
    fun atr(high: DoubleArray, low: DoubleArray, close: DoubleArray, length: Int = 14): DoubleArray =
        rma(trueRange(high, low, close, handleNa = true), length)

    data class Bands(val basis: DoubleArray, val upper: DoubleArray, val lower: DoubleArray) {
        /** Bollinger BandWidth as a fraction of the basis: (upper - lower) / basis. */
        val width: DoubleArray
            get() = DoubleArray(basis.size) { i -> (upper[i] - lower[i]) / basis[i] }
    }

    /** ta.bb: basis = sma, bands = basis +/- mult * population stdev. */
    fun bollinger(src: DoubleArray, length: Int = 20, mult: Double = 2.0): Bands {
        val basis = sma(src, length)
        val dev = stdev(src, length)
        return Bands(
            basis = basis,
            upper = DoubleArray(src.size) { basis[it] + mult * dev[it] },
            lower = DoubleArray(src.size) { basis[it] - mult * dev[it] },
        )
    }

    // ---------------------------------------------------------------- momentum

    /**
     * ta.rsi:
     *   u = max(src - src[1], 0), d = max(src[1] - src, 0)
     *   rsi = d == 0 ? 100 : u == 0 ? 0 : 100 - 100 / (1 + rma(u) / rma(d))
     */
    fun rsi(src: DoubleArray, length: Int = 14): DoubleArray {
        val ch = change(src)
        val up = rma(DoubleArray(src.size) { if (ch[it].isNaN()) Double.NaN else max(ch[it], 0.0) }, length)
        val down = rma(DoubleArray(src.size) { if (ch[it].isNaN()) Double.NaN else max(-ch[it], 0.0) }, length)
        return DoubleArray(src.size) { i ->
            val u = up[i]
            val d = down[i]
            when {
                u.isNaN() || d.isNaN() -> Double.NaN
                d == 0.0 -> 100.0
                u == 0.0 -> 0.0
                else -> 100.0 - 100.0 / (1.0 + u / d)
            }
        }
    }

    data class Macd(val line: DoubleArray, val signal: DoubleArray, val histogram: DoubleArray)

    /** ta.macd: line = ema(fast) - ema(slow), signal = ema(line, signalLength), histogram = line - signal. */
    fun macd(src: DoubleArray, fast: Int = 12, slow: Int = 26, signalLength: Int = 9): Macd {
        val f = ema(src, fast)
        val s = ema(src, slow)
        val line = DoubleArray(src.size) { f[it] - s[it] }
        val signal = ema(line, signalLength)
        return Macd(line, signal, DoubleArray(src.size) { line[it] - signal[it] })
    }

    // ---------------------------------------------------------------- trend strength

    data class Dmi(val plus: DoubleArray, val minus: DoubleArray, val adx: DoubleArray)

    /**
     * ta.dmi(diLength, adxSmoothing), following Pine's reference implementation:
     *   up = ta.change(high), down = -ta.change(low)
     *   plusDM  = na(up)   ? na : (up > down and up > 0 ? up : 0)
     *   minusDM = na(down) ? na : (down > up and down > 0 ? down : 0)
     *   trur  = ta.rma(ta.tr, diLength)
     *   plus  = fixnan(100 * ta.rma(plusDM, diLength) / trur)
     *   minus = fixnan(100 * ta.rma(minusDM, diLength) / trur)
     *   adx   = 100 * ta.rma(math.abs(plus - minus) / (sum == 0 ? 1 : sum), adxSmoothing)
     */
    fun dmi(
        high: DoubleArray,
        low: DoubleArray,
        close: DoubleArray,
        diLength: Int = 14,
        adxSmoothing: Int = 14,
    ): Dmi {
        val n = high.size
        val up = change(high)
        val down = DoubleArray(n) { i -> if (i == 0) Double.NaN else -(low[i] - low[i - 1]) }
        val plusDM = DoubleArray(n) { i ->
            val u = up[i]; val d = down[i]
            if (u.isNaN()) Double.NaN else if (u > d && u > 0) u else 0.0
        }
        val minusDM = DoubleArray(n) { i ->
            val u = up[i]; val d = down[i]
            if (d.isNaN()) Double.NaN else if (d > u && d > 0) d else 0.0
        }
        val trur = rma(trueRange(high, low, close, handleNa = false), diLength)
        val pS = rma(plusDM, diLength)
        val mS = rma(minusDM, diLength)
        val plus = fixnan(DoubleArray(n) { 100 * pS[it] / trur[it] })
        val minus = fixnan(DoubleArray(n) { 100 * mS[it] / trur[it] })
        val dx = DoubleArray(n) { i ->
            val sum = plus[i] + minus[i]
            abs(plus[i] - minus[i]) / (if (sum == 0.0) 1.0 else sum)
        }
        val adxRaw = rma(dx, adxSmoothing)
        return Dmi(plus, minus, DoubleArray(n) { 100 * adxRaw[it] })
    }

    // ---------------------------------------------------------------- volume

    /** ta.obv: cumulative sign(change(close)) * volume. Starts at 0 on the first bar. */
    fun obv(close: DoubleArray, volume: DoubleArray): DoubleArray {
        val out = DoubleArray(close.size)
        for (i in 1 until close.size) {
            out[i] = out[i - 1] + sign(close[i] - close[i - 1]) * volume[i]
        }
        return out
    }
}

/** Index of the newest non-NaN value, or -1. */
fun DoubleArray.lastValidIndex(): Int {
    for (i in indices.reversed()) if (!this[i].isNaN()) return i
    return -1
}
