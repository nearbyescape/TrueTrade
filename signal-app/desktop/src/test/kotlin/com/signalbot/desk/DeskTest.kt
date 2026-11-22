package com.signalbot.desk

import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Candle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeskTest {
    private val m5 = 300_000L

    private fun msg(sym: String, tf: String, t: Long, close: Double, closed: Boolean) =
        """{"stream":"$sym@kline_$tf","data":{"e":"kline","E":1,"s":"X","k":{"t":$t,"T":${t + m5 - 1},"s":"X","i":"$tf",""" +
            """"o":"100.0","c":"$close","h":"105.5","l":"99.5","v":"12.5","x":$closed}}}"""

    @Test
    fun `live candle updates the forming candle, a closed one is appended once`() {
        val market = LiveMarket(Assets.DESKTOP, CoroutineScope(Dispatchers.Unconfined))
        val closes = ArrayList<Pair<String, Int>>()
        market.onClose = { b, n -> closes += "${b.asset.id}/${b.tf}" to n }
        val t0 = 1_795_000_200_000L
        val b = market.book("XRP", Tf.M5)
        b.closed = listOf(Candle(t0, 1.0, 1.1, 0.9, 1.05, 1.0, t0 + m5 - 1))
        b.source = "BINANCE"

        market.handle(msg("xrpusdt", "5m", t0 + m5, 101.0, closed = false))
        assertEquals(101.0, assertNotNull(b.forming).close)
        assertEquals(1, b.closed.size)

        market.handle(msg("xrpusdt", "5m", t0 + m5, 102.0, closed = true))
        assertEquals(2, b.closed.size)
        assertEquals(102.0, b.closed.last().close)
        assertNull(b.forming)
        assertEquals(listOf("XRP/M5" to 1), closes)

        market.handle(msg("xrpusdt", "5m", t0 + m5, 102.0, closed = true)) // duplicate: ignored
        assertEquals(2, b.closed.size)
        market.handle("""{"result":null,"id":1}""") // not a kline: ignored
        market.handle("not json")
        assertEquals(1, closes.size)
    }

    @Test
    fun `gold and silver streams map to their assets`() {
        val market = LiveMarket(Assets.DESKTOP, CoroutineScope(Dispatchers.Unconfined))
        val t0 = 1_795_000_200_000L
        market.handle(msg("xauusdt", "15m", t0, 4100.0, closed = false))
        market.handle(msg("xagusdt", "1d", t0, 51.0, closed = false))
        assertEquals(4100.0, market.book("GOLD", Tf.M15).forming?.close)
        assertEquals(51.0, market.book("SILVER", Tf.D1).forming?.close)
    }

    @Test
    fun `plain prices have no commas and enough decimals`() {
        assertEquals("91250.5", DeskAnalysis.plain(91_250.5))
        assertEquals("136.95", DeskAnalysis.plain(136.951))
        assertEquals("1.9975", DeskAnalysis.plain(1.99751))
        assertEquals("0.12345", DeskAnalysis.plain(0.123451))
    }

    @Test
    fun `settings round-trip keeps alert timeframes`() {
        val s = DeskSettings(alertTfs = setOf(Tf.D1, Tf.H4), riskPct = 1.5)
        assertTrue(Tf.H4 in s.alertTfs)
        assertEquals(Tf.H1, Tf.of(com.signalbot.engine.model.Timeframe.H1))
    }
}
