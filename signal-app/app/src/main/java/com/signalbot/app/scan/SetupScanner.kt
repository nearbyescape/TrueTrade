package com.signalbot.app.scan

import android.content.Context
import com.signalbot.app.data.AppSettings
import com.signalbot.app.data.SetupResult
import com.signalbot.app.data.SetupTest
import com.signalbot.app.data.SetupTestRow
import com.signalbot.app.data.SetupView
import com.signalbot.app.data.Store
import com.signalbot.engine.backtest.BreakRetestBacktest
import com.signalbot.engine.backtest.BrResult
import com.signalbot.engine.backtest.Stats
import com.signalbot.engine.data.BinanceFunding
import com.signalbot.engine.data.CandleLoader
import com.signalbot.engine.data.MarketDataException
import com.signalbot.engine.data.RetryPolicy
import com.signalbot.engine.data.UrlConnectionHttp
import com.signalbot.engine.history.FundingEvent
import com.signalbot.engine.model.Asset
import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe
import com.signalbot.engine.strategy.BreakRetestConfig
import com.signalbot.engine.strategy.BreakRetestEngine
import com.signalbot.engine.strategy.BreakRetestPresets
import com.signalbot.engine.strategy.DailyTrend
import com.signalbot.engine.strategy.ExitMode
import com.signalbot.engine.strategy.PositionSizer
import java.util.Locale

/**
 * Her break-and-retest setup on live futures candles. Blocking network calls: background threads only.
 */
class SetupScanner(context: Context) {
    private val settings = AppSettings(context)
    private val store = Store(context)
    private val http = UrlConnectionHttp()
    private val retry = RetryPolicy()

    val timeframe: Timeframe get() = Timeframe.parse(settings.setupTimeframe)

    fun config(tf: Timeframe): BreakRetestConfig = BreakRetestPresets.forTimeframe(tf)
        .copy(exit = if (settings.setupTarget) ExitMode.TARGET else ExitMode.TRAIL)

    /** Scans every enabled asset on the chosen timeframe, saves the result and journals entries and exits. */
    fun run(): SetupResult {
        val tf = timeframe
        val views = Assets.DEFAULT.filter { settings.isEnabled(it.id) }.map { analyze(it, tf, stepActions = settings.setupStepAlerts) }
        val result = SetupResult(System.currentTimeMillis(), tf.label, views)
        store.saveSetup(result)
        store.addToJournal(views.flatMap { v -> v.actions.filter { it.action != "BREAK" && it.action != "RETEST" } })
        return result
    }

    /** One asset on one timeframe, for the scan and for the chart screen. */
    fun analyze(asset: Asset, tf: Timeframe, stepActions: Boolean = false): SetupView = try {
        val (s, d1) = load(asset, tf, if (tf == Timeframe.D1) 600 else 1000)
        SetupAnalysis.build(asset.id, asset.displayName, tf, s, d1, config(tf), settings.setupTrend, ::sizeText,
            window = if (tf == Timeframe.D1) 150 else 180, stepActions = stepActions)
    } catch (e: Exception) {
        SetupView(asset.id, asset.displayName, tf.label, 0, Double.NaN, "Couldn't load prices: ${e.message}", emptyList(),
            emptyList(), null, null, e.message ?: e.javaClass.simpleName)
    }

    private fun sizeText(entry: Double, stop: Double): Sizing {
        val s = PositionSizer.size(settings.capital, settings.setupRiskPct / 100, entry, stop, settings.leverage)
        return Sizing("Size ${num(s.quantity)} units (${money(s.notional)}), margin ${money(s.margin)}, risk ${money(s.riskAmount)}" +
            if (s.liquidationSafe) "" else ". Liquidation too close: use at most ${PositionSizer.maxSafeLeverage(entry, stop)}x",
            s.quantity)
    }

    /** Closed candles of [tf] and daily candles (for the trend filter), from the first route that works. */
    fun load(asset: Asset, tf: Timeframe, count: Int, dailyCount: Int = 400): Pair<Series, Series> {
        val errors = ArrayList<String>()
        for (route in asset.routes) {
            try {
                val loader = CandleLoader(route.source(http, retry))
                val main = loader.closed(route.symbol, tf, count)
                val d1 = if (tf == Timeframe.D1) main else loader.closed(route.symbol, Timeframe.D1, dailyCount)
                require(main.size >= 60) { "only ${main.size} candles" }
                return Series(main) to Series(d1)
            } catch (e: Exception) {
                errors += "$route: ${e.message}"
            }
        }
        throw MarketDataException(errors.joinToString("; "), retryable = true)
    }

    // ------------------------------------------------------------------------------------- phone backtest

    /**
     * Downloads history and backtests the setup on this phone, with the same code the scan uses.
     * Spans: daily 4 years, 4-hour 2 years, 1-hour 1 year. Costs 0.05% fee + 0.03% slippage per side, plus
     * Binance funding when it can be downloaded.
     */
    fun backtest(progress: (String) -> Unit): SetupTest {
        val tf = timeframe
        val count = when (tf) { Timeframe.D1 -> 1460; Timeframe.H4 -> 4380; else -> 8760 }
        val cfg = config(tf)
        val notes = ArrayList<String>()
        val all = ArrayList<BrResult>()
        var from = Long.MAX_VALUE
        var to = 0L
        val rows = ArrayList<SetupTestRow>()
        for (asset in Assets.DEFAULT.filter { settings.isEnabled(it.id) }) {
            progress("Downloading ${asset.displayName} history")
            val (s, d1) = try {
                load(asset, tf, count, dailyCount = if (tf == Timeframe.D1) count else count / (if (tf == Timeframe.H4) 6 else 24) + 120)
            } catch (e: Exception) {
                notes += "${asset.displayName}: couldn't download (${e.message})"; continue
            }
            val funding: List<FundingEvent> = try {
                BinanceFunding(http).since(asset.routes.first().symbol, s.openTime.first())
            } catch (e: Exception) {
                notes += "${asset.displayName}: funding not included"; emptyList()
            }
            progress("Testing ${asset.displayName} (${s.size} candles)")
            val (al, ash) = if (settings.setupTrend) DailyTrend.allow(d1, s) else null to null
            val replay = BreakRetestEngine(cfg).replay(s, allowLong = al, allowShort = ash)
            val res = BreakRetestBacktest.price(asset.id, replay, funding).filter { !it.openAtEnd }
            all += res
            from = minOf(from, s.openTime.first()); to = maxOf(to, s.openTime.last() + tf.millis)
            if (s.size < count * 0.9) notes += "${asset.displayName}: only ${s.size} candles of history on Binance futures"
            rows += row(asset.displayName, res)
        }
        if (all.isEmpty() && rows.isEmpty()) throw MarketDataException("No history could be downloaded", retryable = true)
        val st = Stats.ofRows(all.map { it.row() }, settings.setupRiskPct / 100)
        val test = SetupTest(
            System.currentTimeMillis(), tf.label,
            "${tf.label} candles, ${if (settings.setupTarget) "take profit at support/resistance" else "trailing stop"}, " +
                if (settings.setupTrend) "daily trend filter on" else "no trend filter",
            if (from == Long.MAX_VALUE) 0 else from, to, row("All assets", all), st.profitFactor, st.maxDrawdownR, rows, notes,
        )
        store.saveSetupTest(test)
        return test
    }

    private fun row(label: String, r: List<BrResult>) = SetupTestRow(label, r.size,
        if (r.isEmpty()) Double.NaN else r.count { it.netR > 0 }.toDouble() / r.size,
        if (r.isEmpty()) Double.NaN else r.map { it.netR }.average(), r.sumOf { it.netR })

    private fun money(v: Double) = String.format(Locale.US, "%,.0f", v)
    private fun num(v: Double) = String.format(Locale.US, if (v >= 100) "%,.1f" else "%.4f", v)
}
