package com.signalbot.engine.indicators

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Cross-check against TA-Lib (independent C library) on 1,500 synthetic BTC-like candles.
 * See tools/make_talib_reference.py. Only the last 500 bars are compared: TA-Lib seeds a few
 * indicators slightly differently on the first bars, and that difference fades to zero.
 */
class TaLibReferenceTest {
    private val rows: List<Map<String, Double>> by lazy {
        val text = javaClass.getResource("/talib_reference.csv")!!.readText()
        val lines = text.lines().filter { it.isNotBlank() }
        val header = lines.first().split(',')
        lines.drop(1).map { line ->
            val cells = line.split(',')
            header.indices.associate { header[it] to (cells[it].toDoubleOrNull() ?: Double.NaN) }
        }
    }

    private fun col(name: String) = DoubleArray(rows.size) { rows[it].getValue(name) }
    private val open by lazy { col("open") }
    private val high by lazy { col("high") }
    private val low by lazy { col("low") }
    private val close by lazy { col("close") }
    private val volume by lazy { col("volume") }

    private fun check(name: String, ours: DoubleArray, relTol: Double = 1e-6, absFloor: Double = 1e-6) {
        val ref = col(name)
        var worst = 0.0
        var worstAt = -1
        for (i in rows.size - 500 until rows.size) {
            val err = abs(ours[i] - ref[i]) / maxOf(abs(ref[i]), absFloor / relTol)
            if (ours[i].isNaN() || err > worst) { worst = if (ours[i].isNaN()) Double.POSITIVE_INFINITY else err; worstAt = i }
        }
        assertTrue(worst <= relTol, "$name differs from TA-Lib: relative error $worst at row $worstAt (ours ${ours.getOrNull(worstAt)}, TA-Lib ${ref.getOrNull(worstAt)})")
    }

    @Test fun ema() {
        check("ema50", Indicators.ema(close, 50))
        check("ema200", Indicators.ema(close, 200))
    }

    @Test fun sma() = check("sma20", Indicators.sma(close, 20))

    @Test fun rsi() = check("rsi14", Indicators.rsi(close, 14))

    @Test fun atr() = check("atr14", Indicators.atr(high, low, close, 14))

    @Test fun dmiAndAdx() {
        val d = Indicators.dmi(high, low, close, 14, 14)
        check("plusdi14", d.plus)
        check("minusdi14", d.minus)
        check("adx14", d.adx)
    }

    @Test fun macd() {
        val m = Indicators.macd(close, 12, 26, 9)
        check("macd", m.line, absFloor = 1e-4)
        check("macdsignal", m.signal, absFloor = 1e-4)
        check("macdhist", m.histogram, absFloor = 1e-4)
    }

    @Test fun bollinger() {
        val b = Indicators.bollinger(close, 20, 2.0)
        check("bbupper", b.upper)
        check("bblower", b.lower)
    }

    @Test fun obvStepsMatch() {
        // TA-Lib starts OBV at the first bar's volume, TradingView at 0: compare the bar-to-bar steps.
        val ours = Indicators.obv(close, volume)
        val ref = col("obv")
        for (i in 1 until rows.size) {
            assertTrue(abs((ours[i] - ours[i - 1]) - (ref[i] - ref[i - 1])) < 1e-6, "OBV step at $i")
        }
        assertTrue(open.size == rows.size)
    }
}
