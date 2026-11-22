package com.signalbot.engine.strategy

import com.signalbot.engine.indicators.Indicators
import com.signalbot.engine.indicators.SwingPoint
import com.signalbot.engine.indicators.Swings
import com.signalbot.engine.model.Series
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The trendline BREAK AND RETEST setup, written as exact rules so a phone can follow it.
 *
 * Lines (the "top-down" markup, done by code):
 *  - A resistance line joins two swing highs A then B (B no higher than A). A support line joins two
 *    swing lows (B no lower than A). Swings need [BreakRetestConfig.pivot] candles on each side.
 *  - No candle may cut through the line between A and B. After B, no candle may CLOSE through it, and
 *    wicks may poke through by at most the touch tolerance ("wiggle room").
 *  - Of the valid lines ending on one of the newest swings, keep the one with the most touches,
 *    then the newest, then the longest.
 *
 * Entry, all three must happen, in order:
 *  1. BREAK: a candle closes through the line.
 *  2. RETEST: price moves away, then comes back and touches the line from the other side.
 *  3. CLOSE: that retest candle closes still on the new side of the line. This is THE signal:
 *     enter at the next candle's open. A close back through the line before this cancels the setup.
 *
 * Risk and exit:
 *  - Stop just beyond the retest swing (the low for a long, the high for a short).
 *  - Each new swing in your favour moves the stop to just beyond it (trailing along the opposing line).
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
) {
    init {
        require(pivot >= 2 && trailPivot >= 1 && retestBars >= 1 && newestAnchors >= 1)
    }
}

enum class ExitMode { TRAIL, TARGET }

/** A straight line through two candles, in real (not flipped) prices. */
data class TrendLine(val i1: Int, val p1: Double, val i2: Int, val p2: Double, val touches: Int, val kind: Kind) {
    enum class Kind { RESISTANCE, SUPPORT }
    fun at(i: Int): Double = p1 + (p2 - p1) * (i - i1).toDouble() / (i2 - i1).toDouble()
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
)

/** A horizontal support/resistance level and how many swings touched it. */
data class Level(val price: Double, val touches: Int)

/** Where a setup stands at the last candle, for drawing and for the "waiting for" text. */
data class BrSide(
    val direction: Direction,
    /** The line being watched for a break (resistance for longs, support for shorts). */
    val watching: TrendLine?,
    /** A broken line waiting for its retest, and the candle that broke it. */
    val broken: TrendLine?,
    val brokenAt: Int?,
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

class BreakRetestEngine(val cfg: BreakRetestConfig = BreakRetestConfig()) {

    /**
     * Replays the rules over [s] up to and including candle [last]. Deterministic and free of
     * look-ahead: swings are used only once confirmed, every decision is taken at a candle's close,
     * and orders fill at the next open. One trade at a time per market.
     *
     * [allowLong] / [allowShort], when given, say per candle whether that side may enter (a
     * higher-timeframe trend filter). Null = always allowed.
     */
    fun replay(
        s: Series,
        last: Int = s.lastIndex,
        allowLong: BooleanArray? = null,
        allowShort: BooleanArray? = null,
    ): BreakRetestReplay {
        val longSide = Side(s, Direction.LONG, allowLong)
        val shortSide = Side(s.mirrored(), Direction.SHORT, allowShort)
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
                    trades += p.toTrade(t, price, reason)
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
                side.trail(p, t)?.let { newStop ->
                    events += BrEvent(BrAction.STOP_MOVED, p.dir, t, s.openTime[t], s.close[t], p.line, newStop, p.target,
                        "New swing: move the stop to ${formatPrice(newStop)}")
                }
            }
            val signals = listOf(longSide, shortSide).mapNotNull { side -> side.step(t, events, flat = pos == null) }
            // Two opposite signals on one candle cancel out. A signal on the newest candle fills at the next open.
            if (pos == null && signals.size == 1) pos = signals.single()
        }
        pos?.let { trades += it.toTrade(null, null, null) }
        val sides = listOf(longSide.state(), shortSide.state())
        return BreakRetestReplay(s, events, trades, sides, levels(s, end, atr))
    }

    // --------------------------------------------------------------------------------- one direction

    /** Position in flipped-or-real price space of its side. Prices kept REAL for output. */
    private data class Position(
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
        var lastTrailPivot: Int,
    ) {
        fun toTrade(exitIndex: Int?, exitPrice: Double?, reason: BrAction?) = BrTrade(
            dir, line, breakIndex, signalIndex, entryIndex, signalClose, initialStop, target, stops.toList(), exitIndex, exitPrice, reason,
        )
    }

    /**
     * Rules for LONG trades on [x]. For shorts [x] is the flipped chart (price p becomes -p), so the
     * same code finds them; prices are flipped back whenever they leave this class.
     */
    private inner class Side(val x: Series, val dir: Direction, val allowed: BooleanArray?) {
        val flip = if (dir == Direction.LONG) 1.0 else -1.0
        val atr = Indicators.atr(x.high, x.low, x.close, cfg.atrLength)
        val highs: List<SwingPoint> = Swings.highs(x.high, cfg.pivot, cfg.pivot)
        val trailLows: List<SwingPoint> = Swings.lows(x.low, cfg.trailPivot, cfg.trailPivot)
        private val confirmAt = HashMap<Int, Int>().apply { highs.forEachIndexed { k, p -> put(p.confirmedAt, k) } }

        var watching: Line? = null
        var broken: Line? = null
        var brokenAt = -1
        var movedAway = false
        private var redraw = true

        inner class Line(val i1: Int, val p1: Double, val i2: Int, val p2: Double, val touches: Int) {
            fun at(i: Int) = p1 + (p2 - p1) * (i - i1).toDouble() / (i2 - i1).toDouble()
            fun real(): TrendLine {
                val l = TrendLine(i1, p1, i2, p2, touches, TrendLine.Kind.RESISTANCE)
                return if (flip > 0) l else l.flipped()
            }
        }

        fun real(p: Double) = p * flip

        /** Advances this side to the close of candle [t]. Returns a new position on an entry signal. */
        fun step(t: Int, events: MutableList<BrEvent>, flat: Boolean): Position? {
            val c = x.close[t]
            val tol = cfg.touchAtr * atr[t]
            // A newly confirmed swing high may give a better line to watch.
            // Lines only change when a new swing confirms or the current line is dropped.
            if (redraw || confirmAt.containsKey(t)) { bestLine(t)?.let { watching = it }; redraw = false }
            watching?.let { w ->
                if (t - w.i1 > cfg.maxLineBars * 2) { watching = null; redraw = true }
                else if (x.high[t] > w.at(t) + tol && c <= w.at(t)) { watching = null; redraw = true } // pierced, not closed
            }

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
                        events += event(BrAction.RETEST, t, c, b, "Pulled back and retested the broken line")
                        broken = null
                        val ok = flat && (allowed?.getOrNull(t) ?: true)
                        if (ok) signal = entry(b, t)?.also { p ->
                            events += BrEvent(BrAction.ENTRY, dir, t, x.openTime[t], real(c), b.real(), p.initialStop, p.target,
                                entryText(b, t, p))
                        }
                    }
                }
            }
            val w = watching
            if (broken == null && w != null && c > w.at(t) && x.close.getOrNull(t - 1)?.let { it <= w.at(t - 1) } == true) {
                broken = w; brokenAt = t; movedAway = x.high[t] >= w.at(t) + cfg.awayAtr * atr[t]; watching = null
                events += event(BrAction.BREAK, t, c, w,
                    "Closed ${if (flip > 0) "above" else "below"} the ${if (flip > 0) "down" else "up"}trend line at ${formatPrice(real(w.at(t)))}. " +
                        "Wait for a retest")
            }
            return signal
        }

        private fun event(a: BrAction, t: Int, c: Double, l: Line, text: String) =
            BrEvent(a, dir, t, x.openTime[t], real(c), l.real(), text = text)

        private fun entry(l: Line, t: Int): Position? {
            val c = x.close[t]
            var lo = Double.POSITIVE_INFINITY
            for (i in brokenAt..t) lo = min(lo, x.low[i])
            val stop = lo - cfg.stopBufferAtr * atr[t]
            val risk = c - stop
            if (risk <= abs(c) * cfg.minRiskFraction) return null
            var target: Double? = null
            if (cfg.exit == ExitMode.TARGET) {
                val lvl = nearestLevelAbove(t, c + 0.5 * atr[t])
                if (lvl != null) {
                    val tp = lvl - cfg.targetBufferAtr * atr[t]
                    if (tp > c) target = tp
                }
                if (cfg.minTargetR > 0 && (target == null || (target - c) / risk < cfg.minTargetR)) return null
            }
            return Position(dir, l.real(), brokenAt, t, t + 1, real(c), real(stop), real(stop), target?.let { real(it) },
                mutableListOf(t + 1 to real(stop)), lastTrailPivot = t)
        }

        private fun entryText(l: Line, t: Int, p: Position): String {
            val buy = flip > 0
            return "${if (buy) "Buy" else "Sell short"} at the next open. Broke the ${if (buy) "down" else "up"}trend line, " +
                "pulled back to it and the candle closed ${if (buy) "above" else "below"} it. Stop ${formatPrice(p.initialStop)}" +
                (p.target?.let { ", target ${formatPrice(it)}" } ?: ", trail it under each new swing")
        }

        /** Moves the stop to beyond a swing confirmed at [t], if that is better. Returns the new stop. */
        fun trail(p: Position, t: Int): Double? {
            val k = Swings.lastConfirmedPosition(trailLows, t)
            if (k < 0) return null
            val sw = trailLows[k]
            if (sw.confirmedAt != t || sw.index <= p.signalIndex) return null
            val newStop = sw.price - cfg.stopBufferAtr * atr[t]
            if (newStop <= p.stop * flip) return null
            p.stop = real(newStop)
            p.stops += (t + 1) to p.stop
            return p.stop
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

        fun state() = BrSide(dir, watching?.real(), broken?.real(), broken?.let { brokenAt })

        /** The best line ending on one of the newest confirmed swing highs, valid through candle t-1. */
        private fun bestLine(t: Int): Line? {
            val last = Swings.lastConfirmedPosition(highs, t)
            if (last < 1) return null
            var best: Line? = null
            for (kb in last downTo max(1, last - cfg.newestAnchors + 1)) {
                val b = highs[kb]
                var ka = kb - 1
                while (ka >= 0 && b.index - highs[ka].index <= cfg.maxLineBars) {
                    val a = highs[ka]
                    ka--
                    if (a.price < b.price || b.index - a.index < 2 * cfg.pivot) continue
                    val l = Line(a.index, a.price, b.index, b.price, 0)
                    val touches = validTouches(l, t, kb) ?: continue
                    if (touches < cfg.minTouches) continue
                    val cand = Line(a.index, a.price, b.index, b.price, touches)
                    if (best == null || better(cand, best)) best = cand
                }
            }
            return best
        }

        private fun better(a: Line, b: Line) = when {
            a.touches != b.touches -> a.touches > b.touches
            a.i2 != b.i2 -> a.i2 > b.i2
            else -> a.i1 < b.i1
        }

        /** Touch count if [l] is still a valid, unbroken line at the close of candle t-1, else null. */
        private fun validTouches(l: Line, t: Int, kb: Int): Int? {
            val eps = 1e-9 * abs(l.p1)
            for (i in l.i1..l.i2) if (x.high[i] > l.at(i) + eps) return null
            for (i in l.i2 + 1 until t) {
                val lv = l.at(i)
                if (x.close[i] > lv) return null
                if (!atr[i].isNaN() && x.high[i] > lv + cfg.touchAtr * atr[i]) return null
            }
            var touches = 0
            val a = atr.getOrNull(t)?.takeIf { !it.isNaN() } ?: return 2
            for (k in 0..kb) {
                val h = highs[k]
                if (h.index < l.i1) continue
                if (abs(h.price - l.at(h.index)) <= cfg.touchAtr * a) touches++
            }
            return max(touches, 2)
        }

        /** Nearest resistance above [above]: swing highs (confirmed by t) clustered within the touch tolerance, 2+ touches. */
        private fun nearestLevelAbove(t: Int, above: Double): Double? {
            val last = Swings.lastConfirmedPosition(highs, t)
            if (last < 0) return null
            val tol = cfg.touchAtr * atr[t]
            val cands = (0..last).map { highs[it] }.filter { it.price > above && t - it.index <= cfg.maxLineBars * 2 }
                .sortedBy { it.price }
            for (h in cands) {
                val n = cands.count { abs(it.price - h.price) <= tol }
                if (n >= 2) return cands.filter { abs(it.price - h.price) <= tol }.maxOf { it.price }
            }
            return null
        }
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
