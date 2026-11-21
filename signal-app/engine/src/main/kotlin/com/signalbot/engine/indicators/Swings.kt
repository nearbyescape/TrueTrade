package com.signalbot.engine.indicators

/**
 * Swing highs and lows (pivots), used for stops, targets and break-of-structure triggers.
 *
 * A swing high at bar i needs [left] bars before it that are strictly lower and [right]
 * bars after it that are lower or equal. The swing is only KNOWN once those right-hand
 * bars have closed, at bar [SwingPoint.confirmedAt] = i + right. Strategy code must only
 * use swings whose confirmedAt <= the current bar; anything else is looking into the future,
 * which makes backtests look far better than live trading.
 */
data class SwingPoint(
    val index: Int,
    val price: Double,
    val kind: Kind,
    val confirmedAt: Int,
) {
    enum class Kind { HIGH, LOW }
}

object Swings {

    fun highs(high: DoubleArray, left: Int = 3, right: Int = 3): List<SwingPoint> =
        find(high, left, right, SwingPoint.Kind.HIGH)

    fun lows(low: DoubleArray, left: Int = 3, right: Int = 3): List<SwingPoint> =
        find(low, left, right, SwingPoint.Kind.LOW)

    private fun find(src: DoubleArray, left: Int, right: Int, kind: SwingPoint.Kind): List<SwingPoint> {
        require(left >= 1 && right >= 1)
        val isHigh = kind == SwingPoint.Kind.HIGH
        // "a beats b": for highs a > b, for lows a < b.
        fun beats(a: Double, b: Double) = if (isHigh) a > b else a < b
        fun beatsOrTies(a: Double, b: Double) = if (isHigh) a >= b else a <= b

        val out = ArrayList<SwingPoint>()
        for (i in left until src.size - right) {
            val v = src[i]
            if (v.isNaN()) continue
            var ok = true
            for (j in i - left until i) if (!beats(v, src[j])) { ok = false; break }
            if (ok) for (j in i + 1..i + right) if (!beatsOrTies(v, src[j])) { ok = false; break }
            if (ok) out += SwingPoint(i, v, kind, confirmedAt = i + right)
        }
        return out
    }

    /** Newest swing already confirmed at bar [atIndex] (no look-ahead), or null. */
    fun lastConfirmed(points: List<SwingPoint>, atIndex: Int): SwingPoint? =
        lastConfirmedPosition(points, atIndex).let { if (it < 0) null else points[it] }

    /**
     * Position in [points] of the newest swing confirmed by [atIndex], or -1. Binary search, so
     * backtests over thousands of bars stay fast. Points from [highs]/[lows] are sorted by index,
     * and confirmedAt = index + right rises with it.
     */
    fun lastConfirmedPosition(points: List<SwingPoint>, atIndex: Int): Int {
        var lo = 0
        var hi = points.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid].confirmedAt <= atIndex) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** All swings confirmed at bar [atIndex], newest first. */
    fun confirmedBefore(points: List<SwingPoint>, atIndex: Int): List<SwingPoint> =
        points.filter { it.confirmedAt <= atIndex }.asReversed()
}
