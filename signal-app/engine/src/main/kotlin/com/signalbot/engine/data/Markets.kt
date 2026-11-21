package com.signalbot.engine.data

enum class Exchange { BINANCE, BYBIT }

/** SPOT = normal coin market. PERP = USDT perpetual futures (the only Binance market for gold and silver). */
enum class Market { SPOT, PERP }

/** Where one asset's candles come from: exchange + market + that exchange's symbol for it. */
data class MarketRoute(val exchange: Exchange, val market: Market, val symbol: String) {
    fun source(http: HttpGet = UrlConnectionHttp(), retry: RetryPolicy = RetryPolicy()): MarketDataSource =
        when (exchange) {
            Exchange.BINANCE -> BinanceSource(http, market, retry = retry)
            Exchange.BYBIT -> BybitSource(http, market, retry = retry)
        }

    override fun toString() = "$exchange ${market.name.lowercase()} $symbol"
}
