package com.signalbot.engine.strategy

import com.signalbot.engine.data.HttpGet
import com.signalbot.engine.data.HttpResponse
import com.signalbot.engine.data.MarketDataException
import com.signalbot.engine.data.RetryPolicy
import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrategyPartsTest {
    private val hour = Timeframe.H1.millis

    private fun hourly(n: Int, start: Long = Synthetic.START) = (0 until n).map { i ->
        val t = start + i * hour
        Candle(t, 10.0 + i, 11.0 + i, 9.0 + i, 10.5 + i, 1.0 + i, t + hour - 1)
    }

    // ------------------------------------------------------------ resample + alignment

    @Test fun resamplesHourlyInto4h() {
        val h4 = Resample.from1h(hourly(10), Timeframe.H4)
        assertEquals(2, h4.size, "the third 4H period is incomplete and dropped")
        with(h4[0]) {
            assertEquals(Synthetic.START, openTime)
            assertEquals(10.0, open); assertEquals(14.0, high); assertEquals(9.0, low); assertEquals(13.5, close)
            assertEquals(1.0 + 2 + 3 + 4, volume)
            assertEquals(Synthetic.START + 4 * hour - 1, closeTime)
        }
        assertEquals(17.5, h4[1].close)
    }

    @Test fun resampleAlignsToUtcEvenWhenStartingMidPeriod() {
        val h4 = Resample.from1h(hourly(10, start = Synthetic.START + 2 * hour), Timeframe.H4)
        assertEquals(listOf(Synthetic.START + 4 * hour, Synthetic.START + 8 * hour), h4.map { it.openTime })
    }

    @Test fun closedIndexOnlyCountsFinishedCandles() {
        val h4 = Series(Resample.from1h(hourly(24), Timeframe.H4))
        assertEquals(-1, h4.closedIndexAt(Timeframe.H4, Synthetic.START + 4 * hour - 1))
        assertEquals(0, h4.closedIndexAt(Timeframe.H4, Synthetic.START + 4 * hour))
        assertEquals(0, h4.closedIndexAt(Timeframe.H4, Synthetic.START + 7 * hour))
        assertEquals(1, h4.closedIndexAt(Timeframe.H4, Synthetic.START + 8 * hour))
        assertEquals(5, h4.closedIndexAt(Timeframe.H4, Long.MAX_VALUE / 2))
    }

    @Test fun mirroringFlipsPricesAndKeepsVolume() {
        val m = Series(hourly(3)).mirrored()
        assertEquals(-10.0, m.open[0]); assertEquals(-9.0, m.high[0]); assertEquals(-11.0, m.low[0]); assertEquals(1.0, m.volume[0])
        assertEquals(Series(hourly(3)).close.toList(), m.mirrored().close.toList())
    }

    // ------------------------------------------------------------ assets

    @Test fun defaultWatchList() {
        assertEquals(listOf("BTC", "ETH", "SOL", "GOLD", "SILVER"), Assets.DEFAULT.map { it.id })
        assertEquals("BINANCE perp XAUUSDT", Assets.GOLD.routes[0].toString())
        assertEquals("BYBIT perp XAUTUSDT", Assets.GOLD.routes[1].toString())
        assertEquals("BINANCE perp XAGUSDT", Assets.SILVER.routes[0].toString())
        assertEquals("BINANCE perp SOLUSDT", Assets.SOL.routes[0].toString())
        assertTrue(Assets.DEFAULT.all { a -> a.routes.all { it.market == com.signalbot.engine.data.Market.PERP } }, "futures only")
        assertTrue(Assets.ETH.followsBtc && Assets.SOL.followsBtc)
        assertFalse(Assets.BTC.followsBtc || Assets.GOLD.followsBtc || Assets.SILVER.followsBtc)
        assertEquals(Assets.SILVER, Assets.byId("silver"))
    }

    @Test fun metalsWeekendWindow() {
        fun at(day: Int, hourOfDay: Int, minute: Int = 0) =
            ZonedDateTime.of(2026, 11, day, hourOfDay, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        // 20 Nov 2026 is a Friday.
        assertFalse(Assets.GOLD.underlyingClosedAt(at(20, 20, 59)))
        assertTrue(Assets.GOLD.underlyingClosedAt(at(20, 21)))
        assertTrue(Assets.GOLD.underlyingClosedAt(at(21, 12)))
        assertTrue(Assets.GOLD.underlyingClosedAt(at(22, 22, 59)))
        assertFalse(Assets.GOLD.underlyingClosedAt(at(22, 23)))
        assertFalse(Assets.BTC.underlyingClosedAt(at(21, 12)), "crypto trades all weekend")
    }

    // ------------------------------------------------------------ loading

    /** Answers kline requests for any exchange/interval with generated candles; can fail chosen URLs. */
    private class FakeExchanges(val failWhen: (String) -> Boolean) : HttpGet {
        val urls = mutableListOf<String>()
        override fun get(url: String): HttpResponse {
            urls += url
            if (failWhen(url)) return HttpResponse(503, "")
            val q = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val limit = q.getValue("limit").toInt()
            val tf = when (q["interval"]) { "1h", "60" -> Timeframe.H1; "4h", "240" -> Timeframe.H4; else -> Timeframe.D1 }
            val now = 1_763_719_000_000L
            val newestOpen = tf.floor(now)
            val rows = (limit - 1 downTo 0).map { newestOpen - it * tf.millis }
            val price = if (url.contains("bybit")) 2.0 else 1.0 // tells the sources apart
            return if (url.contains("bybit")) {
                val list = rows.reversed().joinToString(",") { """["$it","$price","$price","$price","$price","5","5"]""" }
                HttpResponse(200, """{"retCode":0,"retMsg":"OK","result":{"list":[$list]}}""")
            } else {
                HttpResponse(200, "[" + rows.joinToString(",") { """[$it,"$price","$price","$price","$price","5",${it + tf.millis - 1}]""" } + "]")
            }
        }
    }

    private val noWait = RetryPolicy(maxAttempts = 2, sleeper = {})
    private val now = { 1_763_719_000_000L }

    @Test fun loadsGoldFromBinanceFutures() {
        val http = FakeExchanges { false }
        val data = AssetDataLoader(http, noWait, now).load(Assets.GOLD, AssetDataLoader.Counts(5, 5, 5))
        assertEquals(5, data.h1.size); assertEquals(5, data.h4.size); assertEquals(5, data.d1.size)
        assertEquals("BINANCE perp XAUUSDT", data.source)
        assertTrue(http.urls.all { it.startsWith("https://fapi.binance.com/fapi/v1/klines?symbol=XAUUSDT") })
    }

    @Test fun neverMixesExchangesWithinOneLoad() {
        // Binance answers 1H and 4H, then fails on 1D: everything must be reloaded from Bybit.
        val http = FakeExchanges { it.contains("binance") && it.contains("interval=1d") }
        val data = AssetDataLoader(http, noWait, now).load(Assets.SILVER, AssetDataLoader.Counts(5, 5, 5))
        assertEquals("BYBIT perp XAGUSDT", data.source)
        for (s in listOf(data.h1, data.h4, data.d1)) assertTrue(s.close.all { it == 2.0 }, "all prices from Bybit")
        assertTrue(http.urls.any { it.contains("category=linear&symbol=XAGUSDT") })
    }

    @Test fun reportsWhenEveryRouteFails() {
        val e = assertFailsWith<MarketDataException> {
            AssetDataLoader(FakeExchanges { true }, noWait, now).load(Assets.ETH, AssetDataLoader.Counts(5, 5, 5))
        }
        assertTrue(e.message!!.contains("BINANCE perp ETHUSDT") && e.message!!.contains("BYBIT perp ETHUSDT"))
    }

    // ------------------------------------------------------------ sizing + cooldown

    @Test fun positionSizeIncludesFees() {
        val s = PositionSizer.size(capital = 100_000.0, riskFraction = 0.01, entry = 100.0, stop = 96.0)
        assertEquals(1_000.0 / (4.0 + 0.0005 * 196.0), s.quantity, 1e-9)
        assertEquals(1_000.0, s.riskAmount, 1e-6)
        assertFalse(s.cappedByCapital)
    }

    @Test fun leverageChangesMarginNotRisk() {
        val x1 = PositionSizer.size(100_000.0, 0.01, entry = 100.0, stop = 96.0, leverage = 1.0)
        val x2 = PositionSizer.size(100_000.0, 0.01, entry = 100.0, stop = 96.0, leverage = 2.0)
        assertEquals(x1.quantity, x2.quantity, 1e-12)
        assertEquals(x1.riskAmount, x2.riskAmount, 1e-9)
        assertEquals(x1.margin / 2, x2.margin, 1e-9)
        assertEquals(100.0 * (1 - 0.5 + 0.005), x2.liquidationPrice, 1e-9)
        assertTrue(x2.liquidationSafe)
    }

    @Test fun flagsLeverageThatWouldLiquidateBeforeTheStop() {
        // 4% stop: liquidation must be >= 8% away -> max about 11x.
        assertEquals(11, PositionSizer.maxSafeLeverage(100.0, 96.0))
        assertTrue(PositionSizer.size(100_000.0, 0.01, 100.0, 96.0, leverage = 11.0).liquidationSafe)
        assertFalse(PositionSizer.size(100_000.0, 0.01, 100.0, 96.0, leverage = 20.0).liquidationSafe)
        // Shorts: liquidation above entry.
        val short = PositionSizer.size(100_000.0, 0.01, entry = 96.0, stop = 100.0, leverage = 5.0)
        assertEquals(96.0 * (1 + 0.2 - 0.005), short.liquidationPrice, 1e-9)
        assertTrue(short.liquidationSafe)
    }

    @Test fun positionSizeCapsAtCapitalTimesLeverageAndRoundsToStep() {
        val tight = PositionSizer.size(100_000.0, 0.01, entry = 100.0, stop = 99.9, feeRate = 0.0)
        assertTrue(tight.cappedByCapital)
        assertEquals(100_000.0, tight.notional, 1e-6)
        val btc = PositionSizer.size(10_000.0, 0.01, 91_000.0, 89_000.0, step = 0.001)
        assertEquals(0.0, Math.round(btc.quantity * 1000) - btc.quantity * 1000, 1e-6)
        assertTrue(btc.riskAmount <= 100.0)
    }

    @Test fun cooldownHoldsRepeatsButLetsUpgradesThrough() {
        val engine = SignalEngine()
        val s = Synthetic.marketData(Assets.BTC, Synthetic.hourly(24 * 420, 0.00015, 1)).let { d ->
            val p = engine.prepare(d); (0 until d.h1.size).map { engine.evaluate(p, it, Direction.LONG) }.filterIsInstance<Signal>()
        }.first { it.grade == Grade.ALERT }
        val cd = SignalCooldown(hours = 12)
        assertTrue(cd.admit(s))
        assertFalse(cd.admit(s.copy(timeMs = s.timeMs + 3 * hour)), "same move 3h later")
        assertTrue(cd.admit(s.copy(timeMs = s.timeMs + 4 * hour, grade = Grade.A_PLUS)), "a stronger setup gets through")
        assertTrue(cd.admit(s.copy(timeMs = s.timeMs + 17 * hour)), "after the cooldown")
        assertTrue(cd.admit(s.copy(assetId = "ETH", timeMs = s.timeMs + 17 * hour + 1)), "other assets are separate")
    }
}
