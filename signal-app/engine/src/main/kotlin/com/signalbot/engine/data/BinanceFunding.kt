package com.signalbot.engine.data

import com.signalbot.engine.history.FundingEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Funding rate history for a Binance USD-M perpetual, from the public endpoint
 * GET /fapi/v1/fundingRate?symbol=BTCUSDT&startTime=..&limit=1000 -> [{"fundingTime":..,"fundingRate":".."}, ...]
 */
class BinanceFunding(
    private val http: HttpGet = UrlConnectionHttp(),
    private val baseUrl: String = "https://fapi.binance.com",
) {
    fun since(symbol: String, fromMs: Long, nowMs: Long = System.currentTimeMillis()): List<FundingEvent> {
        val out = ArrayList<FundingEvent>()
        var start = fromMs
        repeat(20) {
            val res = http.get("$baseUrl/fapi/v1/fundingRate?symbol=$symbol&startTime=$start&limit=1000")
            if (res.code != 200) throw MarketDataException("Binance funding error ${res.code}", retryable = res.code == 429 || res.code >= 500)
            val page = Json.parseToJsonElement(res.body).jsonArray.map {
                val o = it.jsonObject
                FundingEvent(o.getValue("fundingTime").jsonPrimitive.long, o.getValue("fundingRate").jsonPrimitive.content.toDouble())
            }
            out += page.filter { e -> out.isEmpty() || e.timeMs > out.last().timeMs }
            if (page.size < 1000 || page.last().timeMs >= nowMs) return out
            start = page.last().timeMs + 1
        }
        return out
    }
}
