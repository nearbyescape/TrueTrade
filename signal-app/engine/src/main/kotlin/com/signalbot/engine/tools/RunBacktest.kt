package com.signalbot.engine.tools

import com.signalbot.engine.backtest.AssetTape
import com.signalbot.engine.backtest.BacktestResult
import com.signalbot.engine.backtest.BacktestSettings
import com.signalbot.engine.backtest.EnsembleBacktest
import com.signalbot.engine.backtest.LegTrade
import com.signalbot.engine.backtest.Backtester
import com.signalbot.engine.backtest.ExitStyle
import com.signalbot.engine.backtest.Stats
import com.signalbot.engine.backtest.Trade
import com.signalbot.engine.data.Market
import com.signalbot.engine.history.BinanceArchive
import com.signalbot.engine.history.FundingEvent
import com.signalbot.engine.model.Asset
import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe
import com.signalbot.engine.strategy.Direction
import com.signalbot.engine.strategy.EnsembleEngine
import com.signalbot.engine.strategy.Gate
import com.signalbot.engine.strategy.MarketData
import com.signalbot.engine.strategy.Rejected
import com.signalbot.engine.strategy.Resample
import com.signalbot.engine.strategy.SetupType
import com.signalbot.engine.strategy.SignalEngine
import com.signalbot.engine.strategy.StrategyConfig
import com.signalbot.engine.strategy.TrendEngine
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/**
 * Backtest on real Binance futures history (downloaded once from data.binance.vision, then cached).
 *
 *   ./gradlew :engine:backtest                                     version 3 (breakout ensemble), 2023-01 to yesterday
 *   ./gradlew :engine:backtest --args="ensemble 2023-01 2026-11-20"
 *   ./gradlew :engine:backtest --args="trend 2023-01 2026-11-20"   version 2, for comparison
 *   ./gradlew :engine:backtest --args="v1 2023-01 2026-11-20"      version 1, for comparison
 *
 * Writes summary.txt and trades.csv to ./backtest-out.
 */
fun main(args: Array<String>) {
    val strategy = args.getOrNull(0) ?: "ensemble"
    val from = YearMonth.parse(args.getOrNull(1) ?: "2023-01")
    val to = args.getOrNull(2)?.let(LocalDate::parse) ?: LocalDate.now(ZoneOffset.UTC).minusDays(1)
    val out = File(args.getOrNull(3) ?: "backtest-out").apply { mkdirs() }
    val report = Report()
    val archive = BinanceArchive(File(out, "cache"), log = { print(".") })

    // Gold perps only exist since Dec 2025, so gold is ALSO tested on PAXG (gold-backed token, years of data).
    val paxg = Assets.GOLD.copy(id = "GOLD-PAXG", displayName = "Gold (PAXG proxy)")
    val sources = Assets.DEFAULT.map { it to (it.routes.first().symbol to Market.PERP) } + (paxg to ("PAXGUSDT" to Market.SPOT))
    print("Loading history")
    val hourly = sources.associate { (a, src) -> a.id to archive.hourly(src.first, src.second, from, to) }
    val funding = sources.associate { (a, src) -> a.id to if (src.second == Market.PERP) archive.funding(src.first, from, to) else emptyList() }
    println()
    val btcH4 = Series(Resample.from1h(hourly.getValue("BTC"), Timeframe.H4))
    val data = sources.mapNotNull { (asset, _) ->
        val h1 = hourly.getValue(asset.id)
        if (h1.size < 500) { report.say("${asset.id}: only ${h1.size} hours of history, skipped"); null }
        else asset.id to marketData(asset, h1, if (asset.followsBtc) btcH4 else null)
    }.toMap()

    report.say("== Data")
    for ((id, d) in data) report.say("  ${id.padEnd(10)} ${day(d.h1.openTime.first())} to ${day(d.h1.openTime.last())}, ${d.h1.size} hours")

    val trades = when (strategy) {
        "ensemble" -> { runEnsemble(data, funding, report, out); emptyList() }
        "trend" -> runTrend(data, funding, report)
        "v1" -> runV1(data, funding, report)
        else -> error("strategy must be 'ensemble', 'trend' or 'v1'")
    }

    File(out, "summary.txt").writeText(report.text())
    if (trades.isNotEmpty()) File(out, "trades.csv").printWriter().use { w ->
        w.println("asset,direction,signal_time,entry_time,exit_time,entry,stop,net_r,gross_r,fees_r,funding_r,outcome")
        for (t in trades) {
            w.println(listOf(t.assetId, t.direction, Instant.ofEpochMilli(t.signal.timeMs), Instant.ofEpochMilli(t.entryTime),
                Instant.ofEpochMilli(t.exitTime), t.entryPrice, t.initialStop, f(t.netR, 4), f(t.grossR, 4), f(t.feesR, 4),
                f(t.fundingR, 4), t.outcome).joinToString(","))
        }
    }
    println("\nWrote ${File(out, "summary.txt").path} and ${File(out, "trades.csv").path}")
}

// ------------------------------------------------------------------------------------------ version 2

/** Development period used to choose the version 2 settings; everything after it was unseen. */
private val DEV_START: Long = LocalDate.parse("2023-07-22").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private val HOLDOUT_START: Long = LocalDate.parse("2025-07-01").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun runTrend(data: Map<String, MarketData>, funding: Map<String, List<FundingEvent>>, r: Report): List<Trade> {
    val engine = TrendEngine()
    r.say("\nVersion 2: daily ${engine.cfg.breakoutDays}-day breakout, stop ${engine.cfg.stopAtr} x ATR, " +
        "trail ${engine.cfg.trailAtr} x ATR, ${engine.cfg.regimeEma}-day EMA trend filter, longs and shorts.")
    val tapes = data.mapValues { (id, d) -> AssetTape.buildTrend(engine, d, funding.getValue(id)) }
    val s = BacktestSettings(exitStyle = ExitStyle.TRAIL, minScore = 0, timeStopBars = 0)
    fun run(ids: List<String>, settings: BacktestSettings) = Backtester(settings).run(ids.filter { it in tapes }.map { tapes.getValue(it) })
    val watch = Assets.DEFAULT.map { it.id }
    val watchPaxg = listOf("BTC", "ETH", "SOL", "GOLD-PAXG", "SILVER")
    val end = data.values.maxOf { it.h1.openTime.last() } + 3_600_000L

    for ((name, ids) in listOf("Watch list (gold & silver on futures)" to watch, "Watch list, gold history via PAXG" to watchPaxg)) {
        section(r, "$name: DEVELOPMENT (used to choose settings)", run(ids, s.copy(tradeFrom = DEV_START, tradeUntil = HOLDOUT_START)).trades,
            HOLDOUT_START - DEV_START, s, trend = true)
        section(r, "$name: HELD-OUT from ${day(HOLDOUT_START)} (never used for choosing)",
            run(ids, s.copy(tradeFrom = HOLDOUT_START)).trades, end - HOLDOUT_START, s, trend = true)
    }
    val full = run(watch, s.copy(tradeFrom = DEV_START))
    section(r, "Watch list: FULL PERIOD", full.trades, end - DEV_START, s, trend = true)
    return full.trades
}

// ------------------------------------------------------------------------------------------ version 3

private fun runEnsemble(data: Map<String, MarketData>, funding: Map<String, List<FundingEvent>>, r: Report, out: File) {
    val engine = EnsembleEngine()
    r.say("\nVersion 3: breakout ensemble, legs ${engine.cfg.legs} days, ${engine.cfg.regimeEma}-day EMA filter, midpoint stop, " +
        "longs and shorts. Costs 0.05% fee + 0.03% slippage per side, real funding.")
    val all = data.mapValues { (id, d) -> EnsembleBacktest(engine).run(d, funding.getValue(id)) }
    val end = data.values.maxOf { it.h1.openTime.last() } + 3_600_000L
    val watch = Assets.DEFAULT.map { it.id }
    val watchPaxg = listOf("BTC", "ETH", "SOL", "GOLD-PAXG", "SILVER")

    fun part(title: String, ids: List<String>, from: Long, until: Long) {
        val legs = ids.filter { it in all }.flatMap { all.getValue(it) }.filter { it.entryTime in from until until }
        val closed = legs.filter { !it.openAtEnd }
        val open = legs.filter { it.openAtEnd }
        val st = Stats.ofRows(closed.map { it.row() }, 0.005, until - from)
        r.say("\n== $title")
        if (st.trades == 0) { r.say("  No closed trades."); return }
        r.say("  Closed trades ${st.trades} (${f(st.tradesPerWeek)}/week), win rate ${p(st.winRate)}, avg ${f(st.avgR)}R, total ${f(st.totalR, 1)}R")
        r.say("  Profit factor ${f(st.profitFactor)}, max drawdown ${f(st.maxDrawdownR, 1)}R, longest losing streak ${st.longestLosingStreak}, " +
            "avg win ${f(st.avgWinR)}R, avg loss ${f(st.avgLossR)}R, avg hold ${f(st.avgHoldHours / 24, 1)} days")
        r.say("  At 0.5% risk per leg: ${p(st.compoundedReturn)} return, max drawdown ${p(st.maxDrawdownPct)}. Fees ${f(st.feesR, 1)}R, funding ${f(st.fundingR, 1)}R")
        if (open.isNotEmpty()) r.say("  Still open at the end: ${open.size} legs, unrealised ${f(open.sumOf { it.netR }, 1)}R (not counted above)")
        fun line(label: String, g: List<LegTrade>) {
            val x = Stats.ofRows(g.map { it.row() }, 0.005, until - from)
            r.say("    ${label.padEnd(12)} ${x.trades.toString().padStart(4)} trades, win ${p(x.winRate).padStart(6)}, avg ${f(x.avgR).padStart(6)}R, total ${f(x.totalR, 1).padStart(7)}R")
        }
        closed.groupBy { it.assetId }.toSortedMap().forEach { (k, g) -> line(k, g) }
        closed.groupBy { it.leg }.toSortedMap().forEach { (k, g) -> line("$k-day leg", g) }
        closed.groupBy { it.direction }.forEach { (k, g) -> line(k.name, g) }
        closed.groupBy { Instant.ofEpochMilli(it.entryTime).atZone(ZoneOffset.UTC).year }.toSortedMap().forEach { (k, g) -> line(k.toString(), g) }
        r.say("  Checklist:")
        st.checklist(minWinRate = null, minTrades = 30).forEach { (text, ok) -> r.say("    [${if (ok) "x" else " "}] $text") }
    }
    for ((name, ids) in listOf("Watch list (gold & silver on futures)" to watch, "Watch list, gold history via PAXG" to watchPaxg)) {
        part("$name: DEVELOPMENT", ids, DEV_START, HOLDOUT_START)
        part("$name: UNSEEN from ${day(HOLDOUT_START)}", ids, HOLDOUT_START, end)
        part("$name: FULL PERIOD", ids, DEV_START, end)
    }
    File(out, "trades.csv").printWriter().use { w ->
        w.println("asset,leg,direction,entry_time,exit_time,entry,exit,initial_stop,net_r,gross_r,fees_r,funding_r,open_at_end")
        for (t in all.values.flatten().filter { it.entryTime >= DEV_START }.sortedBy { it.entryTime }) {
            w.println(listOf(t.assetId, t.leg, t.direction, Instant.ofEpochMilli(t.entryTime), Instant.ofEpochMilli(t.exitTime), t.entryPrice,
                t.exitPrice, t.initialStop, f(t.netR, 4), f(t.grossR, 4), f(t.feesR, 4), f(t.fundingR, 4), t.openAtEnd).joinToString(","))
        }
    }
}

// ------------------------------------------------------------------------------------------ version 1

private fun runV1(data: Map<String, MarketData>, funding: Map<String, List<FundingEvent>>, r: Report): List<Trade> {
    val engine = SignalEngine(StrategyConfig())
    val tapes = data.mapValues { (id, d) -> AssetTape.build(engine, engine.prepare(d), funding.getValue(id)) }
    val firstJudged = tapes.mapValues { (_, tape) ->
        (0 until tape.h1.size).firstOrNull { i ->
            val e = engine.evaluate(tape.prepared, i, Direction.LONG)
            !(e is Rejected && e.gate == Gate.INSUFFICIENT_DATA)
        }?.let { tape.h1.openTime[it] } ?: Long.MAX_VALUE
    }
    val ids = Assets.DEFAULT.map { it.id }.filter { it in tapes }
    val start = firstJudged.getValue("BTC")
    val end = tapes.values.maxOf { it.h1.openTime.last() } + 3_600_000L
    val split = start + ((end - start) * 0.7).toLong()
    r.say("\nVersion 1 rules. Window ${day(start)} to ${day(end)}; settings chosen before ${day(split)} only.")
    fun run(s: BacktestSettings): BacktestResult = Backtester(s).run(ids.map { tapes.getValue(it) })

    val grid = listOf(70, 75, 80, 85).flatMap { score -> listOf(false, true).map { BacktestSettings(minScore = score, runnerExitAtTarget = it) } }
    var best: BacktestSettings? = null
    var bestTotal = Double.NEGATIVE_INFINITY
    for (g in grid) {
        val st = Stats.of(run(g.copy(tradeUntil = split)).trades, g.riskPerTrade, split - start)
        r.say("  min score ${g.minScore}, runner ${if (g.runnerExitAtTarget) "exits at target" else "trails"}: ${st.trades} trades, " +
            "avg ${f(st.avgR)}R, PF ${f(st.profitFactor)}")
        if (st.trades >= 30 && st.totalR > bestTotal) { bestTotal = st.totalR; best = g }
    }
    val chosen = best ?: BacktestSettings()
    section(r, "IN-SAMPLE", run(chosen.copy(tradeUntil = split)).trades, split - start, chosen, trend = false)
    section(r, "OUT-OF-SAMPLE", run(chosen.copy(tradeFrom = split)).trades, end - split, chosen, trend = false)
    val full = run(chosen)
    section(r, "FULL PERIOD", full.trades, end - start, chosen, trend = false)
    return full.trades
}

// ------------------------------------------------------------------------------------------ helpers

private class Report {
    private val sb = StringBuilder()
    fun say(s: String = "") { println(s); sb.append(s).append('\n') }
    fun text() = sb.toString()
}

private fun marketData(asset: Asset, h1: List<Candle>, btcH4: Series?) = MarketData(asset, Series(h1),
    Series(Resample.from1h(h1, Timeframe.H4)), Series(Resample.from1h(h1, Timeframe.D1)), btcH4, "archive")

private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
private fun f(v: Double, d: Int = 2) = String.format(Locale.US, "%.${d}f", v)
private fun p(v: Double) = String.format(Locale.US, "%.1f%%", v * 100)

private fun section(r: Report, title: String, trades: List<Trade>, periodMs: Long, s: BacktestSettings, trend: Boolean) {
    val st = Stats.of(trades, s.riskPerTrade, periodMs)
    r.say("\n== $title")
    if (st.trades == 0) { r.say("  No trades."); return }
    r.say("  Trades ${st.trades} (${f(st.tradesPerWeek)}/week), win rate ${p(st.winRate)}, avg ${f(st.avgR)}R per trade, total ${f(st.totalR, 1)}R")
    r.say("  Profit factor ${f(st.profitFactor)}, max drawdown ${f(st.maxDrawdownR, 1)}R, longest losing streak ${st.longestLosingStreak}")
    r.say("  Avg win ${f(st.avgWinR)}R, avg loss ${f(st.avgLossR)}R, avg hold ${f(st.avgHoldHours / 24, 1)} days, fees ${f(st.feesR, 1)}R, funding ${f(st.fundingR, 1)}R")
    r.say("  At ${p(s.riskPerTrade)} risk per trade: ${p(st.compoundedReturn)} return, max drawdown ${p(st.maxDrawdownPct)}")
    fun line(label: String, g: List<Trade>) {
        val x = Stats.of(g, s.riskPerTrade, periodMs)
        r.say("    ${label.padEnd(12)} ${x.trades.toString().padStart(4)} trades, win ${p(x.winRate).padStart(6)}, avg ${f(x.avgR).padStart(6)}R, total ${f(x.totalR, 1).padStart(6)}R")
    }
    trades.groupBy { it.assetId }.toSortedMap().forEach { (k, g) -> line(k, g) }
    trades.groupBy { it.direction }.forEach { (k, g) -> line(k.name, g) }
    trades.groupBy { Instant.ofEpochMilli(it.entryTime).atZone(ZoneOffset.UTC).year }.toSortedMap().forEach { (k, g) -> line(k.toString(), g) }
    if (!trend) for (setup in SetupType.entries) trades.filter { setup in it.signal.setups }.takeIf { it.isNotEmpty() }?.let { line(setup.name, it) }
    r.say("  Checklist:")
    val checks = if (trend) st.checklist(minWinRate = null, minTrades = 30) else st.checklist()
    checks.forEach { (text, ok) -> r.say("    [${if (ok) "x" else " "}] $text") }
}
