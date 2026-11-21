package com.signalbot.engine.history

import com.signalbot.engine.data.Market
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinanceArchiveTest {
    private fun zip(name: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        return out.toByteArray()
    }

    private val hour = 3_600_000L
    private fun row(t: Long, micro: Boolean = false): String {
        val m = if (micro) 1000 else 1
        return "${t * m},100.0,101.0,99.0,100.5,12.5,${(t + hour - 1) * m},1250,10,6,600,0"
    }

    @Test fun parsesFilesWithAndWithoutHeaderAndMicroseconds() {
        val t0 = 1_790_812_800_000L
        val withHeader = zip("a.csv", "open_time,open,high,low,close,volume,close_time,quote_volume,count,taker_buy_volume,taker_buy_quote_volume,ignore\n${row(t0)}\n")
        val plain = zip("b.csv", "${row(t0)}\n${row(t0 + hour)}\n")
        val micro = zip("c.csv", "${row(t0, micro = true)}\n")
        assertEquals(1, BinanceArchive.parseKlines(withHeader).size)
        assertEquals(2, BinanceArchive.parseKlines(plain).size)
        val m = BinanceArchive.parseKlines(micro).single()
        assertEquals(t0, m.openTime)
        assertEquals(t0 + hour - 1, m.closeTime)
        assertEquals(100.5, m.close)
    }

    @Test fun parsesFunding() {
        val f = BinanceArchive.parseFunding(zip("f.csv", "calc_time,funding_interval_hours,last_funding_rate\n1790812800001,8,0.00007981\n"))
        assertEquals(listOf(FundingEvent(1790812800001, 0.00007981)), f)
    }

    @Test fun usesMonthlyFilesThenDailyForTheCurrentMonthAndCaches() {
        val dir = Files.createTempDirectory("archive").toFile()
        val requested = mutableListOf<String>()
        val t0 = LocalDate.parse("2026-10-31").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val t1 = LocalDate.parse("2026-11-02").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val http = ByteGet { url ->
            requested += url.substringAfter("/data/")
            when {
                url.endsWith("BTCUSDT-1h-2026-10.zip") -> zip("m.csv", row(t0) + "\n")
                url.endsWith("BTCUSDT-1h-2026-11-02.zip") -> zip("d.csv", row(t1) + "\n")
                else -> null
            }
        }
        val archive = BinanceArchive(dir, http)
        val candles = archive.hourly("BTCUSDT", Market.PERP, YearMonth.parse("2026-09"), LocalDate.parse("2026-11-02"))
        assertEquals(listOf(t0, t1), candles.map { it.openTime })
        assertTrue("futures/um/monthly/klines/BTCUSDT/1h/BTCUSDT-1h-2026-09.zip" in requested)
        assertTrue(requested.none { it.contains("2026-09-") }, "no day-by-day requests for an old month with no file")
        assertTrue(requested.count { it.contains("/daily/") } == 2, "Nov 1 and Nov 2 only")
        // Second run comes from the cache.
        requested.clear()
        archive.hourly("BTCUSDT", Market.PERP, YearMonth.parse("2026-10"), LocalDate.parse("2026-10-31"))
        assertTrue(requested.isEmpty(), "cached: $requested")
        dir.deleteRecursively()
        check(!File(dir, "x").exists())
    }
}
