package com.signalbot.engine.indicators

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Small cases worked out by hand, one per formula. */
class IndicatorsTest {
    private val nan = Double.NaN
    private fun d(vararg v: Double) = v

    private fun assertSeries(expected: DoubleArray, actual: DoubleArray, tol: Double = 1e-9) {
        assertEquals(expected.size, actual.size, "length")
        for (i in expected.indices) {
            if (expected[i].isNaN()) assertTrue(actual[i].isNaN(), "index $i should be NaN but was ${actual[i]}")
            else assertEquals(expected[i], actual[i], tol, "index $i")
        }
    }

    @Test fun smaWaitsForAFullWindow() =
        assertSeries(d(nan, nan, 2.0, 3.0, 4.0), Indicators.sma(d(1.0, 2.0, 3.0, 4.0, 5.0), 3))

    @Test fun smaIsNaNWhileAWindowHoldsNaN() =
        assertSeries(d(nan, nan, nan, nan, 4.0), Indicators.sma(d(1.0, nan, 3.0, 4.0, 5.0), 3))

    @Test fun smaStaysExactOnLongSeries() {
        val src = DoubleArray(5_000) { 30_000.0 + (it % 97) * 0.37 }
        val fast = Indicators.sma(src, 200)
        for (i in listOf(199, 1_000, 2_049, 4_999)) {
            val exact = (i - 199..i).sumOf { src[it] } / 200
            assertEquals(exact, fast[i], 1e-9, "index $i")
        }
    }

    @Test fun emaIsSeededWithSmaThenRecursive() {
        // length 3 -> alpha 0.5. Seed at index 2 = (1+2+3)/3 = 2, then 0.5*x + 0.5*prev.
        // On a straight line that settles at exactly x - 1.
        val out = Indicators.ema(DoubleArray(10) { it + 1.0 }, 3)
        assertSeries(d(nan, nan, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0), out)
    }

    @Test fun rmaUsesWildersAlpha() {
        // length 2 -> alpha 0.5. Seed (2+4)/2 = 3; then 0.5*6 + 0.5*3 = 4.5; 0.5*8 + 0.5*4.5 = 6.25.
        assertSeries(d(nan, 3.0, 4.5, 6.25), Indicators.rma(d(2.0, 4.0, 6.0, 8.0), 2))
    }

    @Test fun rsiHandWorked() {
        // closes 10, 11, 10, 12 -> gains 1,0,2 / losses 0,1,0
        // rma(2): up = 0.5, 1.25 ; down = 0.5, 0.25 -> RSI 50, then 100 - 100/(1+5) = 83.33
        assertSeries(d(nan, nan, 50.0, 100.0 - 100.0 / 6.0), Indicators.rsi(d(10.0, 11.0, 10.0, 12.0), 2))
    }

    @Test fun rsiIsHundredWithNoLossesAndZeroWithNoGains() {
        val up = Indicators.rsi(DoubleArray(30) { 100.0 + it }, 14)
        val down = Indicators.rsi(DoubleArray(30) { 100.0 - it }, 14)
        assertEquals(100.0, up.last(), 0.0)
        assertEquals(0.0, down.last(), 0.0)
    }

    @Test fun trueRangeCoversGapsAndFirstBar() {
        val high = d(12.0, 15.0)
        val low = d(9.0, 12.0)
        val close = d(10.0, 14.0)
        // bar 1: max(15-12, |15-10|, |12-10|) = 5
        assertSeries(d(3.0, 5.0), Indicators.trueRange(high, low, close, handleNa = true))
        assertSeries(d(nan, 5.0), Indicators.trueRange(high, low, close, handleNa = false))
    }

    @Test fun atrOfLengthOneIsTrueRange() {
        val high = d(12.0, 15.0, 14.0)
        val low = d(9.0, 12.0, 13.0)
        val close = d(10.0, 14.0, 13.5)
        assertSeries(Indicators.trueRange(high, low, close, true), Indicators.atr(high, low, close, 1))
    }

    @Test fun macdHistogramIsLineMinusSignal() {
        val src = DoubleArray(120) { 100 + 10 * kotlin.math.sin(it / 7.0) }
        val m = Indicators.macd(src)
        assertTrue(m.line[24].isNaN() && !m.line[25].isNaN(), "line starts when EMA 26 does")
        assertTrue(m.signal[32].isNaN() && !m.signal[33].isNaN(), "signal needs 9 MACD values")
        for (i in 33 until src.size) assertEquals(m.line[i] - m.signal[i], m.histogram[i], 1e-12)
    }

    @Test fun adxReachesHundredInAPerfectUptrend() {
        // Every bar makes a higher high and higher low: +DM always, -DM never.
        val n = 200
        val high = DoubleArray(n) { 101.0 + it }
        val low = DoubleArray(n) { 99.0 + it }
        val close = DoubleArray(n) { 100.5 + it }
        val dmi = Indicators.dmi(high, low, close, 14, 14)
        assertEquals(0.0, dmi.minus.last(), 1e-12)
        assertTrue(dmi.plus.last() > 0)
        assertEquals(100.0, dmi.adx.last(), 1e-9)
        assertTrue(dmi.adx[26].isNaN() && !dmi.adx[27].isNaN(), "ADX needs 14 + 14 - 1 bars after the first")
    }

    @Test fun obvAddsOnUpClosesAndSubtractsOnDown() =
        assertSeries(d(0.0, 6.0, 6.0, -2.0), Indicators.obv(d(1.0, 2.0, 2.0, 1.0), d(5.0, 6.0, 7.0, 8.0)))

    @Test fun stdevIsPopulationStdev() {
        // Classic example: mean 5, population variance 4.
        val out = Indicators.stdev(d(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0), 8)
        assertEquals(2.0, out.last(), 1e-12)
    }

    @Test fun bollingerWidth() {
        val b = Indicators.bollinger(d(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0), 8, 2.0)
        assertEquals(9.0, b.upper.last(), 1e-12)
        assertEquals(1.0, b.lower.last(), 1e-12)
        assertEquals(8.0 / 5.0, b.width.last(), 1e-12)
    }

    @Test fun highestLowestAndMedian() {
        val src = d(3.0, 1.0, 4.0, 1.0, 5.0, 9.0, 2.0)
        assertSeries(d(nan, nan, 4.0, 4.0, 5.0, 9.0, 9.0), Indicators.highest(src, 3))
        assertSeries(d(nan, nan, 1.0, 1.0, 1.0, 1.0, 2.0), Indicators.lowest(src, 3))
        assertSeries(d(nan, nan, 3.0, 1.0, 4.0, 5.0, 5.0), Indicators.rollingMedian(src, 3))
        assertSeries(d(nan, 2.0, 2.5, 2.5, 3.0, 7.0, 5.5), Indicators.rollingMedian(src, 2))
    }

    @Test fun fixnanCarriesLastValue() =
        assertSeries(d(nan, 1.0, 1.0, 3.0, 3.0), Indicators.fixnan(d(nan, 1.0, nan, 3.0, nan)))

    @Test fun lastValidIndexSkipsTrailingNaN() {
        assertEquals(1, d(1.0, 2.0, nan).lastValidIndex())
        assertEquals(-1, d(nan).lastValidIndex())
    }
}
