package com.signalbot.engine.strategy

import com.signalbot.engine.model.Asset
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic fake markets for tests: a drifting trend with 4-6 day swings (so pullbacks,
 * retracements, breakouts and swing points exist), random noise, occasional shocks, and volume that
 * rises on moves in the trend's direction.
 */
object Synthetic {
    // 2025-01-06 00:00 UTC, a Monday, aligned to every timeframe.
    const val START = 1_736_121_600_000L

    fun hourly(
        hours: Int,
        driftPerHour: Double,
        seed: Int,
        startPrice: Double = 30_000.0,
        noise: Double = 0.003,
        waveAmp: Double = 0.035,
    ): List<Candle> {
        val rnd = Random(seed)
        val out = ArrayList<Candle>(hours)
        var logTrend = 0.0
        var prevClose = startPrice
        var period = 110.0
        var phase = 0.0
        for (h in 0 until hours) {
            if (h % 400 == 0) period = 90.0 + rnd.nextDouble() * 60.0 // swing length drifts: 4-6 days
            phase += 2 * PI / period
            logTrend += driftPerHour + noise * 0.35 * rnd.nextGaussian()
            if (rnd.nextDouble() < 0.004) logTrend += (if (rnd.nextBoolean()) 1 else -1) * noise * 6 // occasional shock
            val wave = waveAmp * sin(phase)
            val close = startPrice * exp(logTrend + wave + noise * 0.5 * rnd.nextGaussian())
            val open = prevClose
            val body = kotlin.math.abs(close - open)
            val high = maxOf(open, close) + body * 0.3 + close * noise * 0.4 * kotlin.math.abs(rnd.nextGaussian())
            val low = minOf(open, close) - body * 0.3 - close * noise * 0.4 * kotlin.math.abs(rnd.nextGaussian())
            val withTrend = (close > open) == (driftPerHour >= 0)
            val volume = (100.0 + 40.0 * rnd.nextGaussian().coerceIn(-2.0, 2.0)) * (if (withTrend) 1.35 else 0.85) *
                (1.0 + 8.0 * kotlin.math.abs(close - open) / close / noise / 10)
            val t = START + h * Timeframe.H1.millis
            out += Candle(t, open, high, low, close, volume.coerceAtLeast(1.0), t + Timeframe.H1.millis - 1)
            prevClose = close
        }
        return out
    }

    fun Random.nextGaussian(): Double {
        // Box-Muller
        var u = 0.0
        while (u == 0.0) u = nextDouble()
        val v = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * v)
    }

    fun marketData(asset: Asset, h1: List<Candle>, btcH4: Series? = null): MarketData =
        MarketData(
            asset,
            Series(h1),
            Series(Resample.from1h(h1, Timeframe.H4)),
            Series(Resample.from1h(h1, Timeframe.D1)),
            btcH4,
            "synthetic",
        )

    /** Everything that was known at the close of 1H bar [i]: the inputs a live scan would have had. */
    fun truncatedAt(data: MarketData, i: Int): MarketData {
        val t = data.h1.openTime[i] + Timeframe.H1.millis
        fun Series.upTo(tf: Timeframe) = Series(candles.filter { it.openTime + tf.millis <= t })
        return MarketData(data.asset, Series(data.h1.candles.subList(0, i + 1)), data.h4.upTo(Timeframe.H4),
            data.d1.upTo(Timeframe.D1), data.btcH4?.upTo(Timeframe.H4), data.source)
    }
}
