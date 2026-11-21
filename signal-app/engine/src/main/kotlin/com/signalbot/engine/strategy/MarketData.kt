package com.signalbot.engine.strategy

import com.signalbot.engine.data.CandleLoader
import com.signalbot.engine.data.HttpGet
import com.signalbot.engine.data.MarketDataException
import com.signalbot.engine.data.RetryPolicy
import com.signalbot.engine.data.UrlConnectionHttp
import com.signalbot.engine.model.Asset
import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe

/** Everything the engine needs to judge one asset: its 1H, 4H and 1D closed candles, plus BTC's 4H for altcoins. */
class MarketData(
    val asset: Asset,
    val h1: Series,
    val h4: Series,
    val d1: Series,
    val btcH4: Series? = null,
    /** Which route the candles came from, e.g. "BINANCE spot BTCUSDT". */
    val source: String = "",
) {
    /**
     * Price-flipped copy (every price p becomes -p). A downtrend becomes an uptrend, so the long rules
     * run unchanged on it to find shorts, and the results are flipped back. One set of rules, two directions.
     */
    fun mirrored(): MarketData = MarketData(asset, h1.mirrored(), h4.mirrored(), d1.mirrored(), btcH4?.mirrored(), source)
}

fun Series.mirrored(): Series = Series(candles.map { Candle(it.openTime, -it.open, -it.low, -it.high, -it.close, it.volume, it.closeTime) })

/**
 * Index of the newest candle of timeframe [tf] that had CLOSED by [timeMs], or -1.
 * This is how a 1H decision sees only finished 4H and 1D candles.
 */
fun Series.closedIndexAt(tf: Timeframe, timeMs: Long): Int {
    var lo = 0
    var hi = size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (openTime[mid] + tf.millis <= timeMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/** Builds 4H or 1D candles from 1H candles (UTC-aligned, like the exchanges). Incomplete periods are dropped. */
object Resample {
    fun from1h(h1: List<Candle>, target: Timeframe): List<Candle> {
        require(target.millis > Timeframe.H1.millis && target.millis % Timeframe.H1.millis == 0L)
        val out = ArrayList<Candle>()
        var i = 0
        while (i < h1.size) {
            val start = target.floor(h1[i].openTime)
            val end = start + target.millis - 1
            var j = i
            var high = Double.NEGATIVE_INFINITY
            var low = Double.POSITIVE_INFINITY
            var vol = 0.0
            while (j < h1.size && h1[j].openTime <= end) {
                high = maxOf(high, h1[j].high); low = minOf(low, h1[j].low); vol += h1[j].volume
                j++
            }
            // Complete = we hold the period's first AND last hour (exchanges align periods to UTC).
            if (h1[i].openTime == start && h1[j - 1].closeTime == end) {
                out += Candle(start, h1[i].open, high, low, h1[j - 1].close, vol, end)
            }
            i = j
        }
        return out
    }
}

/**
 * Loads an asset's three timeframes. All three come from the SAME route: if the main exchange fails
 * part-way, everything is reloaded from the backup, so prices from two exchanges are never mixed.
 */
class AssetDataLoader(
    private val http: HttpGet = UrlConnectionHttp(),
    private val retry: RetryPolicy = RetryPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Counts(val h1: Int = 600, val h4: Int = 800, val d1: Int = 450)

    fun load(asset: Asset, counts: Counts = Counts(), btcH4: Series? = null): MarketData {
        val errors = ArrayList<String>()
        for (route in asset.routes) {
            try {
                val loader = CandleLoader(route.source(http, retry), clock)
                val h1 = loader.closed(route.symbol, Timeframe.H1, counts.h1)
                val h4 = loader.closed(route.symbol, Timeframe.H4, counts.h4)
                val d1 = loader.closed(route.symbol, Timeframe.D1, counts.d1)
                return MarketData(asset, Series(h1), Series(h4), Series(d1), btcH4, route.toString())
            } catch (e: MarketDataException) {
                errors += "$route: ${e.message}"
            }
        }
        throw MarketDataException("Could not load ${asset.id}: ${errors.joinToString("; ")}", retryable = true)
    }

    /** BTC 4H candles, loaded once per scan and shared by every altcoin. */
    fun loadBtc4h(count: Int = 400): Series {
        val errors = ArrayList<String>()
        for (route in Assets.BTC.routes) {
            try {
                return Series(CandleLoader(route.source(http, retry), clock).closed(route.symbol, Timeframe.H4, count))
            } catch (e: MarketDataException) {
                errors += "$route: ${e.message}"
            }
        }
        throw MarketDataException("Could not load BTC 4H: ${errors.joinToString("; ")}", retryable = true)
    }
}
