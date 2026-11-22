package com.signalbot.desk

import com.signalbot.engine.model.Asset
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Series
import com.signalbot.engine.strategy.BrAction
import com.signalbot.engine.strategy.BreakRetestEngine
import com.signalbot.engine.strategy.BreakRetestPresets
import com.signalbot.engine.strategy.DailyTrend
import com.signalbot.engine.strategy.Direction
import com.signalbot.engine.strategy.ExitMode
import com.signalbot.engine.strategy.PositionSizer
import com.signalbot.engine.strategy.StopMode
import com.signalbot.engine.strategy.TrendLine
import com.signalbot.engine.strategy.formatPrice
import com.signalbot.engine.strategy.timeframeName
import java.util.Locale

enum class LStyle { DOWN, UP, BROKEN, HTF_DOWN, HTF_UP, OLD, STOP }

/** A line on the chart in candle-index space of the view (x can be fractional or off-screen). */
data class DLine(val x1: Double, val p1: Double, val x2: Double, val p2: Double, val endX: Double, val style: LStyle, val tag: String? = null) {
    fun at(x: Double) = if (x2 == x1) p2 else p1 + (p2 - p1) * (x - x1) / (x2 - x1)
}

enum class MarkKind { BUY, SELL, WIN, LOSS, BREAK }

data class DMark(val x: Int, val price: Double, val kind: MarkKind)

data class DeskTrade(
    val direction: String, val sinceMs: Long, val entry: Double, val stop: Double, val initialStop: Double,
    val target: Double?, val rNow: Double, val quantity: Double?, val sizeText: String?,
)

/** Something to do (or that happened) at the newest closed candle. ENTER is the signal. */
data class DeskAction(
    val assetId: String, val name: String, val symbol: String, val tf: Tf, val action: String, val direction: String,
    val price: Double, val stop: Double?, val target: Double?, val quantity: Double?, val sizeText: String?,
    val text: String, val timeMs: Long,
) {
    val key: String get() = "$assetId|${tf.name}|$action|$direction|$timeMs"
}

data class DeskEvent(val timeMs: Long, val text: String, val color: String)

/** Everything one chart shows for one asset on one timeframe, from closed candles. */
class DeskView(
    val assetId: String,
    val name: String,
    val symbol: String,
    val tf: Tf,
    val time: LongArray,
    val open: DoubleArray,
    val high: DoubleArray,
    val low: DoubleArray,
    val close: DoubleArray,
    val lines: List<DLine>,
    val levels: List<Double>,
    val marks: List<DMark>,
    /** Stop steps of trades in view: each path is (candle index, stop) points. */
    val stopPaths: List<List<Pair<Int, Double>>>,
    val targets: List<Triple<Int, Int, Double>>,
    val checks: List<Boolean>,
    val headline: String,
    val trade: DeskTrade?,
    val actions: List<DeskAction>,
    val recent: List<DeskEvent>,
) {
    val size: Int get() = close.size
    val lastClose: Double get() = close.lastOrNull() ?: Double.NaN
}

object DeskAnalysis {
    val CHECKS = listOf("Price broke the trendline", "Pulled back and retested it", "Candle closed and respected it")

    /**
     * Runs her full method (top-down lines, retest, close, stop on the opposing line) on [closed] candles of
     * [tf]. [higher] holds closed candles of the higher timeframes; [daily] feeds the trend filter.
     */
    fun build(
        asset: Asset,
        tf: Tf,
        closed: List<Candle>,
        higher: Map<Tf, List<Candle>>,
        daily: List<Candle>?,
        s: DeskSettings,
        keep: Int = 700,
    ): DeskView {
        val symbol = asset.routes.first().symbol
        val series = Series(closed)
        val cfg = BreakRetestPresets.forTimeframe(tf.engine)
            .copy(stop = StopMode.LINE, exit = if (s.takeProfit) ExitMode.TARGET else ExitMode.TRAIL)
        val hi = BreakRetestPresets.higherOf(tf.engine).mapNotNull { h ->
            higher[Tf.of(h)]?.takeIf { it.size > 50 }?.let { Series(it) to BreakRetestPresets.forTimeframe(h) }
        }
        val (al, ash) = if (s.trendFilter && daily != null && daily.size > 60) DailyTrend.allow(Series(daily), series) else null to null
        val r = BreakRetestEngine(cfg).replay(series, allowLong = al, allowShort = ash, higher = hi)
        val last = series.lastIndex
        val tfMs = tf.engine.millis

        fun sizing(entry: Double, stop: Double): Pair<String, Double> {
            val z = PositionSizer.size(s.capital, s.riskPct / 100, entry, stop, s.leverage)
            return "Size ${qty(z.quantity)} units (${money(z.notional)}), margin ${money(z.margin)}, risk ${money(z.riskAmount)}" +
                (if (z.liquidationSafe) "" else ". Liquidation too close: use at most ${PositionSizer.maxSafeLeverage(entry, stop)}x") to z.quantity
        }

        val wanted = buildSet {
            addAll(listOf(BrAction.ENTRY, BrAction.STOP_MOVED, BrAction.EXIT_STOP, BrAction.EXIT_TARGET))
            if (s.stepAlerts) addAll(listOf(BrAction.BREAK, BrAction.RETEST))
        }
        val actions = r.events.filter { it.index == last && it.action in wanted }.map { e ->
            val size = if (e.action == BrAction.ENTRY && e.stop != null) sizing(e.price, e.stop!!) else null
            DeskAction(asset.id, asset.displayName, symbol, tf,
                when (e.action) { BrAction.ENTRY -> "ENTER"; BrAction.STOP_MOVED -> "MOVE_STOP"; BrAction.EXIT_STOP, BrAction.EXIT_TARGET -> "EXIT"; else -> e.action.name },
                e.direction.name, e.price, e.stop, e.target, size?.second, size?.first, e.text, series.openTime[e.index] + tfMs)
        }

        val open = r.open
        val trade = open?.let { t ->
            val entry = if (t.entryIndex <= last) series.open[t.entryIndex] else t.entryPrice
            val sign = if (t.direction == Direction.LONG) 1.0 else -1.0
            val risk = sign * (entry - t.initialStop)
            val z = sizing(entry, t.initialStop)
            DeskTrade(t.direction.name, series.openTime[t.signalIndex] + tfMs, entry, t.stops.last().second, t.initialStop, t.target,
                if (risk > 0) sign * (series.close[last] - entry) / risk else Double.NaN, z.second, z.first)
        }

        val entryNow = r.events.lastOrNull { it.action == BrAction.ENTRY && it.index == last }
        val waiting = r.sides.firstOrNull { it.broken != null }
        val (headline, met) = when {
            entryNow != null -> "${if (entryNow.direction == Direction.LONG) "Buy" else "Sell short"} at the next open. All three checks passed." to 3
            trade != null -> "In a ${trade.direction.lowercase()} from ${formatPrice(trade.entry)}. Stop ${formatPrice(trade.stop)}" +
                (trade.target?.let { ", target ${formatPrice(it)}" } ?: ", riding the opposing line") + "." to 3
            waiting != null -> "Broke the ${waiting.broken?.let { if (it.sourceMs != 0L) timeframeName(it.sourceMs) + " " else "" } ?: ""}" +
                "${if (waiting.direction == Direction.LONG) "down" else "up"}trend line. Waiting for the retest." to 1
            else -> "Watching the lines. No break yet." to 0
        }

        // ---- chart, newest [keep] candles
        val w = minOf(keep, series.size)
        val off = series.size - w
        fun dl(l: TrendLine, end: Double, style: LStyle, tag: String? = null) = DLine(l.x1 - off, l.p1, l.x2 - off, l.p2, end - off, style, tag)
        val lines = ArrayList<DLine>()
        val marks = ArrayList<DMark>()
        val stopPaths = ArrayList<List<Pair<Int, Double>>>()
        val targets = ArrayList<Triple<Int, Int, Double>>()
        val farRight = last + 6.0
        for (side in r.sides) {
            val up = side.direction == Direction.SHORT // shorts watch support (uptrend) lines
            side.higher.forEach { l ->
                lines += dl(l, farRight, if (up) LStyle.HTF_UP else LStyle.HTF_DOWN, if (l.sourceMs != 0L) shortTf(l.sourceMs) else null)
            }
            side.watching?.let { lines += dl(it, farRight, if (up) LStyle.UP else LStyle.DOWN) }
            side.broken?.let { b ->
                lines += dl(b, farRight, LStyle.BROKEN, if (b.sourceMs != 0L) shortTf(b.sourceMs) else null)
                side.brokenAt?.let { at -> if (at >= off) marks += DMark(at - off, series.close[at], MarkKind.BREAK) }
            }
        }
        for (t in r.trades) {
            if (t.signalIndex < off) continue
            lines += dl(t.line, t.signalIndex.toDouble(), LStyle.OLD)
            val long = t.direction == Direction.LONG
            val entryPx = if (t.entryIndex <= last) series.open[t.entryIndex] else t.entryPrice
            marks += DMark(t.entryIndex - off, entryPx, if (long) MarkKind.BUY else MarkKind.SELL)
            val endIdx = t.exitIndex ?: (last + 1)
            val pts = t.stops.map { (i, p) -> (i - off) to p }.toMutableList()
            if (pts.isNotEmpty()) pts += (endIdx - off) to pts.last().second
            stopPaths += pts
            t.target?.let { targets += Triple(t.entryIndex - off, endIdx - off, it) }
            if (t.exitIndex != null && t.exitPrice != null) {
                val win = if (long) t.exitPrice!! > entryPx else t.exitPrice!! < entryPx
                marks += DMark(t.exitIndex!! - off, t.exitPrice!!, if (win) MarkKind.WIN else MarkKind.LOSS)
            }
            if (t.exitIndex == null) t.stopLine?.let { lines += dl(it, farRight, LStyle.STOP) }
        }

        val recent = r.events.filter { it.action != BrAction.STOP_MOVED || it.index >= last - 50 }.takeLast(8).asReversed().map { e ->
            DeskEvent(series.openTime[e.index] + tfMs, e.text, when (e.action) {
                BrAction.ENTRY -> "signal"; BrAction.EXIT_STOP, BrAction.CANCELLED -> "bad"; BrAction.EXIT_TARGET -> "good"; else -> "plain"
            })
        }

        return DeskView(
            asset.id, asset.displayName, symbol, tf,
            series.openTime.copyOfRange(off, series.size), series.open.copyOfRange(off, series.size),
            series.high.copyOfRange(off, series.size), series.low.copyOfRange(off, series.size), series.close.copyOfRange(off, series.size),
            lines, r.levels.map { it.price }, marks, stopPaths, targets,
            DeskAnalysis.CHECKS.indices.map { it < met }, headline, trade, actions, recent,
        )
    }

    private fun shortTf(ms: Long) = Tf.entries.firstOrNull { it.engine.millis == ms }?.label
    fun money(v: Double) = String.format(Locale.US, "%,.0f", v)
    fun qty(q: Double) = String.format(Locale.US, if (q >= 100) "%.1f" else if (q >= 1) "%.3f" else "%.4f", q)

    /** Plain number for exchange order forms: no thousands commas. */
    fun plain(p: Double): String {
        val a = kotlin.math.abs(p)
        val d = when { a >= 1000 -> 1; a >= 10 -> 2; a >= 1 -> 4; else -> 5 }
        return String.format(Locale.US, "%.${d}f", p)
    }
}
