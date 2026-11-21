package com.signalbot.engine.strategy

import com.signalbot.engine.indicators.Indicators
import com.signalbot.engine.indicators.SwingPoint
import com.signalbot.engine.indicators.Swings
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe

/**
 * One timeframe's candles with indicators computed once over the whole series. A backtest then
 * judges thousands of bars by reading arrays, instead of recomputing indicators bar by bar.
 * Every value at index i uses only candles 0..i, so reading index i never sees the future.
 */
class TfView(val series: Series, val tf: Timeframe, private val cfg: StrategyConfig) {
    val close get() = series.close
    val high get() = series.high
    val low get() = series.low
    val open get() = series.open
    val volume get() = series.volume

    val emaFast by lazy { Indicators.ema(series.close, if (tf == Timeframe.D1) cfg.dailyEmaFast else cfg.emaFast) }
    val emaSlow by lazy { Indicators.ema(series.close, if (tf == Timeframe.D1) cfg.dailyEmaSlow else cfg.emaSlow) }
    val atr by lazy { Indicators.atr(series.high, series.low, series.close, cfg.atrLength) }
    val atrPct by lazy { DoubleArray(series.size) { atr[it] / kotlin.math.abs(series.close[it]) } }
    val atrPctMedian by lazy { Indicators.rollingMedian(atrPct, cfg.regimeMedianBars) }
    val dmi by lazy { Indicators.dmi(series.high, series.low, series.close, cfg.adxLength, cfg.adxLength) }
    val rsi by lazy { Indicators.rsi(series.close, cfg.rsiLength) }
    val macdHist by lazy { Indicators.macd(series.close).histogram }
    val volumeSma by lazy { Indicators.sma(series.volume, cfg.volumeSma) }
    val obv by lazy { Indicators.obv(series.close, series.volume) }

    private val swingCache = HashMap<Triple<Boolean, Int, Int>, List<SwingPoint>>()

    fun swingHighs(left: Int, right: Int): List<SwingPoint> =
        swingCache.getOrPut(Triple(true, left, right)) { Swings.highs(series.high, left, right) }

    fun swingLows(left: Int, right: Int): List<SwingPoint> =
        swingCache.getOrPut(Triple(false, left, right)) { Swings.lows(series.low, left, right) }

    /** Swing-high price by bar index (NaN where there is none), for fast scans of overhead resistance. */
    fun swingHighPriceByIndex(left: Int, right: Int): DoubleArray {
        val out = DoubleArray(series.size) { Double.NaN }
        for (s in swingHighs(left, right)) out[s.index] = s.price
        return out
    }
}

/** All timeframes of one asset, prepared for one direction (normal for longs, mirrored for shorts). */
class PreparedSide(val data: MarketData, cfg: StrategyConfig) {
    val h1 = TfView(data.h1, Timeframe.H1, cfg)
    val h4 = TfView(data.h4, Timeframe.H4, cfg)
    val d1 = TfView(data.d1, Timeframe.D1, cfg)
    val btcH4: TfView? = data.btcH4?.let { TfView(it, Timeframe.H4, cfg) }

    val h4ResistanceByIndex by lazy { h4.swingHighPriceByIndex(cfg.swing4hLeft, cfg.swing4hRight) }
    val d1ResistanceByIndex by lazy { d1.swingHighPriceByIndex(cfg.dailySwingLeft, cfg.dailySwingRight) }
}

/** An asset ready for the engine: the long side now, the short side (mirrored) only if asked for. */
class Prepared(val data: MarketData, val cfg: StrategyConfig) {
    val long = PreparedSide(data, cfg)
    val short by lazy { PreparedSide(data.mirrored(), cfg) }
}
