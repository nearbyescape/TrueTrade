package com.signalbot.engine.data

import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Timeframe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Binance candles from public endpoints (no API key, no account): spot, or USD-M perpetual futures.
 * GET /api/v3/klines -> [[openTime, "open", "high", "low", "close", "volume", closeTime, ...], ...]
 * oldest first; the last row is the candle still forming.
 */
class BinanceSource(
    private val http: HttpGet = UrlConnectionHttp(),
    private val market: Market = Market.SPOT,
    private val baseUrl: String = if (market == Market.SPOT) "https://data-api.binance.vision" else "https://fapi.binance.com",
    private val retry: RetryPolicy = RetryPolicy(),
) : MarketDataSource {
    override val name = if (market == Market.SPOT) "Binance" else "Binance Futures"
    // USD-M futures (perpetuals, incl. gold XAUUSDT and silver XAGUSDT) use the same row format on /fapi/v1/klines.
    private val path = if (market == Market.SPOT) "/api/v3/klines" else "/fapi/v1/klines"

    override fun klines(symbol: String, timeframe: Timeframe, limit: Int, endTimeMs: Long?): List<Candle> {
        checkRequest(symbol, limit)
        val url = buildString {
            append(baseUrl).append(path).append("?symbol=").append(symbol)
            append("&interval=").append(timeframe.binanceCode)
            append("&limit=").append(limit)
            if (endTimeMs != null) append("&endTime=").append(endTimeMs)
        }
        return retry.run {
            val res = http.get(url)
            when {
                res.code == 200 -> parse(res.body)
                // 429 = slow down. 418 = IP temporarily banned for ignoring 429s: don't retry, let the backup take over.
                res.code == 429 -> throw MarketDataException("Binance rate limit (429)", retryable = true,
                    retryAfterMs = res.retryAfterSeconds?.times(1000))
                res.code == 418 -> throw MarketDataException("Binance IP ban (418)", retryable = false)
                res.code >= 500 -> throw MarketDataException("Binance server error ${res.code}", retryable = true)
                else -> throw MarketDataException("Binance error ${res.code}: ${errorMessage(res.body)}", retryable = false)
            }
        }
    }

    internal fun parse(body: String): List<Candle> = parseJson(name, body) { root ->
        root.jsonArray.map { row ->
            val r = row.jsonArray
            Candle(
                openTime = r[0].jsonPrimitive.long,
                open = r[1].jsonPrimitive.content.toDouble(),
                high = r[2].jsonPrimitive.content.toDouble(),
                low = r[3].jsonPrimitive.content.toDouble(),
                close = r[4].jsonPrimitive.content.toDouble(),
                volume = r[5].jsonPrimitive.content.toDouble(),
                closeTime = r[6].jsonPrimitive.long,
            )
        }
    }

    private fun errorMessage(body: String): String = runCatching {
        Json.parseToJsonElement(body).jsonObject["msg"]?.jsonPrimitive?.content
    }.getOrNull() ?: body.take(200)
}

/**
 * Bybit candles (public, no key): spot, or "linear" USDT perpetuals.
 * GET /v5/market/kline -> {"retCode":0,"result":{"list":[["startTime","open","high","low","close","volume","turnover"], ...]}}
 * NEWEST first; errors come back as HTTP 200 with a non-zero retCode.
 */
class BybitSource(
    private val http: HttpGet = UrlConnectionHttp(),
    private val market: Market = Market.SPOT,
    private val baseUrl: String = "https://api.bybit.com",
    private val retry: RetryPolicy = RetryPolicy(),
) : MarketDataSource {
    override val name = if (market == Market.SPOT) "Bybit" else "Bybit Perp"
    private val category = if (market == Market.SPOT) "spot" else "linear"

    override fun klines(symbol: String, timeframe: Timeframe, limit: Int, endTimeMs: Long?): List<Candle> {
        checkRequest(symbol, limit)
        val url = buildString {
            append(baseUrl).append("/v5/market/kline?category=").append(category).append("&symbol=").append(symbol)
            append("&interval=").append(timeframe.bybitCode)
            append("&limit=").append(limit)
            if (endTimeMs != null) append("&end=").append(endTimeMs)
        }
        return retry.run {
            val res = http.get(url)
            when {
                res.code == 200 -> parse(res.body, timeframe)
                res.code == 429 || res.code == 403 -> throw MarketDataException("Bybit rate limit/blocked (${res.code})",
                    retryable = res.code == 429, retryAfterMs = res.retryAfterSeconds?.times(1000))
                res.code >= 500 -> throw MarketDataException("Bybit server error ${res.code}", retryable = true)
                else -> throw MarketDataException("Bybit error ${res.code}: ${res.body.take(200)}", retryable = false)
            }
        }
    }

    internal fun parse(body: String, timeframe: Timeframe): List<Candle> = parseJson(name, body) { root ->
        val obj = root.jsonObject
        val retCode = obj["retCode"]?.jsonPrimitive?.intOrNull
        if (retCode != 0) {
            val msg = obj["retMsg"]?.jsonPrimitive?.content ?: "unknown"
            // 10006 = too many requests; 10016 = service busy. Both pass with time.
            throw MarketDataException("Bybit retCode $retCode: $msg", retryable = retCode == 10006 || retCode == 10016)
        }
        val list = (obj["result"] as? JsonObject)?.get("list") as? JsonArray
            ?: throw MarketDataException("Bybit response has no result.list", retryable = true)
        list.map { row ->
            val r = row.jsonArray
            val start = r[0].jsonPrimitive.content.toLong()
            Candle(
                openTime = start,
                open = r[1].jsonPrimitive.content.toDouble(),
                high = r[2].jsonPrimitive.content.toDouble(),
                low = r[3].jsonPrimitive.content.toDouble(),
                close = r[4].jsonPrimitive.content.toDouble(),
                volume = r[5].jsonPrimitive.content.toDouble(),
                closeTime = start + timeframe.millis - 1,
            )
        }.asReversed().toList()
    }
}

/** Turns any malformed-response problem into a retryable MarketDataException (often a proxy or captive-portal page). */
private inline fun <T> parseJson(source: String, body: String, block: (JsonElement) -> T): T =
    try {
        block(Json.parseToJsonElement(body))
    } catch (e: MarketDataException) {
        throw e
    } catch (e: Exception) {
        throw MarketDataException("$source returned an unreadable response: ${e.message}", retryable = true, cause = e)
    }
