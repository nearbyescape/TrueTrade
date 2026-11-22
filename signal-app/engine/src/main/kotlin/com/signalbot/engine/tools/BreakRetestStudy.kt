package com.signalbot.engine.tools

import com.signalbot.engine.backtest.BrResult
import com.signalbot.engine.backtest.BreakRetestBacktest
import com.signalbot.engine.backtest.Stats
import com.signalbot.engine.data.Market
import com.signalbot.engine.history.BinanceArchive
import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe
import com.signalbot.engine.strategy.BreakRetestEngine
import com.signalbot.engine.strategy.BreakRetestPresets
import com.signalbot.engine.strategy.DailyTrend
import com.signalbot.engine.strategy.ExitMode
import com.signalbot.engine.strategy.Resample
import com.signalbot.engine.strategy.StopMode
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/**
 * Tests the break-and-retest setup on Binance futures history for the six desktop assets, on every
 * timeframe the desktop app charts, with and without her top-down lines and her opposing-line stop.
 *   ./gradlew :engine:breakretest --args="2023-01 2026-11-21 backtest-out [timeframe]"
 * 1H, 4H and daily: Jul 2023 onwards (unseen from Jul 2025). 5m and 15m: the last 12 months (unseen: last 3).
 */
fun main(args: Array<String>) {
    val from = YearMonth.parse(args.getOrNull(0) ?: "2023-01")
    val to = args.getOrNull(1)?.let(LocalDate::parse) ?: LocalDate.now(ZoneOffset.UTC).minusDays(1)
    val out = File(args.getOrNull(2) ?: "backtest-out").apply { mkdirs() }
    val only = args.getOrNull(3)?.split(',')?.toSet()
    val archive = BinanceArchive(File(out, "cache"), log = { print(".") })
    val assets = Assets.DESKTOP
    val sb = StringBuilder()
    fun say(s: String) { println(s); sb.append(s).append('\n') }

    print("Loading 1h history")
    val hourly = assets.associate { it.id to archive.hourly(it.routes.first().symbol, Market.PERP, from, to) }
    val funding = assets.associate { it.id to archive.funding(it.routes.first().symbol, from, to) }
    val shortFrom = YearMonth.from(to).minusMonths(11)
    val wantShort = only == null || "15m" in only || "5m" in only
    print("\nLoading 15m and 5m history")
    val m15 = if (wantShort) assets.associate { it.id to archive.klines(it.routes.first().symbol, Market.PERP, "15m", shortFrom, to) } else emptyMap()
    val m5 = if (wantShort) assets.associate { it.id to archive.klines(it.routes.first().symbol, Market.PERP, "5m", shortFrom, to) } else emptyMap()
    println()

    val longStart = day("2023-07-01"); val longUnseen = day("2025-07-01")
    val shortStart = shortFrom.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val shortUnseen = YearMonth.from(to).minusMonths(2).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val end = hourly.values.filter { it.isNotEmpty() }.maxOf { it.last().closeTime } + 1

    data class Variant(val name: String, val stop: StopMode, val topDown: Boolean, val trend: Boolean, val exit: ExitMode = ExitMode.TRAIL)
    val variants = listOf(
        Variant("previous app rules (swing stop, trend filter)", StopMode.SWING, topDown = false, trend = true),
        Variant("her stop on the opposing line", StopMode.LINE, topDown = false, trend = true),
        Variant("her top-down lines", StopMode.SWING, topDown = true, trend = true),
        Variant("HER FULL METHOD: top-down + opposing-line stop + trend filter", StopMode.LINE, topDown = true, trend = true),
        Variant("her full method, no trend filter", StopMode.LINE, topDown = true, trend = false),
        Variant("her full method, take profit at S/R", StopMode.LINE, topDown = true, trend = true, exit = ExitMode.TARGET),
    )

    fun series(id: String, tf: Timeframe): List<Candle> = when (tf) {
        Timeframe.M5 -> m5[id].orEmpty()
        Timeframe.M15 -> m15[id].orEmpty()
        Timeframe.H1 -> hourly.getValue(id)
        else -> Resample.from1h(hourly.getValue(id), tf)
    }

    for (tf in listOf(Timeframe.D1, Timeframe.H4, Timeframe.H1, Timeframe.M15, Timeframe.M5)) {
        if (only != null && tf.label !in only) continue
        val short = tf == Timeframe.M5 || tf == Timeframe.M15
        val (start, unseen) = if (short) shortStart to shortUnseen else longStart to longUnseen
        say("\n================ ${tf.label} candles${if (short) " (last 12 months)" else ""}")
        for (v in variants) {
            val t0 = System.currentTimeMillis()
            val cfg = BreakRetestPresets.forTimeframe(tf).copy(stop = v.stop, exit = v.exit)
            val results = assets.flatMap { a ->
                val base = series(a.id, tf)
                if (base.size < 500) return@flatMap emptyList<BrResult>()
                val s = Series(base)
                val higher = if (!v.topDown) emptyList() else BreakRetestPresets.higherOf(tf).map { h ->
                    val hs = when {
                        h.millis < Timeframe.H1.millis -> Resample.to(base, h)
                        h == Timeframe.H1 -> hourly.getValue(a.id)
                        else -> Resample.from1h(hourly.getValue(a.id), h)
                    }.filter { it.openTime >= base.first().openTime - 400L * h.millis }
                    Series(hs) to BreakRetestPresets.forTimeframe(h)
                }
                val (al, ash) = if (v.trend) DailyTrend.allow(Series(Resample.from1h(hourly.getValue(a.id), Timeframe.D1)), s) else null to null
                val replay = BreakRetestEngine(cfg).replay(s, allowLong = al, allowShort = ash, higher = higher)
                BreakRetestBacktest.price(a.id, replay, funding.getValue(a.id))
            }.filter { it.entryTime >= start && !it.openAtEnd }
            say("\n== ${v.name}  (${(System.currentTimeMillis() - t0) / 1000}s)")
            for ((label, a, b) in listOf(Triple("development", start, unseen), Triple("UNSEEN", unseen, end), Triple("full", start, end))) {
                val rs = results.filter { it.entryTime in a until b }
                val st = Stats.ofRows(rs.map { it.row() }, 0.01, b - a)
                say("  ${label.padEnd(11)} ${st.trades.toString().padStart(5)} trades (${f(st.tradesPerWeek)}/wk), win ${p(st.winRate)}, " +
                    "avg ${f(st.avgR)}R, total ${f(st.totalR, 1)}R, PF ${f(st.profitFactor)}, maxDD ${f(st.maxDrawdownR, 1)}R, " +
                    "avg win ${f(st.avgWinR)}R, fees ${f(st.feesR / maxOf(1, st.trades))}R/trade, hold ${f(st.avgHoldHours, 1)}h")
            }
            val line = results.groupBy { it.assetId }.toSortedMap().entries.joinToString("  ") { (id, g) ->
                "$id ${g.size}/${f(g.map { it.netR }.average())}R"
            }
            say("    per asset (trades/avg): $line")
        }
    }
    File(out, "breakretest.txt").writeText(sb.toString())
}

private fun day(s: String) = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun f(v: Double, d: Int = 2) = String.format(Locale.US, "%.${d}f", v)
private fun p(v: Double) = String.format(Locale.US, "%.0f%%", v * 100)
