package com.signalbot.engine.strategy

import com.signalbot.engine.indicators.Indicators
import com.signalbot.engine.indicators.SwingPoint
import com.signalbot.engine.indicators.Swings
import com.signalbot.engine.model.Series
import com.signalbot.engine.model.Timeframe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The trendline BREAK AND RETEST setup, written as exact rules so a computer can follow it.
 *
 * Lines (her "top-down" markup, done by code):
 *  - A resistance line joins two swing highs A then B (B no higher than A). A support line joins two
 *    swing lows (B no lower than A). Swings need [BreakRetestConfig.pivot] candles on each side.
 *  - No candle may cut through the line between A and B. After B, no candle may CLOSE through it, and
 *    wicks may poke through by at most the touch tolerance ("wiggle room").
 *  - Of the valid lines ending on one of the newest swings, keep the one with the most touches,
 *    then the newest, then the longest.
 *  - Top-down: lines drawn the same way on HIGHER timeframes are carried down and watched too, as she
 *    marks up monthly, daily and 4-hour before trading the 1-hour.
 *
 * Entry, all three must happen, in order:
 *  1. BREAK: a candle closes through a watched line.
 *  2. RETEST: price moves away, then comes back and touches the line from the other side.
 *  3. CLOSE: that retest candle closes still on the new side of the line. This is THE signal:
 *     enter at the next candle's open. A close back through the line before this cancels the setup.
 *
 * Risk and exit:
 *  - [StopMode.LINE] (hers): the stop sits on the OPPOSING trendline, drawn from the last swing low
 *    before the retest through the retest low (for a long), and rises with it every candle. Each new
 *    higher swing low redraws it steeper through that swing ("each point B becomes the new point A").
 *  - [StopMode.SWING]: a flat stop beyond the retest swing, moved beyond each new swing.
 *  - Exit when the stop is hit, or, with [ExitMode.TARGET], at the nearest support/resistance level,
 *    set a little before the level so it gets filled.
 *
 * Shorts are the same rules on the price-flipped chart, so both directions are treated identically.
 */
data class BreakRetestConfig(
    val pivot: Int = 5,
    val trailPivot: Int = 3,
    val maxLineBars: Int = 400,
    val newestAnchors: Int = 3,
    val touchAtr: Double = 0.25,
    val awayAtr: Double = 0.5,
    val retestBars: Int = 48,
    val stopBufferAtr: Double = 0.2,
    val exit: ExitMode = ExitMode.TRAIL,
    /** With TARGET: skip trades whose target is closer than this many R. 0 = take every trade. */
    val minTargetR: Double = 0.0,
    val targetBufferAtr: Double = 0.1,
    /** Skip entries whose stop is closer than this fraction of price: costs would swamp them. */
    val minRiskFraction: Double = 0.002,
    val atrLength: Int = 14,
    /** Only watch lines with at least this many swing touches (2 = any two swings). */
    val minTouches: Int = 2,
    val stop: StopMode = StopMode.SWING,
) {
    init {
        require(pivot >= 2 && trailPivot >= 1 && retestBars >= 1 && newestAnchors >= 1)
    }
}

enum class ExitMode { TRAIL, TARGET }

enum class StopMode { SWING, LINE }

/**
 * A straight line through two candles, in real (not flipped) prices. [i1]/[i2] are candle indexes on the
 * chart it is used on (rounded for lines drawn on a higher timeframe); [t1]/[t2] are the anchor candles'
 * open times, and [sourceMs] the timeframe the line was drawn on (0 = the chart's own).
 */
data class TrendLine(
    val i1: Int, val p1: Double, val i2: Int, val p2: Double, val touches: Int, val kind: Kind,
    val x1: Double = i1.toDouble(), val x2: Double = i2.toDouble(),
    val t1: Long = 0, val t2: Long = 0, val sourceMs: Long = 0,
) {
    enum class Kind { RESISTANCE, SUPPORT }
    fun at(i: Int): Double = at(i.toDouble())
    fun at(x: Double): Double = p1 + (p2 - p1) * (x - x1) / (x2 - x1)
    /** Value at an epoch-millisecond time (needs [t1] != [t2]). */
    fun atTime(ms: Long): Double = p1 + (p2 - p1) * (ms - t1).toDouble() / (t2 - t1).toDouble()
    internal fun flipped() = copy(p1 = -p1, p2 = -p2, kind = if (kind == Kind.RESISTANCE) Kind.SUPPORT else Kind.RESISTANCE)
}

enum class BrAction { BREAK, RETEST, CANCELLED, ENTRY, STOP_MOVED, EXIT_STOP, EXIT_TARGET }

/** Something the rules noticed at the close of candle [index]. ENTRY is the trade signal. */
data class BrEvent(
    val action: BrAction,
    val direction: Direction,
    val index: Int,
    val timeMs: Long,
    val price: Double,
    val line: TrendLine?,
    val stop: Double? = null,
    val target: Double? = null,
    val text: String,
)

/** One trade: signal at candle [signalIndex] close, filled at [entryIndex] open. */
data class BrTrade(
    val direction: Direction,
    val line: TrendLine,
    val breakIndex: Int,
    val signalIndex: Int,
    val entryIndex: Int,
    val entryPrice: Double,
    val initialStop: Double,
    val target: Double?,
    /** Stops as they trailed: (candle index from which it applies, price). */
    val stops: List<Pair<Int, Double>>,
    val exitIndex: Int?,
    val exitPrice: Double?,
    val exitReason: BrAction?,
    /** With [StopMode.LINE]: the opposing line the stop follows (the latest one). */
    val stopLine: TrendLine? = null,
)

/** A horizontal support/resistance level and how many swings touched it. */
data class Level(val price: Double, val touches: Int)

/** Where a setup stands at the last candle, for drawing and for the "waiting for" text. */
data class BrSide(
    val direction: Direction,
    /** The line being watched for a break on this chart (resistance for longs, support for shorts). */
    val watching: TrendLine?,
    /** A broken line waiting for its retest, and the candle that broke it. */
    val broken: TrendLine?,
    val brokenAt: Int?,
    /** Lines from higher timeframes being watched too (top-down). */
    val higher: List<TrendLine> = emptyList(),
)

class BreakRetestReplay(
    val series: Series,
    val events: List<BrEvent>,
    val trades: List<BrTrade>,
    val sides: List<BrSide>,
    val levels: List<Level>,
) {
    val open: BrTrade? get() = trades.lastOrNull()?.takeIf { it.exitIndex == null }
}

/** "daily", "4-hour", "1-hour", "15-minute", "5-minute" for a timeframe length. */
fun timeframeName(ms: Long): String = when (ms) {
    Timeframe.D1.millis -> "daily"
    Timeframe.H4.millis -> "4-hour"
    Timeframe.H1.millis -> "1-hour"
    Timeframe.M15.millis -> "15-minute"
    Timeframe.M5.millis -> "5-minute"
    else -> "${ms / 60_000}-minute"
}

class BreakRetestEngine(val cfg: BreakRetestConfig = BreakRetestConfig()) {

    /**
     * Replays the rules over [s] up to and including candle [last]. Deterministic and free of
     * look-ahead: swings are used only once confirmed, every decision is taken at a candle's close,
     * higher-timeframe lines only from higher candles already closed, and orders fill at the next open.
     * One trade at a time per market.
     *
     * [allowLong] / [allowShort], when given, say per candle whether that side may enter (a
     * higher-timeframe trend filter). Null = always allowed.
     * [higher]: closed candles of higher timeframes whose lines are watched too (top-down), each with
     * the settings to draw its lines with.
     */
    fun replay(
        s: Series,
        last: Int = s.lastIndex,
        allowLong: BooleanArray? = null,
        allowShort: BooleanArray? = null,
        higher: List<Pair<Series, BreakRetestConfig>> = emptyList(),
    ): BreakRetestReplay {
        val longSide = Side(s, Direction.LONG, allowLong, higher.map { it.first to it.second })
        val shortSide = Side(s.mirrored(), Direction.SHORT, allowShort, higher.map { it.first.mirrored() to it.second })
        val atr = longSide.atr
        val events = ArrayList<BrEvent>()
        val trades = ArrayList<BrTrade>()
        var pos: Position? = null
        val end = min(last, s.lastIndex)

        for (t in 0..end) {
            // 1. An open trade first meets this candle's prices (stop / target set at earlier closes).
            pos?.let { p ->
                val side = if (p.dir == Direction.LONG) longSide else shortSide
                val hit = side.checkExit(p, t)
                if (hit != null) {
                    val (price, reason) = hit
                    trades += side.toTrade(p, t, price, reason)
                    events += BrEvent(reason, p.dir, t, s.openTime[t], price, p.line, p.stop, p.target,
                        if (reason == BrAction.EXIT_TARGET) "Target ${formatPrice(price)} reached at support/resistance"
                        else "Stop ${formatPrice(price)} hit: price broke the opposing line")
                    pos = null
                }
            }
            if (atr[t].isNaN()) continue
            // 2. At this candle's close: trail the stop, then look for new setups.
            pos?.let { p ->
                val side = if (p.dir == Direction.LONG) longSide else shortSide
                side.trail(p, t)?.let { (newStop, text) ->
                    events += BrEvent(BrAction.STOP_MOVED, p.dir, t, s.openTime[t], s.close[t], p.line, newStop, p.target, text)
                }
            }
            val signals = listOf(longSide, shortSide).mapNotNull { side -> side.step(t, events, flat = pos == null) }
            // Two opposite signals on one candle cancel out. A signal on the newest candle fills at the next open.
            if (pos == null && signals.size == 1) pos = signals.single()
        }
        pos?.let { p -> trades += (if (p.dir == Direction.LONG) longSide else shortSide).toTrade(p, null, null, null) }
        val sides = listOf(longSide.state(end), shortSide.state(end))
        return BreakRetestReplay(s, events, trades, sides, levels(s, end, atr))
    }

    // --------------------------------------------------------------------------------- shared pieces

    /** A line in the index space of the series it was drawn on (possibly flipped). */
    private class TLine(val i1: Int, val p1: Double, val i2: Int, val p2: Double, val touches: Int) {
        fun at(i: Int) = p1 + (p2 - p1) * (i - i1).toDouble() / (i2 - i1).toDouble()
    }

    /**
     * Draws resistance lines on [x] (flip the chart for support) the way she does, and keeps the one to
     * watch as candles close. [dropOnClose]: forget a line once a candle of [x] closes through it.
     */
    private inner class Tracker(val x: Series, val c: BreakRetestConfig, val dropOnClose: Boolean) {
        val atr = Indicators.atr(x.high, x.low, x.close, c.atrLength)
        val highs: List<SwingPoint> = Swings.highs(x.high, c.pivot, c.pivot)
        private val confirmAt = HashMap<Int, Int>().apply { highs.forEachIndexed { k, p -> put(p.confirmedAt, k) } }
        var watching: TLine? = null
        private var redraw = true

        /** Updates the watched line at the close of candle [t] and returns it. */
        fun update(t: Int): TLine? {
            if (atr[t].isNaN()) return null
            // Lines only change when a new swing confirms or the current line was dropped.
            if (redraw || confirmAt.containsKey(t)) { bestLine(t)?.let { watching = it }; redraw = false }
            watching?.let { w ->
                val lv = w.at(t)
                when {
                    t - w.i1 > c.maxLineBars * 2 -> { watching = null; redraw = true }
                    dropOnClose && x.close[t] > lv -> { watching = null; redraw = true }
                    x.high[t] > lv + c.touchAtr * atr[t] && x.close[t] <= lv -> { watching = null; redraw = true } // pierced, not closed
                }
            }
            return watching
        }

        private fun bestLine(t: Int): TLine? {
            val last = Swings.lastConfirmedPosition(highs, t)
            if (last < 1) return null
            var best: TLine? = null
            for (kb in last downTo max(1, last - c.newestAnchors + 1)) {
                val b = highs[kb]
                var ka = kb - 1
                while (ka >= 0 && b.index - highs[ka].index <= c.maxLineBars) {
                    val a = highs[ka]
                    ka--
                    if (a.price < b.price || b.index - a.index < 2 * c.pivot) continue
                    val touches = validTouches(TLine(a.index, a.price, b.index, b.price, 0), t, kb) ?: continue
                    if (touches < c.minTouches) continue
                    val cand = TLine(a.index, a.price, b.index, b.price, touches)
                    if (best == null || better(cand, best)) best = cand
                }
            }
            return best
        }

        private fun better(a: TLine, b: TLine) = when {
            a.touches != b.touches -> a.touches > b.touches
            a.i2 != b.i2 -> a.i2 > b.i2
            else -> a.i1 < b.i1
        }

        /** Touch count if [l] is still a valid, unbroken line at the close of candle t-1, else null. */
        private fun validTouches(l: TLine, t: Int, kb: Int): Int? {
            val eps = 1e-9 * abs(l.p1)
            for (i in l.i1..l.i2) if (x.high[i] > l.at(i) + eps) return null
            for (i in l.i2 + 1 until t) {
                val lv = l.at(i)
                if (x.close[i] > lv) return null
                if (!atr[i].isNaN() && x.high[i] > lv + c.touchAtr * atr[i]) return null
            }
            var touches = 0
            val a = atr.getOrNull(t)?.takeIf { !it.isNaN() } ?: return 2
            for (k in 0..kb) {
                val h = highs[k]
                if (h.index < l.i1) continue
                if (abs(h.price - l.at(h.index)) <= c.touchAtr * a) touches++
            }
            return max(touches, 2)
        }

        /** Nearest resistance above [above]: swing highs (confirmed by t) clustered within the touch tolerance, 2+ touches. */
        fun nearestLevelAbove(t: Int, above: Double): Double? {
            val last = Swings.lastConfirmedPosition(highs, t)
            if (last < 0) return null
            val tol = c.touchAtr * atr[t]
            val cands = (0..last).map { highs[it] }.filter { it.price > above && t - it.index <= c.maxLineBars * 2 }.sortedBy { it.price }
            for (h in cands) {
                val near = cands.filter { abs(it.price - h.price) <= tol }
                if (near.size >= 2) return near.maxOf { it.price }
            }
            return null
        }
    }

    /** The opposing line a LINE stop follows, in the side's (possibly flipped) index space. */
    private class Opp(val i1: Int, val p1: Double, val i2: Int, val p2: Double) {
        fun at(i: Int) = if (i2 == i1) p2 else p1 + (p2 - p1) * (i - i1).toDouble() / (i2 - i1).toDouble()
    }

    /** Position in the side's space: prices REAL in [stop]/[initialStop]/[target]; [opp] and [buf] flipped. */
    private class Position(
        val dir: Direction,
        val line: TrendLine,
        val breakIndex: Int,
        val signalIndex: Int,
        val entryIndex: Int,
        val signalClose: Double,
        var stop: Double,
        val initialStop: Double,
        val target: Double?,
        val stops: MutableList<Pair<Int, Double>>,
        var opp: Opp?,
        val buf: Double,
    )

    // --------------------------------------------------------------------------------- one direction

    /**
     * Rules for LONG trades on [x]. For shorts [x] is the flipped chart (price p becomes -p), so the
     * same code finds them; prices are flipped back whenever they leave this class.
     */
    private inner class Side(val x: Series, val dir: Direction, val allowed: BooleanArray?, higherX: List<Pair<Series, BreakRetestConfig>>) {
        val flip = if (dir == Direction.LONG) 1.0 else -1.0
        val own = Tracker(x, cfg, dropOnClose = false)
        val atr = own.atr
        val trailLows: List<SwingPoint> = Swings.lows(x.low, cfg.trailPivot, cfg.trailPivot)
        val tfMs = if (x.size > 1) x.openTime[1] - x.openTime[0] else Timeframe.D1.millis

        /** Each higher timeframe: its candles and the line it was watching after each of its closes. */
        private inner class Higher(val h: Series, val ms: Long, val lines: Array<TLine?>)
        private val higher: List<Higher> = higherX.filter { it.first.size > 1 }.map { (h, c) ->
            val tr = Tracker(h, c, dropOnClose = true)
            Higher(h, h.openTime[1] - h.openTime[0], Array(h.size) { k -> tr.update(k) })
        }.sortedByDescending { it.ms }
        private val higherIdx = IntArray(higher.size) { -1 }

        /** A line in this chart's time: anchors as open times, so lines from any timeframe line up. */
        inner class Cand(val t1: Long, val p1: Double, val t2: Long, val p2: Double, val touches: Int, val sourceMs: Long) {
            val key = "$sourceMs|$t1|$t2"
            fun atTime(ms: Long) = p1 + (p2 - p1) * (ms - t1).toDouble() / (t2 - t1).toDouble()
            fun at(t: Int) = atTime(x.openTime[t])
            fun real(): TrendLine {
                val x1 = (t1 - x.openTime[0]).toDouble() / tfMs
                val x2 = (t2 - x.openTime[0]).toDouble() / tfMs
                val l = TrendLine(Math.round(x1).toInt(), p1, Math.round(x2).toInt(), p2, touches, TrendLine.Kind.RESISTANCE,
                    x1, x2, t1, t2, if (sourceMs == tfMs) 0 else sourceMs)
                return if (flip > 0) l else l.flipped()
            }
            val name: String get() = if (sourceMs == tfMs) "" else timeframeName(sourceMs) + " "
        }

        private fun ownCand(l: TLine) = Cand(x.openTime[l.i1], l.p1, x.openTime[l.i2], l.p2, l.touches, tfMs)
        private fun higherCand(hh: Higher, l: TLine) = Cand(hh.h.openTime[l.i1], l.p1, hh.h.openTime[l.i2], l.p2, l.touches, hh.ms)

        var broken: Cand? = null
        var brokenAt = -1
        var movedAway = false
        private val used = HashSet<String>()

        fun real(p: Double) = p * flip

        /** Higher-timeframe lines in force at the close of candle [t] (only higher candles already closed). */
        private fun higherLines(t: Int): List<Cand> {
            val closeTime = x.openTime[t] + tfMs
            val out = ArrayList<Cand>()
            higher.forEachIndexed { n, hh ->
                var k = higherIdx[n]
                while (k + 1 < hh.h.size && hh.h.openTime[k + 1] + hh.ms <= closeTime) k++
                higherIdx[n] = k
                if (k >= 0) hh.lines[k]?.let { out += higherCand(hh, it) }
            }
            return out
        }

        /** Advances this side to the close of candle [t]. Returns a new position on an entry signal. */
        fun step(t: Int, events: MutableList<BrEvent>, flat: Boolean): Position? {
            val c = x.close[t]
            val tol = cfg.touchAtr * atr[t]
            val ownLine = own.update(t)
            val htf = higherLines(t)

            var signal: Position? = null
            val b = broken
            if (b != null) {
                val lv = b.at(t)
                when {
                    c < lv -> { // closed back through: fake-out
                        events += event(BrAction.CANCELLED, t, c, b, "Closed back through the broken line: setup cancelled")
                        broken = null
                    }
                    t - brokenAt > cfg.retestBars -> broken = null // no retest in time
                    !movedAway -> if (x.high[t] >= lv + cfg.awayAtr * atr[t]) movedAway = true
                    x.low[t] <= lv + tol -> {
                        events += event(BrAction.RETEST, t, c, b, "Pulled back and retested the broken ${b.name}line")
                        broken = null
                        val ok = flat && (allowed?.getOrNull(t) ?: true)
                        if (ok) signal = entry(b, t)?.also { p ->
                            events += BrEvent(BrAction.ENTRY, dir, t, x.openTime[t], real(c), b.real(), p.initialStop, p.target, entryText(b, p))
                        }
                    }
                }
            }
            if (broken == null && t > 0) {
                // Highest timeframe first: her higher-timeframe lines matter most.
                val cands = htf + listOfNotNull(ownLine?.let { ownCand(it) })
                val hit = cands.firstOrNull { w -> w.key !in used && c > w.at(t) && x.close[t - 1] <= w.at(t - 1) }
                if (hit != null) {
                    used += hit.key
                    broken = hit; brokenAt = t; movedAway = x.high[t] >= hit.at(t) + cfg.awayAtr * atr[t]
                    if (ownLine != null && hit.sourceMs == tfMs) own.watching = null
                    events += event(BrAction.BREAK, t, c, hit,
                        "Closed ${if (flip > 0) "above" else "below"} the ${hit.name}${if (flip > 0) "down" else "up"}trend line at " +
                            "${formatPrice(real(hit.at(t)))}. Wait for a retest")
                }
            }
            // A chart line that price has closed through is finished, even when another line took the break,
            // so it isn't drawn as if still in force.
            own.watching?.let { w -> if (c > w.at(t)) own.watching = null }
            return signal
        }

        private fun event(a: BrAction, t: Int, c: Double, l: Cand, text: String) =
            BrEvent(a, dir, t, x.openTime[t], real(c), l.real(), text = text)

        private fun entry(l: Cand, t: Int): Position? {
            val c = x.close[t]
            var bIdx = brokenAt
            for (i in brokenAt..t) if (x.low[i] < x.low[bIdx]) bIdx = i
            val buf = cfg.stopBufferAtr * atr[t]
            val opp = if (cfg.stop == StopMode.LINE) opposingLine(bIdx, t) else null
            val stop = (opp?.at(t + 1) ?: x.low[bIdx]) - buf
            val risk = c - stop
            if (risk <= abs(c) * cfg.minRiskFraction) return null
            var target: Double? = null
            if (cfg.exit == ExitMode.TARGET) {
                val lvl = own.nearestLevelAbove(t, c + 0.5 * atr[t])
                if (lvl != null) {
                    val tp = lvl - cfg.targetBufferAtr * atr[t]
                    if (tp > c) target = tp
                }
                if (cfg.minTargetR > 0 && (target == null || (target - c) / risk < cfg.minTargetR)) return null
            }
            return Position(dir, l.real(), brokenAt, t, t + 1, real(c), real(stop), real(stop), target?.let { real(it) },
                mutableListOf(t + 1 to real(stop)), opp, buf)
        }

        /**
         * Her opposing line for a long: from the newest swing low before the retest low B (and lower than B)
         * through B, with no candle below it since. Null when there is none: the stop stays flat under B.
         */
        private fun opposingLine(bIdx: Int, t: Int): Opp? {
            val bPrice = x.low[bIdx]
            val k = Swings.lastConfirmedPosition(trailLows, t)
            for (q in k downTo 0) {
                val a = trailLows[q]
                if (bIdx - a.index > cfg.maxLineBars) break
                if (a.index >= bIdx || a.price >= bPrice) continue
                val o = Opp(a.index, a.price, bIdx, bPrice)
                var ok = true
                for (i in a.index..t) if (x.low[i] < o.at(i) - 1e-9 * abs(bPrice)) { ok = false; break }
                if (ok) return o
            }
            return null
        }

        private fun entryText(l: Cand, p: Position): String {
            val buy = flip > 0
            return "${if (buy) "Buy" else "Sell short"} at the next open. Broke the ${l.name}${if (buy) "down" else "up"}trend line, " +
                "pulled back to it and the candle closed ${if (buy) "above" else "below"} it. Stop ${formatPrice(p.initialStop)}" +
                (p.target?.let { ", target ${formatPrice(it)}" } ?: when {
                    p.opp != null -> ", on the opposing ${if (buy) "up" else "down"}trend line: it moves with the line"
                    else -> ", trail it beyond each new swing"
                })
        }

        /**
         * At the close of candle [t], sets the stop for the next candle. Returns (stop, message) when the
         * trader should do something new: a new swing redrew the line, or the flat stop moved.
         */
        fun trail(p: Position, t: Int): Pair<Double, String>? {
            val k = Swings.lastConfirmedPosition(trailLows, t)
            val sw = if (k >= 0) trailLows[k].takeIf { it.confirmedAt == t && it.index > p.signalIndex } else null
            val o = p.opp
            if (o != null) {
                var redrawn = false
                // A new higher swing low above the line: point B becomes point A, the swing is the new B.
                if (sw != null && sw.index > o.i2 && sw.price > o.p2 && sw.price > o.at(sw.index)) {
                    p.opp = Opp(o.i2, o.p2, sw.index, sw.price); redrawn = true
                }
                val next = p.opp!!.at(t + 1) - p.buf
                if (next > p.stop * flip) {
                    p.stop = real(next)
                    p.stops += (t + 1) to p.stop
                }
                return if (redrawn) p.stop to "New higher swing: the stop line now runs through it. Stop for the next candle " +
                    "${formatPrice(p.stop)}, rising with the line" else null
            }
            if (sw == null) return null
            val newStop = sw.price - p.buf
            if (newStop <= p.stop * flip) return null
            p.stop = real(newStop)
            p.stops += (t + 1) to p.stop
            return p.stop to "New swing: move the stop to ${formatPrice(p.stop)}"
        }

        /** Exit price and reason if candle [t] reaches the stop or target. Stop wins a tie. */
        fun checkExit(p: Position, t: Int): Pair<Double, BrAction>? {
            if (t < p.entryIndex) return null
            val stop = p.stop * flip
            if (x.low[t] <= stop) return real(min(x.open[t], stop)) to BrAction.EXIT_STOP
            val tgt = p.target?.let { it * flip } ?: return null
            if (x.high[t] >= tgt) return real(max(x.open[t], tgt)) to BrAction.EXIT_TARGET
            return null
        }

        fun toTrade(p: Position, exitIndex: Int?, exitPrice: Double?, reason: BrAction?): BrTrade {
            val stopLine = p.opp?.let { o ->
                val l = TrendLine(o.i1, o.p1, o.i2, o.p2, 2, TrendLine.Kind.SUPPORT,
                    t1 = x.openTime[o.i1], t2 = x.openTime[o.i2], sourceMs = 0)
                if (flip > 0) l else l.copy(p1 = -o.p1, p2 = -o.p2, kind = TrendLine.Kind.RESISTANCE)
            }
            return BrTrade(dir, p.line, p.breakIndex, p.signalIndex, p.entryIndex, p.signalClose, p.initialStop, p.target,
                p.stops.toList(), exitIndex, exitPrice, reason, stopLine)
        }

        fun state(t: Int) = BrSide(dir, own.watching?.let { ownCand(it).real() }, broken?.real(), broken?.let { brokenAt },
            if (t >= 0 && higher.isNotEmpty()) higherLines(t).map { it.real() } else emptyList())
    }

    /** Support/resistance levels near the last price: clustered swing highs and lows with 2+ touches. */
    private fun levels(s: Series, t: Int, atr: DoubleArray): List<Level> {
        if (t < 0 || atr.getOrNull(t)?.isNaN() != false) return emptyList()
        val tol = cfg.touchAtr * atr[t]
        val pts = (Swings.highs(s.high, cfg.pivot, cfg.pivot) + Swings.lows(s.low, cfg.pivot, cfg.pivot))
            .filter { it.confirmedAt <= t && t - it.index <= cfg.maxLineBars * 2 }
            .map { it.price }.sorted()
        val out = ArrayList<Level>()
        var i = 0
        while (i < pts.size) {
            var j = i
            while (j + 1 < pts.size && pts[j + 1] - pts[i] <= 2 * tol) j++
            if (j > i) out += Level(pts.subList(i, j + 1).average(), j - i + 1)
            i = j + 1
        }
        val c = s.close[t]
        return out.sortedBy { abs(it.price - c) }.take(6).sortedBy { it.price }
    }
}

/** Settings per chart timeframe, as tested in the study (tools/BreakRetestStudy.kt). */
object BreakRetestPresets {
    fun forTimeframe(tf: Timeframe): BreakRetestConfig = when (tf) {
        Timeframe.D1 -> BreakRetestConfig(pivot = 3, trailPivot = 2, retestBars = 10, maxLineBars = 200)
        Timeframe.H4 -> BreakRetestConfig(pivot = 3, trailPivot = 2, retestBars = 24, maxLineBars = 250)
        Timeframe.H1 -> BreakRetestConfig(pivot = 5, trailPivot = 3, retestBars = 48)
        Timeframe.M15, Timeframe.M5 -> BreakRetestConfig(pivot = 5, trailPivot = 3, retestBars = 36, maxLineBars = 400)
    }

    /** Her top-down chain: the higher timeframes whose lines are carried down to [tf]. */
    fun higherOf(tf: Timeframe): List<Timeframe> = when (tf) {
        Timeframe.M5 -> listOf(Timeframe.H1, Timeframe.M15)
        Timeframe.M15 -> listOf(Timeframe.H4, Timeframe.H1)
        Timeframe.H1 -> listOf(Timeframe.D1, Timeframe.H4)
        Timeframe.H4 -> listOf(Timeframe.D1)
        Timeframe.D1 -> emptyList()
    }
}

/**
 * Higher-timeframe filter ("top-down"): longs only while the last CLOSED daily candle is above its
 * [emaLength]-day EMA, shorts only while below. Index i of the result applies to the close of candle i of [s].
 */
object DailyTrend {
    fun allow(d1: Series, s: Series, emaLength: Int = 50): Pair<BooleanArray, BooleanArray> {
        val ema = Indicators.ema(d1.close, emaLength)
        val step = if (s.size > 1) s.openTime[1] - s.openTime[0] else Timeframe.D1.millis
        val up = BooleanArray(s.size)
        val down = BooleanArray(s.size)
        for (i in 0 until s.size) {
            val k = d1.closedIndexAt(Timeframe.D1, s.openTime[i] + step)
            if (k < 0 || ema[k].isNaN()) continue
            up[i] = d1.close[k] > ema[k]
            down[i] = d1.close[k] < ema[k]
        }
        return up to down
    }
}
