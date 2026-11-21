package com.signalbot.engine.data

import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Timeframe
import java.util.TreeMap

/**
 * Loads CLOSED candles only. Signals are decided on closed candles, never on the one still
 * forming, so a signal can't appear and then vanish before the bar ends.
 */
class CandleLoader(
    private val source: MarketDataSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The newest [count] closed candles, oldest first. Pages through history when count > 1000. */
    fun closed(symbol: String, timeframe: Timeframe, count: Int): List<Candle> {
        require(count >= 1)
        val now = clock()
        if (count < PAGE) {
            // +1 because the newest row is usually the open candle.
            return source.klines(symbol, timeframe, count + 1)
                .filter { it.isClosedAt(now) }
                .takeLast(count)
        }
        return history(symbol, timeframe, count, now)
    }

    /**
     * Closed candles opened after [lastKnownOpenTime], for catching up after the phone was
     * offline. Returns an empty list when nothing new has closed.
     */
    fun since(symbol: String, timeframe: Timeframe, lastKnownOpenTime: Long): List<Candle> {
        val now = clock()
        val newestClosedOpen = timeframe.floor(now) - timeframe.millis
        if (newestClosedOpen <= lastKnownOpenTime) return emptyList()
        val missing = ((newestClosedOpen - lastKnownOpenTime) / timeframe.millis).toInt()
        return closed(symbol, timeframe, missing + 2).filter { it.openTime > lastKnownOpenTime }
    }

    private fun history(symbol: String, timeframe: Timeframe, count: Int, now: Long): List<Candle> {
        val byOpenTime = TreeMap<Long, Candle>()
        var end: Long? = null
        var closedCount = 0
        var pages = 0
        while (closedCount < count && pages < MAX_PAGES) {
            val batch = source.klines(symbol, timeframe, PAGE, end)
            pages++
            if (batch.isEmpty()) break
            for (c in batch) {
                if (c.isClosedAt(now) && byOpenTime.put(c.openTime, c) == null) closedCount++
            }
            val oldest = batch.first().openTime
            if (batch.size < PAGE || (end != null && oldest >= end)) break // reached the coin's first candle
            end = oldest - 1
        }
        return byOpenTime.values.toList().takeLast(count)
    }

    companion object {
        const val PAGE = 1000
        /** Safety stop: 50 pages = 50,000 candles (over 5 years of 1H). */
        const val MAX_PAGES = 50
    }
}

/** A hole in the candle history: [missing] candles absent after the candle opened at [afterOpenTime]. */
data class Gap(val afterOpenTime: Long, val missing: Int)

object CandleIntegrity {
    /** Finds missing candles. Exchanges sometimes have real gaps (maintenance), so report rather than fail. */
    fun findGaps(candles: List<Candle>, timeframe: Timeframe): List<Gap> {
        val gaps = ArrayList<Gap>()
        for (i in 1 until candles.size) {
            val step = candles[i].openTime - candles[i - 1].openTime
            if (step > timeframe.millis) {
                gaps += Gap(candles[i - 1].openTime, (step / timeframe.millis - 1).toInt())
            }
        }
        return gaps
    }
}
