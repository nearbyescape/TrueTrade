package com.signalbot.app.scan

import android.content.Context
import com.signalbot.app.data.ActionView
import com.signalbot.app.data.AppSettings
import com.signalbot.app.data.AssetView
import com.signalbot.app.data.OpenLegView
import com.signalbot.app.data.PaperStats
import com.signalbot.app.data.ScanResult
import com.signalbot.app.data.Store
import com.signalbot.engine.backtest.EnsembleBacktest
import com.signalbot.engine.model.Asset
import com.signalbot.engine.model.Assets
import com.signalbot.engine.model.Timeframe
import com.signalbot.engine.strategy.AssetDataLoader
import com.signalbot.engine.strategy.Direction
import com.signalbot.engine.strategy.EnsembleEngine
import com.signalbot.engine.strategy.LegAction
import com.signalbot.engine.strategy.PositionSizer
import java.util.Locale

/**
 * Runs the version 3 strategy (breakout ensemble) on every enabled asset, using the same engine
 * that was backtested. Blocking network calls: call from a background thread only.
 */
class Scanner(context: Context) {
    private val settings = AppSettings(context)
    private val store = Store(context)
    private val engine = EnsembleEngine()

    fun run(): ScanResult {
        val loader = AssetDataLoader()
        val assets = Assets.DEFAULT.filter { settings.isEnabled(it.id) }
        val result = ScanResult(System.currentTimeMillis(), assets.map { scan(loader, it) })
        store.saveScan(result)
        store.addToJournal(result.assets.flatMap { it.actions })
        return result
    }

    private fun scan(loader: AssetDataLoader, asset: Asset): AssetView = try {
        // 500 days covers the 200-day EMA warm-up plus the 90-day leg; 1H/4H are barely used here.
        val data = loader.load(asset, AssetDataLoader.Counts(h1 = 48, h4 = 12, d1 = 500))
        val d = data.d1
        val last = d.lastIndex
        val replay = engine.replay(data)

        val actions = replay.events.filter { it.dayIndex == last }.map { e ->
            val sizeText = if (e.action == LegAction.ENTER) {
                val s = PositionSizer.size(settings.capital, settings.riskPerLegPct / 100, e.close, e.stop, settings.leverage)
                "Size ${num(s.quantity)} units (${money(s.notional)}), margin ${money(s.margin)}, risk ${money(s.riskAmount)}" +
                    if (s.liquidationSafe) "" else ". Liquidation too close: use at most ${PositionSizer.maxSafeLeverage(e.close, e.stop)}x"
            } else null
            ActionView(asset.id, asset.displayName, e.leg, e.action.name, e.direction.name, e.close, e.stop, e.timeMs, e.reasons, sizeText)
        }

        val close = d.close[last]
        val open = replay.open.filter { it.entryDayIndex != last }.map { o ->
            val sign = if (o.direction == Direction.LONG) 1.0 else -1.0
            val risk = sign * (o.signalClose - o.initialStop)
            OpenLegView(asset.id, o.leg, o.direction.name, d.openTime[o.entryDayIndex] + Timeframe.D1.millis,
                o.signalClose, o.stop, if (risk > 0) sign * (close - o.signalClose) / risk else Double.NaN)
        }

        // Paper trading: the strategy's own closed legs over the last 180 days on this data.
        val since = d.openTime[last] - 180L * Timeframe.D1.millis
        val closed = EnsembleBacktest(engine).run(data, emptyList()).filter { !it.openAtEnd && it.exitTime >= since }
        val paper = PaperStats(180, closed.size,
            if (closed.isEmpty()) Double.NaN else closed.count { it.netR > 0 }.toDouble() / closed.size,
            if (closed.isEmpty()) Double.NaN else closed.map { it.netR }.average(),
            closed.sumOf { it.netR })

        val closes = d.close.copyOfRange(maxOf(0, d.size - 120), d.size).toList()
        AssetView(asset.id, asset.displayName, data.source, d.openTime[last] + Timeframe.D1.millis, close, actions, open, paper, null, closes)
    } catch (e: Exception) {
        AssetView(asset.id, asset.displayName, "", 0, Double.NaN, emptyList(), emptyList(), null, e.message ?: e.javaClass.simpleName)
    }

    private fun money(v: Double) = String.format(Locale.US, "%,.0f", v)
    private fun num(v: Double) = String.format(Locale.US, if (v >= 100) "%,.1f" else "%.4f", v)
}
