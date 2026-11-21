package com.signalbot.engine.data

import com.signalbot.engine.model.Timeframe
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExchangeSourcesTest {

    /** Plays back canned responses in order and records the URLs asked for. */
    private class FakeHttp(vararg responses: HttpResponse) : HttpGet {
        private val queue = ArrayDeque(responses.toList())
        val urls = mutableListOf<String>()
        override fun get(url: String): HttpResponse {
            urls += url
            return queue.removeFirstOrNull() ?: error("no more canned responses")
        }
    }

    private val sleeps = mutableListOf<Long>()
    private val retry = RetryPolicy(maxAttempts = 3, baseDelayMs = 1_000, sleeper = { sleeps += it })

    // Shapes copied from the exchanges' API docs.
    private val binanceBody = """
        [[1763712000000,"91500.10","91820.00","91400.00","91750.55","812.33",1763715599999,"74400000.1",12000,"400.1","36600000.2","0"],
         [1763715600000,"91750.55","92010.00","91600.00","91980.00","640.12",1763719199999,"58800000.3",9800,"320.0","29400000.0","0"]]
    """.trimIndent()

    private val bybitBody = """
        {"retCode":0,"retMsg":"OK","result":{"category":"spot","symbol":"BTCUSDT","list":[
          ["1763715600000","91750.55","92010.00","91600.00","91980.00","640.12","58800000.3"],
          ["1763712000000","91500.10","91820.00","91400.00","91750.55","812.33","74400000.1"]]},"time":1763719000000}
    """.trimIndent()

    @Test fun binanceParsesAndBuildsUrl() {
        val http = FakeHttp(HttpResponse(200, binanceBody))
        val candles = BinanceSource(http, retry = retry).klines("BTCUSDT", Timeframe.H1, 2, endTimeMs = 1763719199999)
        assertEquals(2, candles.size)
        assertEquals(1763712000000, candles[0].openTime)
        assertEquals(91750.55, candles[0].close)
        assertEquals(812.33, candles[0].volume)
        assertEquals(1763715599999, candles[0].closeTime)
        assertEquals(
            "https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1h&limit=2&endTime=1763719199999",
            http.urls.single(),
        )
    }

    @Test fun bybitIsReversedToOldestFirstWithCloseTime() {
        val http = FakeHttp(HttpResponse(200, bybitBody))
        val candles = BybitSource(http, retry = retry).klines("BTCUSDT", Timeframe.H4, 2)
        assertEquals(listOf(1763712000000, 1763715600000), candles.map { it.openTime })
        assertEquals(1763712000000 + 4 * 3_600_000 - 1, candles[0].closeTime)
        assertTrue(http.urls.single().contains("category=spot&symbol=BTCUSDT&interval=240&limit=2"))
    }

    @Test fun bothSourcesAgreeOnTheSameCandles() {
        val b = BinanceSource(FakeHttp(HttpResponse(200, binanceBody)), retry = retry).klines("BTCUSDT", Timeframe.H1, 2)
        val y = BybitSource(FakeHttp(HttpResponse(200, bybitBody)), retry = retry).klines("BTCUSDT", Timeframe.H1, 2)
        assertEquals(b, y)
    }

    @Test fun retriesServerErrorsWithBackoff() {
        val http = FakeHttp(HttpResponse(503, ""), HttpResponse(502, ""), HttpResponse(200, binanceBody))
        val candles = BinanceSource(http, retry = retry).klines("BTCUSDT", Timeframe.H1, 2)
        assertEquals(2, candles.size)
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test fun honoursRetryAfterOnRateLimit() {
        val http = FakeHttp(HttpResponse(429, "", retryAfterSeconds = 5), HttpResponse(200, binanceBody))
        BinanceSource(http, retry = retry).klines("BTCUSDT", Timeframe.H1, 2)
        assertEquals(listOf(5_000L), sleeps)
    }

    @Test fun givesUpAfterMaxAttempts() {
        val http = FakeHttp(HttpResponse(500, ""), HttpResponse(500, ""), HttpResponse(500, ""))
        val e = assertFailsWith<MarketDataException> { BinanceSource(http, retry = retry).klines("BTCUSDT", Timeframe.H1, 2) }
        assertTrue(e.retryable)
        assertEquals(3, http.urls.size)
    }

    @Test fun doesNotRetryClientErrors() {
        val http = FakeHttp(HttpResponse(400, """{"code":-1121,"msg":"Invalid symbol."}"""))
        val e = assertFailsWith<MarketDataException> { BinanceSource(http, retry = retry).klines("NOPEUSDT", Timeframe.H1, 2) }
        assertTrue(e.message!!.contains("Invalid symbol."))
        assertEquals(1, http.urls.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test fun networkFailureIsRetried() {
        var calls = 0
        val http = HttpGet { calls++; if (calls == 1) throw java.io.IOException("timeout") else HttpResponse(200, binanceBody) }
        assertEquals(2, BinanceSource(http, retry = retry).klines("BTCUSDT", Timeframe.H1, 2).size)
    }

    @Test fun garbageBodyIsARetryableError() {
        val http = FakeHttp(HttpResponse(200, "<html>captive portal</html>"), HttpResponse(200, binanceBody))
        assertEquals(2, BinanceSource(http, retry = retry).klines("BTCUSDT", Timeframe.H1, 2).size)
    }

    @Test fun bybitRetCodeErrors() {
        val busy = """{"retCode":10006,"retMsg":"Too many visits!","result":{}}"""
        val bad = """{"retCode":10001,"retMsg":"Not supported symbols","result":{}}"""
        assertEquals(2, BybitSource(FakeHttp(HttpResponse(200, busy), HttpResponse(200, bybitBody)), retry = retry)
            .klines("BTCUSDT", Timeframe.H1, 2).size)
        val e = assertFailsWith<MarketDataException> {
            BybitSource(FakeHttp(HttpResponse(200, bad)), retry = retry).klines("BTCUSDT", Timeframe.H1, 2)
        }
        assertTrue(!e.retryable)
    }

    @Test fun fallsBackToBybitWhenBinanceBansUs() {
        val binance = BinanceSource(FakeHttp(HttpResponse(418, "")), retry = retry)
        val bybit = BybitSource(FakeHttp(HttpResponse(200, bybitBody)), retry = retry)
        val source = FallbackSource(listOf(binance, bybit))
        assertEquals(2, source.klines("BTCUSDT", Timeframe.H1, 2).size)
        assertEquals("Bybit", source.lastUsed)
    }

    @Test fun reportsEveryFailureWhenAllSourcesFail() {
        val source = FallbackSource(listOf(
            BinanceSource(FakeHttp(HttpResponse(418, "")), retry = retry),
            BybitSource(FakeHttp(HttpResponse(403, "")), retry = retry),
        ))
        val e = assertFailsWith<MarketDataException> { source.klines("BTCUSDT", Timeframe.H1, 2) }
        assertTrue(e.message!!.contains("Binance") && e.message!!.contains("Bybit"))
    }

    @Test fun rejectsUnsafeSymbols() {
        assertFailsWith<IllegalArgumentException> {
            BinanceSource(FakeHttp(), retry = retry).klines("BTC&limit=5", Timeframe.H1, 2)
        }
        assertFailsWith<IllegalArgumentException> {
            BinanceSource(FakeHttp(), retry = retry).klines("BTCUSDT", Timeframe.H1, 1001)
        }
    }
}
