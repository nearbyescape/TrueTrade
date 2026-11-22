package com.signalbot.engine.history

import com.signalbot.engine.data.Market
import com.signalbot.engine.model.Candle
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

/** One funding payment time and rate (rate x position value; longs pay when positive). */
data class FundingEvent(val timeMs: Long, val rate: Double)

/** Downloads raw bytes; null when the file does not exist (404). Swappable for tests. */
fun interface ByteGet {
    @Throws(IOException::class)
    fun get(url: String): ByteArray?
}

class UrlConnectionBytes(private val timeoutMs: Int = 60_000) : ByteGet {
    override fun get(url: String): ByteArray? {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            return when (val code = conn.responseCode) {
                200 -> conn.inputStream.use { it.readBytes() }
                403, 404 -> null // the archive answers 404 (sometimes 403) for files that don't exist
                else -> throw IOException("HTTP $code for $url")
            }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Binance's official bulk history (data.binance.vision): complete monthly files, plus daily files
 * for the current month. Free, no key, and far faster than paging the REST API for years of data.
 * Downloads are cached on disk, so a second backtest run needs no network.
 *
 * Paths:  data/futures/um/monthly/klines/BTCUSDT/1h/BTCUSDT-1h-2024-11.zip
 *         data/futures/um/daily/klines/BTCUSDT/1h/BTCUSDT-1h-2026-11-20.zip
 *         data/futures/um/monthly/fundingRate/BTCUSDT/BTCUSDT-fundingRate-2026-10.zip
 *         data/spot/monthly/klines/PAXGUSDT/1h/...
 */
class BinanceArchive(
    private val cacheDir: File,
    private val http: ByteGet = UrlConnectionBytes(),
    private val baseUrl: String = "https://data.binance.vision/data",
    private val log: (String) -> Unit = {},
) {
    private fun marketPath(market: Market) = if (market == Market.PERP) "futures/um" else "spot"

    /** 1H candles from the start of [from] up to and including [to] (UTC days). Months before listing are skipped. */
    fun hourly(symbol: String, market: Market, from: YearMonth, to: LocalDate): List<Candle> = klines(symbol, market, "1h", from, to)

    /** Candles of [interval] ("5m", "15m", "1h", ...) from the start of [from] up to and including [to] (UTC days). */
    fun klines(symbol: String, market: Market, interval: String, from: YearMonth, to: LocalDate): List<Candle> {
        val base = "${marketPath(market)}/%s/klines/$symbol/$interval/$symbol-$interval-%s.zip"
        val byTime = sortedMapOf<Long, Candle>()
        var month = from
        val lastMonth = YearMonth.from(to)
        while (!month.isAfter(lastMonth)) {
            // A month fully inside the range comes from its monthly file.
            val monthly = if (!month.atEndOfMonth().isAfter(to)) fetch(base.format("monthly", month)) else null
            if (monthly != null) {
                parseKlines(monthly).forEach { byTime[it.openTime] = it }
            } else if (!month.isBefore(lastMonth.minusMonths(1))) {
                // Current month, or a just-finished month whose monthly file isn't published yet.
                // (Older months with no file are simply before the listing: no point asking day by day.)
                var day = month.atDay(1)
                val end = minOf(month.atEndOfMonth(), to)
                while (!day.isAfter(end)) {
                    fetch(base.format("daily", day))?.let { bytes -> parseKlines(bytes).forEach { byTime[it.openTime] = it } }
                    day = day.plusDays(1)
                }
            }
            month = month.plusMonths(1)
        }
        val endMs = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return byTime.values.filter { it.closeTime < endMs }
    }

    /** Funding events (perpetuals only). The archive publishes them monthly, so the current month is missing. */
    fun funding(symbol: String, from: YearMonth, to: LocalDate): List<FundingEvent> {
        val out = ArrayList<FundingEvent>()
        var month = from
        while (!month.isAfter(YearMonth.from(to))) {
            fetch("futures/um/monthly/fundingRate/$symbol/$symbol-fundingRate-$month.zip")?.let { out += parseFunding(it) }
            month = month.plusMonths(1)
        }
        return out.sortedBy { it.timeMs }
    }

    private fun fetch(path: String): ByteArray? {
        val file = File(cacheDir, path)
        val missing = File(cacheDir, "$path.missing")
        if (file.exists()) return file.readBytes()
        // Remember 404s for past months only; today's daily file may appear later.
        if (missing.exists() && System.currentTimeMillis() - missing.lastModified() < 6 * 3_600_000L) return null
        log("download $path")
        val bytes = http.get("$baseUrl/$path")
        file.parentFile.mkdirs()
        if (bytes == null) { missing.writeText(""); return null }
        file.writeBytes(bytes)
        return bytes
    }

    companion object {
        /** Kline CSV inside a zip. Handles files with or without a header, and microsecond timestamps (spot, 2025+). */
        fun parseKlines(zip: ByteArray): List<Candle> = csvLines(zip).mapNotNull { line ->
            val c = line.split(',')
            if (c.size < 7 || !c[0].first().isDigit()) return@mapNotNull null
            Candle(
                openTime = toMillis(c[0].toLong()),
                open = c[1].toDouble(),
                high = c[2].toDouble(),
                low = c[3].toDouble(),
                close = c[4].toDouble(),
                volume = c[5].toDouble(),
                closeTime = toMillis(c[6].toLong()),
            )
        }

        fun parseFunding(zip: ByteArray): List<FundingEvent> = csvLines(zip).mapNotNull { line ->
            val c = line.split(',')
            if (c.size < 3 || !c[0].first().isDigit()) return@mapNotNull null
            FundingEvent(toMillis(c[0].toLong()), c[2].toDouble())
        }

        /** Timestamps above ~5138 AD in ms are really microseconds. */
        private fun toMillis(t: Long) = if (t > 100_000_000_000_000L) t / 1000 else t

        private fun csvLines(zip: ByteArray): List<String> {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                z.nextEntry ?: return emptyList()
                return z.bufferedReader().readLines().filter { it.isNotBlank() }
            }
        }
    }
}
