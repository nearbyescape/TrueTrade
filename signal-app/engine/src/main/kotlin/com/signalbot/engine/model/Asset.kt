package com.signalbot.engine.model

import com.signalbot.engine.data.Exchange
import com.signalbot.engine.data.Market
import com.signalbot.engine.data.MarketRoute
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

enum class AssetClass { CRYPTO, METAL }

/**
 * A tradable asset and where to get its prices. [routes] are tried in order; the first is the main
 * source, the rest are backups.
 */
data class Asset(
    /** Short id used everywhere in the app, e.g. "BTC", "GOLD". */
    val id: String,
    val displayName: String,
    val assetClass: AssetClass,
    val routes: List<MarketRoute>,
    /** Altcoins mostly follow BTC, so their longs are blocked while BTC's 4H trend is down. */
    val followsBtc: Boolean,
    /**
     * Metals trade 24/7 as crypto perpetuals, but the real market they track is shut at weekends,
     * so prices then are thin and unreliable. No new signals inside that window.
     */
    val skipWhenUnderlyingClosed: Boolean,
    /** Assets in one group tend to move together; risk limits cap open trades per group. */
    val correlationGroup: String,
) {
    init {
        require(routes.isNotEmpty()) { "$id needs at least one price route" }
    }

    /** True while the traditional gold/silver market is closed: Friday 21:00 to Sunday 23:00 UTC. */
    fun underlyingClosedAt(timeMs: Long): Boolean {
        if (!skipWhenUnderlyingClosed) return false
        val t = Instant.ofEpochMilli(timeMs).atZone(ZoneOffset.UTC)
        return when (t.dayOfWeek) {
            DayOfWeek.FRIDAY -> t.hour >= 21
            DayOfWeek.SATURDAY -> true
            DayOfWeek.SUNDAY -> t.hour < 23
            else -> false
        }
    }
}

object Assets {
    val BTC = crypto("BTC", "Bitcoin", followsBtc = false)
    val ETH = crypto("ETH", "Ethereum")
    val SOL = crypto("SOL", "Solana")

    val GOLD = Asset(
        id = "GOLD",
        displayName = "Gold",
        assetClass = AssetClass.METAL,
        routes = listOf(
            MarketRoute(Exchange.BINANCE, Market.PERP, "XAUUSDT"),
            // Backup tracks Tether Gold (1 token = 1 oz), which stays very close to spot gold.
            MarketRoute(Exchange.BYBIT, Market.PERP, "XAUTUSDT"),
        ),
        followsBtc = false,
        skipWhenUnderlyingClosed = true,
        correlationGroup = "metals",
    )

    val SILVER = Asset(
        id = "SILVER",
        displayName = "Silver",
        assetClass = AssetClass.METAL,
        routes = listOf(
            MarketRoute(Exchange.BINANCE, Market.PERP, "XAGUSDT"),
            MarketRoute(Exchange.BYBIT, Market.PERP, "XAGUSDT"),
        ),
        followsBtc = false,
        skipWhenUnderlyingClosed = true,
        correlationGroup = "metals",
    )

    val XRP = crypto("XRP", "XRP")

    /** The default watch list (phone app). Everything is traded as USDT perpetual futures. More coins: [crypto]. */
    val DEFAULT: List<Asset> = listOf(BTC, ETH, SOL, GOLD, SILVER)

    /** The desktop app's watch list: the phone's five plus XRP. */
    val DESKTOP: List<Asset> = listOf(BTC, ETH, SOL, XRP, GOLD, SILVER)

    fun byId(id: String): Asset? = DESKTOP.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** A coin's USDT perpetual on Binance futures, with Bybit's perpetual as backup, e.g. crypto("XRP", "XRP"). */
    fun crypto(id: String, name: String, followsBtc: Boolean = true): Asset = Asset(
        id = id,
        displayName = name,
        assetClass = AssetClass.CRYPTO,
        routes = listOf(
            MarketRoute(Exchange.BINANCE, Market.PERP, "${id}USDT"),
            MarketRoute(Exchange.BYBIT, Market.PERP, "${id}USDT"),
        ),
        followsBtc = followsBtc,
        skipWhenUnderlyingClosed = false,
        correlationGroup = "crypto",
    )
}
