package com.signalbot.app.scan

import com.signalbot.app.data.ActionView
import com.signalbot.app.data.ChartLine
import com.signalbot.app.data.ChartMarker
import com.signalbot.app.data.ChartModel
import com.signalbot.app.data.LineStyle
import com.signalbot.app.data.MarkerKind
import com.signalbot.app.data.Segment
import com.signalbot.app.data.SetupTrade
import com.signalbot.app.data.SetupView
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe
import com.signalbot.engine.strategy.BrAction
import com.signalbot.engine.strategy.BrTrade
import com.signalbot.engine.strategy.BreakRetestConfig
import com.signalbot.engine.strategy.BreakRetestEngine
import com.signalbot.engine.strategy.BreakRetestReplay
import com.signalbot.engine.strategy.DailyTrend
import com.signalbot.engine.strategy.Direction
import com.signalbot.engine.strategy.TrendLine
import com.signalbot.engine.strategy.formatPrice
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns candles into the break-and-retest view: her three entry questions, what to do at the next open,
 * the trade in progress and the chart overlays. Pure (no Android, no network), so the screenshot tests
 * and the background scan use exactly the same code.
 */
/** Position size for an entry: the sentence shown, and the quantity to type in. */
data class Sizing(val text: String, val quantity: Double)

object SetupAnalysis {
    val CRITERIA = listOf("Price broke the trendline", "Price pulled back and retested it", "The candle closed and respected it")

    fun build(
        assetId: String,
        name: String,
        tf: Timeframe,
        s: Series,
        d1: Series?,
        cfg: BreakRetestConfig,
        useTrend: Boolean,
        sizing: (entry: Double, stop: Double) -> Sizing? = { _, _ -> null },
        window: Int = 160,
        stepActions: Boolean = false,
    ): SetupView {
        val (al, ash) = if (useTrend && d1 != null) DailyTrend.allow(d1, s) else null to null
        val r = BreakRetestEngine(cfg).replay(s, allowLong = al, allowShort = ash)
        val last = s.lastIndex
        val closeTime = s.openTime[last] + tf.millis

        // Things to act on, from the newest two candles (so a run that is a little late still reports them).
        val wanted = buildSet {
            addAll(listOf(BrAction.ENTRY, BrAction.STOP_MOVED, BrAction.EXIT_STOP, BrAction.EXIT_TARGET))
            if (stepActions) addAll(listOf(BrAction.BREAK, BrAction.RETEST))
        }
        // Stop moves and exits from the newest two candles (a late check still reports them); an entry only from the
        // newest, because "at the next open" would be wrong for an older one.
        val actions = r.events.filter { e ->
            e.action in wanted && (e.index == last || (e.index == last - 1 && e.action != BrAction.ENTRY && e.action != BrAction.BREAK && e.action != BrAction.RETEST))
        }.map { e ->
            val action = when (e.action) {
                BrAction.ENTRY -> "ENTER"
                BrAction.STOP_MOVED -> "MOVE_STOP"
                BrAction.EXIT_STOP, BrAction.EXIT_TARGET -> "EXIT"
                else -> e.action.name
            }
            val size = if (e.action == BrAction.ENTRY && e.stop != null) sizing(e.price, e.stop!!) else null
            ActionView(assetId, name, 0, action, e.direction.name, e.price, e.stop ?: Double.NaN,
                s.openTime[e.index] + tf.millis, listOfNotNull(e.text, e.line?.let { lineText(it, s, tf) }),
                size?.text, target = if (e.action == BrAction.ENTRY || e.action == BrAction.EXIT_TARGET) e.target else null,
                quantity = size?.quantity)
        }

        val open = r.open
        val trade = open?.let { t ->
            val entry = if (t.entryIndex <= last) s.open[t.entryIndex] else t.entryPrice
            val sign = if (t.direction == Direction.LONG) 1.0 else -1.0
            val risk = sign * (entry - t.initialStop)
            SetupTrade(t.direction.name, s.openTime[t.signalIndex] + tf.millis, entry, t.stops.last().second, t.initialStop, t.target,
                if (risk > 0) sign * (s.close[last] - entry) / risk else Double.NaN)
        }

        val entryNow = r.events.lastOrNull { it.action == BrAction.ENTRY && it.index == last }
        val waiting = r.sides.firstOrNull { it.broken != null }
        val (headline, met) = when {
            entryNow != null -> "${if (entryNow.direction == Direction.LONG) "Buy" else "Sell short"} at the next open. " +
                "All three checks passed." to 3
            trade != null -> "In a ${trade.direction.lowercase()} since ${date(trade.signalTimeMs, tf)}. " +
                "Stop ${formatPrice(trade.stop)}${trade.target?.let { ", target ${formatPrice(it)}" } ?: ""}." to 3
            waiting != null -> "Broke the ${if (waiting.direction == Direction.LONG) "down" else "up"}trend line. " +
                "Waiting for price to pull back and retest it." to 1
            else -> {
                val w = r.sides.mapNotNull { side -> side.watching?.let { side.direction to it.at(last + 1) } }
                if (w.isEmpty()) "No clean trendline yet. Waiting for new swings." to 0
                else "Watching " + w.joinToString(" and ") { (d, p) ->
                    "the ${if (d == Direction.LONG) "downtrend line near" else "uptrend line near"} ${formatPrice(p)}"
                } + ". No break yet." to 0
            }
        }
        val criteria = CRITERIA.mapIndexed { i, text -> text to (i < met) }

        return SetupView(assetId, name, tf.label, closeTime, s.close[last], headline, criteria, actions, trade,
            chart(r, tf, window), null)
    }

    /** The newest [window] candles with lines, levels, trades and stops mapped into the window's indexes. */
    fun chart(r: BreakRetestReplay, tf: Timeframe, window: Int): ChartModel {
        val s = r.series
        val w = minOf(window, s.size)
        val off = s.size - w
        val lastW = w - 1
        fun line(l: TrendLine, end: Int, style: LineStyle) = ChartLine(l.i1 - off, l.p1, l.i2 - off, l.p2, end, style)

        val lines = ArrayList<ChartLine>()
        val markers = ArrayList<ChartMarker>()
        val stops = ArrayList<Segment>()
        val targets = ArrayList<Segment>()
        for (t in r.trades) {
            if (t.signalIndex < off) continue
            lines += line(t.line, t.signalIndex - off, LineStyle.OLD)
            tradeMarks(t, s, off, lastW, markers, stops, targets)
        }
        for (side in r.sides) {
            side.watching?.let { lines += line(it, lastW + 4, if (it.kind == TrendLine.Kind.RESISTANCE) LineStyle.DOWNTREND else LineStyle.UPTREND) }
            side.broken?.let { b ->
                lines += line(b, lastW + 4, LineStyle.BROKEN)
                side.brokenAt?.let { at -> if (at >= off) markers += ChartMarker(at - off, s.close[at], MarkerKind.BREAK) }
            }
        }
        return ChartModel(
            tf.label,
            s.openTime.copyOfRange(off, s.size), s.open.copyOfRange(off, s.size), s.high.copyOfRange(off, s.size),
            s.low.copyOfRange(off, s.size), s.close.copyOfRange(off, s.size),
            lines, r.levels.map { it.price }, markers, stops, targets,
        )
    }

    private fun tradeMarks(t: BrTrade, s: Series, off: Int, lastW: Int, markers: MutableList<ChartMarker>,
                           stops: MutableList<Segment>, targets: MutableList<Segment>) {
        val long = t.direction == Direction.LONG
        val entryW = t.entryIndex - off
        val entryPx = if (t.entryIndex <= s.lastIndex) s.open[t.entryIndex] else t.entryPrice
        markers += ChartMarker(minOf(entryW, lastW + 1), entryPx, if (long) MarkerKind.BUY else MarkerKind.SELL)
        val endW = t.exitIndex?.let { it - off } ?: (lastW + 2)
        t.stops.forEachIndexed { k, (from, price) ->
            val to = t.stops.getOrNull(k + 1)?.first?.let { it - off - 1 } ?: endW
            stops += Segment(from - off, maxOf(from - off, to), price)
        }
        t.target?.let { targets += Segment(entryW, endW, it) }
        if (t.exitIndex != null && t.exitPrice != null) {
            val win = if (long) t.exitPrice!! > entryPx else t.exitPrice!! < entryPx
            markers += ChartMarker(t.exitIndex!! - off, t.exitPrice!!, if (win) MarkerKind.EXIT_WIN else MarkerKind.EXIT_LOSS)
        }
    }

    private fun lineText(l: TrendLine, s: Series, tf: Timeframe): String =
        "Line from ${date(s.openTime[l.i1], tf)} (${formatPrice(l.p1)}) through ${date(s.openTime[l.i2], tf)} (${formatPrice(l.p2)}), " +
            "${l.touches} touches"

    fun date(ms: Long, tf: Timeframe): String {
        val pattern = if (tf == Timeframe.D1) "d MMM" else "d MMM HH:mm"
        return DateTimeFormatter.ofPattern(pattern, Locale.UK).withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(ms)) +
            if (tf == Timeframe.D1) "" else " UTC"
    }
}
