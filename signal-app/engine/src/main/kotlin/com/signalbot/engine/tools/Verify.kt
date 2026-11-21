package com.signalbot.engine.tools

import com.signalbot.engine.data.CandleIntegrity
import com.signalbot.engine.data.CandleLoader
import com.signalbot.engine.data.MarketDataException
import com.signalbot.engine.indicators.Indicators
import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Phase 1 gate: print indicator values for the last few closed candles, to compare by eye with
 * TradingView on the same futures chart (e.g. BINANCE:BTCUSDT.P, BINANCE:XAUUSDT.P).
 *
 *   ./gradlew :engine:verify --args="BTC 1h"
 *   ./gradlew :engine:verify --args="GOLD 4h"
 */
fun main(args: Array<String>) {
    val asset = Assets.byId(args.getOrNull(0) ?: "BTC") ?: run {
        System.err.println("Unknown asset. Use one of: ${Assets.DEFAULT.joinToString { it.id }}"); exitProcess(1)
    }
    val tf = Timeframe.parse(args.getOrNull(1) ?: "1h")
    val count = args.getOrNull(2)?.toInt() ?: 1500

    var loaded: Pair<String, Series>? = null
    for (route in asset.routes) {
        try {
            loaded = route.toString() to Series(CandleLoader(route.source()).closed(route.symbol, tf, count))
            break
        } catch (e: MarketDataException) {
            System.err.println("$route failed: ${e.message}")
        }
    }
    val (source, s) = loaded ?: exitProcess(1)
    println("${asset.displayName} ${tf.label}: ${s.size} closed candles from $source")
    val gaps = CandleIntegrity.findGaps(s.candles, tf)
    if (gaps.isNotEmpty()) println("Note: ${gaps.size} gap(s) in exchange history, e.g. ${gaps.first()}")

    val ema50 = Indicators.ema(s.close, 50)
    val ema200 = Indicators.ema(s.close, 200)
    val rsi = Indicators.rsi(s.close, 14)
    val atr = Indicators.atr(s.high, s.low, s.close, 14)
    val dmi = Indicators.dmi(s.high, s.low, s.close, 14, 14)
    val macd = Indicators.macd(s.close)

    val ist = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.US).withZone(ZoneId.of("Asia/Kolkata"))
    fun f(v: Double, d: Int = 2) = if (v.isNaN()) "-" else String.format(Locale.US, "%.${d}f", v)
    val row = "%-13s %12s %12s %12s %7s %10s %7s %7s %7s %10s %10s %10s"
    println()
    println(String.format(Locale.US, row, "Open (IST)", "Close", "EMA50", "EMA200", "RSI14", "ATR14", "ADX", "+DI", "-DI", "MACD", "Signal", "Hist"))
    for (i in maxOf(0, s.lastIndex - 4)..s.lastIndex) {
        println(String.format(Locale.US, row,
            ist.format(Instant.ofEpochMilli(s.openTime[i])), f(s.close[i]), f(ema50[i]), f(ema200[i]), f(rsi[i]),
            f(atr[i]), f(dmi.adx[i]), f(dmi.plus[i]), f(dmi.minus[i]), f(macd.line[i]), f(macd.signal[i]), f(macd.histogram[i])))
    }
}
