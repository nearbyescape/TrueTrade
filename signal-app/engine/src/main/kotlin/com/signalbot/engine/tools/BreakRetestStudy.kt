package com.signalbot.engine.tools

import com.signalbot.engine.backtest.BrResult
import com.signalbot.engine.backtest.BreakRetestBacktest
import com.signalbot.engine.backtest.Stats
import com.signalbot.engine.data.Market
import com.signalbot.engine.history.BinanceArchive
import com.signalbot.engine.indicators.Indicators
import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe
import com.signalbot.engine.strategy.BreakRetestConfig
import com.signalbot.engine.strategy.BreakRetestEngine
import com.signalbot.engine.strategy.ExitMode
import com.signalbot.engine.strategy.Resample
import com.signalbot.engine.strategy.closedIndexAt
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/**
 * Tests the break-and-retest setup on Binance futures history for the five assets.
 *   ./gradlew :engine:breakretest --args="2023-01 2026-11-20"
 * Development period Jul 2023 - Jun 2025; everything after is unseen.
 */
fun main(args: Array<String>) {
    val from = YearMonth.parse(args.getOrNull(0) ?: "2023-01")
    val to = args.getOrNull(1)?.let(LocalDate::parse) ?: LocalDate.now(ZoneOffset.UTC).minusDays(1)
    val out = File(args.getOrNull(2) ?: "backtest-out").apply { mkdirs() }
    val archive = BinanceArchive(File(out, "cache"), log = { print(".") })
    print("Loading history")
    val ids = Assets.DEFAULT.map { it.id }
    val hourly = Assets.DEFAULT.associate { it.id to archive.hourly(it.routes.first().symbol, Market.PERP, from, to) }
    val funding = Assets.DEFAULT.associate { it.id to archive.funding(it.routes.first().symbol, from, to) }
    println()
    val dev = day("2023-07-01"); val unseen = day("2025-07-01")
    val end = hourly.values.maxOf { it.last().closeTime } + 1
    val sb = StringBuilder()
    fun say(s: String) { println(s); sb.append(s).append('\n') }

    data class Variant(val name: String, val tf: Timeframe, val cfg: BreakRetestConfig, val filter: Boolean)
    val only = args.getOrNull(3)
    val variants = buildList {
        for (tf in listOf(Timeframe.H1, Timeframe.H4, Timeframe.D1)) {
            val base = when (tf) {
                Timeframe.H1 -> BreakRetestConfig(pivot = 5, trailPivot = 3, retestBars = 48)
                Timeframe.H4 -> BreakRetestConfig(pivot = 3, trailPivot = 2, retestBars = 24, maxLineBars = 250)
                else -> BreakRetestConfig(pivot = 3, trailPivot = 2, retestBars = 10, maxLineBars = 200)
            }
            for (exit in ExitMode.entries) for (filter in listOf(false, true)) {
                add(Variant("${tf.label} ${exit.name.lowercase()}${if (filter) " +daily trend" else ""}", tf, base.copy(exit = exit), filter))
            }
            add(Variant("${tf.label} trail +daily trend, 3+ touch lines", tf, base.copy(minTouches = 3), true))
            add(Variant("${tf.label} trail +daily trend, bigger swings", tf, base.copy(pivot = base.pivot * 2), true))
            add(Variant("${tf.label} target, 1.5R+ only", tf, base.copy(exit = ExitMode.TARGET, minTargetR = 1.5), false))
        }
    }.filter { only == null || it.name.startsWith(only) }

    for (v in variants) {
        val results = ids.flatMap { id ->
            val h1 = hourly.getValue(id)
            if (h1.size < 1000) return@flatMap emptyList<BrResult>()
            val s = if (v.tf == Timeframe.H1) Series(h1) else Series(Resample.from1h(h1, v.tf))
            val (al, ash) = if (v.filter) trendFilter(h1, s) else null to null
            val replay = BreakRetestEngine(v.cfg).replay(s, allowLong = al, allowShort = ash)
            BreakRetestBacktest.price(id, replay, funding.getValue(id))
        }.filter { it.entryTime >= dev }
        say("\n== ${v.name}")
        for ((label, a, b) in listOf(Triple("development", dev, unseen), Triple("UNSEEN", unseen, end), Triple("full", dev, end))) {
            val rs = results.filter { !it.openAtEnd && it.entryTime in a until b }
            val st = Stats.ofRows(rs.map { it.row() }, 0.01, b - a)
            say("  ${label.padEnd(11)} ${st.trades.toString().padStart(4)} trades (${f(st.tradesPerWeek)}/wk), win ${p(st.winRate)}, " +
                "avg ${f(st.avgR)}R, total ${f(st.totalR, 1)}R, PF ${f(st.profitFactor)}, maxDD ${f(st.maxDrawdownR, 1)}R, " +
                "avg win ${f(st.avgWinR)}R, fees ${f(st.feesR / maxOf(1, st.trades))}R/trade, hold ${f(st.avgHoldHours, 0)}h")
        }
        val full = results.filter { !it.openAtEnd }
        for ((id, g) in full.groupBy { it.assetId }.toSortedMap()) {
            val st = Stats.ofRows(g.map { it.row() }, 0.01)
            say("    ${id.padEnd(7)} ${st.trades.toString().padStart(4)} trades, win ${p(st.winRate)}, avg ${f(st.avgR)}R, total ${f(st.totalR, 1)}R")
        }
        for ((d, g) in full.groupBy { it.direction }) {
            val st = Stats.ofRows(g.map { it.row() }, 0.01)
            say("    ${d.name.padEnd(7)} ${st.trades.toString().padStart(4)} trades, win ${p(st.winRate)}, avg ${f(st.avgR)}R")
        }
    }
    File(out, "breakretest.txt").writeText(sb.toString())
}

/** Higher-timeframe filter: longs only while the last closed daily candle is above its 50-day EMA, shorts below. */
internal fun trendFilter(h1: List<Candle>, s: Series): Pair<BooleanArray, BooleanArray> {
    val d = Series(Resample.from1h(h1, Timeframe.D1))
    val ema = Indicators.ema(d.close, 50)
    val tf = s.openTime[1] - s.openTime[0]
    val up = BooleanArray(s.size); val down = BooleanArray(s.size)
    for (i in 0 until s.size) {
        val k = d.closedIndexAt(Timeframe.D1, s.openTime[i] + tf)
        if (k < 0 || ema[k].isNaN()) continue
        up[i] = d.close[k] > ema[k]; down[i] = d.close[k] < ema[k]
    }
    return up to down
}

private fun day(s: String) = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun f(v: Double, d: Int = 2) = String.format(Locale.US, "%.${d}f", v)
private fun p(v: Double) = String.format(Locale.US, "%.0f%%", v * 100)
@Suppress("unused") private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
