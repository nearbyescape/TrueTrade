package com.signalbot.engine.data

import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Timeframe
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandleLoaderTest {
    private val tf = Timeframe.H1
    private val hour = tf.millis
    private val start = 1_700_000_000_000L - 1_700_000_000_000L % hour // aligned to the hour

    /** Pretend exchange holding [total] hourly candles; the newest one is still open at [now]. */
    private inner class FakeExchange(total: Int, val now: Long) : MarketDataSource {
        override val name = "Fake"
        val all = (0 until total).map { i ->
            val t = start + i * hour
            Candle(t, 100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i, 10.0, t + hour - 1)
        }
        var calls = 0
        override fun klines(symbol: String, timeframe: Timeframe, limit: Int, endTimeMs: Long?): List<Candle> {
            calls++
            require(limit <= 1000)
            return all.filter { endTimeMs == null || it.openTime <= endTimeMs }.takeLast(limit)
        }
    }

    private fun exchange(total: Int): FakeExchange {
        // "now" sits 20 minutes into the newest candle, so that candle is still open.
        val now = start + (total - 1) * hour + 20 * 60_000
        return FakeExchange(total, now)
    }

    @Test fun dropsTheCandleStillForming() {
        val ex = exchange(300)
        val candles = CandleLoader(ex) { ex.now }.closed("BTCUSDT", tf, 100)
        assertEquals(100, candles.size)
        assertEquals(ex.all[298].openTime, candles.last().openTime, "newest closed is the second-to-last")
        assertTrue(candles.all { it.isClosedAt(ex.now) })
    }

    @Test fun pagesBackThroughHistory() {
        val ex = exchange(5_000)
        val candles = CandleLoader(ex) { ex.now }.closed("BTCUSDT", tf, 2_500)
        assertEquals(2_500, candles.size)
        assertEquals(ex.all[4_998].openTime, candles.last().openTime)
        assertTrue(CandleIntegrity.findGaps(candles, tf).isEmpty(), "pages join with no gaps or duplicates")
        assertEquals(3, ex.calls)
    }

    @Test fun stopsAtTheCoinsFirstCandle() {
        val ex = exchange(1_200)
        val candles = CandleLoader(ex) { ex.now }.closed("NEWUSDT", tf, 5_000)
        assertEquals(1_199, candles.size, "everything that exists, minus the open candle")
        assertEquals(ex.all.first().openTime, candles.first().openTime)
    }

    @Test fun catchesUpAfterBeingOffline() {
        val ex = exchange(500)
        val loader = CandleLoader(ex) { ex.now }
        val lastSeen = ex.all[490].openTime // phone was offline for ~8 hours
        val missed = loader.since("BTCUSDT", tf, lastSeen)
        assertEquals((491..498).map { ex.all[it].openTime }, missed.map { it.openTime })
        assertTrue(loader.since("BTCUSDT", tf, ex.all[498].openTime).isEmpty(), "nothing new has closed")
    }

    @Test fun findsGaps() {
        val ex = exchange(10)
        val withHole = ex.all.filterIndexed { i, _ -> i != 4 && i != 5 }
        assertEquals(listOf(Gap(ex.all[3].openTime, 2)), CandleIntegrity.findGaps(withHole, tf))
    }

    @Test fun timeframeFloorAlignsToUtc() {
        val t = start + 3 * hour + 59 * 60_000
        assertEquals(start + 3 * hour, Timeframe.H1.floor(t))
        assertEquals(0L, Timeframe.D1.floor(Timeframe.D1.millis * 3 + 5) % Timeframe.D1.millis)
        assertEquals(Timeframe.H4, Timeframe.parse("4H"))
    }
}
