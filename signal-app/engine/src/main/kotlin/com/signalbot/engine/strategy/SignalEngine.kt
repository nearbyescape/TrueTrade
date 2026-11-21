package com.signalbot.engine.strategy

import com.signalbot.engine.indicators.Swings
import com.signalbot.engine.model.Asset
import com.signalbot.engine.model.Timeframe
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Judges one 1H candle close for one asset and direction.
 *
 * All rules are written for LONGS. Shorts run the same rules on price-mirrored data (see
 * [MarketData.mirrored]) and the resulting prices are flipped back, so the two directions can
 * never drift apart.
 *
 * Order: data -> market hours / news -> trend -> BTC -> volatility -> setup -> trigger -> volume ->
 * stop width -> room to target. The first failure is returned as [Rejected]; a setup that passes
 * everything becomes a [Signal] with a 0-100 score. Signals under the alert threshold still come
 * back (graded LOG_ONLY) so the journal can show near misses.
 */
class SignalEngine(val cfg: StrategyConfig = StrategyConfig()) {

    fun prepare(data: MarketData): Prepared = Prepared(data, cfg)

    /** Judge the newest closed 1H candle in every allowed direction. */
    fun evaluateLatest(p: Prepared): List<Evaluation> = directions().map { evaluate(p, p.data.h1.lastIndex, it) }

    fun directions(): List<Direction> = if (cfg.allowShorts) listOf(Direction.LONG, Direction.SHORT) else listOf(Direction.LONG)

    fun evaluate(p: Prepared, barIndex: Int, direction: Direction): Evaluation {
        require(barIndex in 0 until p.data.h1.size) { "barIndex out of range" }
        val side = if (direction == Direction.LONG) p.long else p.short
        return Judge(side, p.data.asset, barIndex, direction).run()
    }

    /** One evaluation. Works in "long frame" prices; [real] converts back for shorts. */
    private inner class Judge(
        private val side: PreparedSide,
        private val asset: Asset,
        private val i: Int,
        private val dir: Direction,
    ) {
        private val long = dir == Direction.LONG
        private val h1 = side.h1
        private val h4 = side.h4
        private val d1 = side.d1
        private val t = h1.series.openTime[i] + Timeframe.H1.millis

        private fun real(p: Double) = if (long) p else -p
        private fun reject(gate: Gate, detail: String) = Rejected(asset.id, dir, t, i, gate, detail)

        // Direction-aware words for reasons and rejections.
        private val upWord = if (long) "up" else "down"
        private val aboveWord = if (long) "above" else "below"
        private val risingWord = if (long) "rising" else "falling"
        private val bullish = if (long) "Bullish" else "Bearish"
        private val lowWord = if (long) "low" else "high"
        private val resistanceWord = if (long) "resistance" else "support"

        fun run(): Evaluation {
            if (asset.underlyingClosedAt(t)) return reject(Gate.MARKET_CLOSED, "${asset.displayName} market is closed for the weekend")
            if (cfg.newsBlackouts.any { t in it }) return reject(Gate.NEWS, "Inside a news blackout window")

            val j4 = h4.series.closedIndexAt(Timeframe.H4, t)
            val jd = d1.series.closedIndexAt(Timeframe.D1, t)
            if (i < cfg.warmup1h || j4 < cfg.warmup4h || jd < cfg.warmup1d) {
                return reject(Gate.INSUFFICIENT_DATA,
                    "Need more history: 1H ${i + 1}/${cfg.warmup1h + 1}, 4H ${j4 + 1}/${cfg.warmup4h + 1}, 1D ${jd + 1}/${cfg.warmup1d + 1}")
            }
            val a4 = h4.atr[j4]
            val a1 = h1.atr[i]
            val needed = doubleArrayOf(
                d1.emaSlow[jd], d1.emaFast[jd], h4.emaFast[j4], h4.emaSlow[j4], h4.emaFast[j4 - cfg.emaRisingBars],
                h4.dmi.adx[j4], a4, h4.atrPctMedian[j4], a1, h1.rsi[i], h1.rsi[i - cfg.rsiTurnLookback],
                h1.macdHist[i], h1.macdHist[i - 1], h1.volumeSma[i - 1],
            )
            if (needed.any { it.isNaN() }) return reject(Gate.INSUFFICIENT_DATA, "Indicators still warming up")

            // 1. Trend: daily, 4H structure, 4H slope, 4H strength.
            if (d1.close[jd] <= d1.emaSlow[jd]) return reject(Gate.TREND, "Daily close is not $aboveWord the 200 EMA")
            if (h4.emaFast[j4] <= h4.emaSlow[j4]) return reject(Gate.TREND, "4H EMA 50 is not $aboveWord EMA 200")
            if (h4.emaFast[j4] <= h4.emaFast[j4 - cfg.emaRisingBars]) return reject(Gate.TREND, "4H EMA 50 is not $risingWord")
            val adx = h4.dmi.adx[j4]
            if (adx < cfg.minAdx) return reject(Gate.TREND, "4H ADX ${f1(adx)} is below ${f1(cfg.minAdx)}: trend too weak")

            // 2. BTC filter for coins that follow BTC.
            val btc = btcState()
            if (btc == BtcState.AGAINST) return reject(Gate.BTC_FILTER, "BTC 4H trend is against this trade")

            // 3. Volatility regime.
            val regime = h4.atrPct[j4] / h4.atrPctMedian[j4]
            if (regime < cfg.regimeMin) return reject(Gate.VOLATILITY, "Market too quiet: 4H ATR is ${f2(regime)}x its normal level")
            if (regime > cfg.regimeMax) return reject(Gate.VOLATILITY, "Market too wild: 4H ATR is ${f2(regime)}x its normal level")

            // 4. Setups: where price is.
            val from = i - cfg.setupLookback1h + 1
            var windowLow = Double.POSITIVE_INFINITY
            for (m in from..i) windowLow = min(windowLow, h1.low[m])
            val close = h1.close[i]
            val setups = LinkedHashSet<SetupType>()
            val conf = LinkedHashSet<Confluence>()
            val notes = ArrayList<String>()

            val ema = h4.emaFast[j4]
            // Touched the zone, closed back above its bottom, and didn't break far below it (that's a breakdown, not a pullback).
            if (windowLow <= ema + cfg.emaZoneAtr * a4 && windowLow >= ema - 1.5 * a4 && close > ema - cfg.emaZoneAtr * a4) {
                setups += SetupType.TREND_PULLBACK; conf += Confluence.EMA_ZONE
                notes += "pullback to the 4H EMA 50"
            }
            fibZone(j4, a4)?.let { (top, bottom) ->
                if (windowLow <= top && windowLow >= bottom - 0.25 * a4 && close > bottom) {
                    setups += SetupType.TREND_PULLBACK; conf += Confluence.FIB_ZONE
                    notes += "${pct(cfg.fibShallow)}-${pct(cfg.fibDeep)} retracement"
                }
            }
            breakoutLevel(j4, from, a1, a4)?.let { level ->
                setups += SetupType.BREAKOUT_RETEST; conf += Confluence.BREAKOUT_LEVEL
                notes += "retest of the 4H breakout at ${px(level)}"
            }
            sweptLevel(from)?.let { level ->
                setups += SetupType.LIQUIDITY_SWEEP; conf += Confluence.SWEPT_LEVEL
                notes += "swept the 1H swing $lowWord at ${px(level)} and reclaimed it"
            }
            if (setups.isEmpty()) return reject(Gate.NO_SETUP, "Price is not at a pullback zone, breakout retest or swept $lowWord")

            // 5. Trigger on the 1H candle that just closed.
            val engulf = isEngulfing()
            val bos = isBreakOfStructure()
            if (!engulf && !bos) return reject(Gate.NO_TRIGGER, "Setup found, waiting for an engulfing candle or 1H break of structure")
            val rsiTurn = isRsiTurn()
            val macdFlip = h1.macdHist[i] > 0 && h1.macdHist[i - 1] <= 0

            // 6. Volume on the trigger candle vs the 20 candles before it.
            val avgVol = h1.volumeSma[i - 1]
            val volRatio = if (avgVol > 0) h1.volume[i] / avgVol else 0.0
            if (volRatio < cfg.minVolumeRatio) return reject(Gate.LOW_VOLUME, "Trigger volume is only ${f2(volRatio)}x average")

            // 7. Stop below the setup's low, plus a small buffer.
            val entry = close
            val stop = windowLow - cfg.stopBufferAtr1h * a1
            val risk = entry - stop
            if (risk > cfg.maxStopAtr4h * a4) return reject(Gate.STOP_TOO_WIDE,
                "Stop would be ${f2(risk / a4)} 4H ATRs away (max ${f2(cfg.maxStopAtr4h)})")
            if (risk < cfg.minStopAtr1h * a1) return reject(Gate.STOP_TOO_TIGHT,
                "Stop would be only ${f2(risk / a1)} 1H ATRs away (min ${f2(cfg.minStopAtr1h)})")

            // 8. Room to the nearest unbroken resistance (4H or daily swing high).
            val res = nearestResistance(j4, jd, entry)
            val openSky = res == null
            val roomR = if (res == null) cfg.openSkyTargetR else (res.first - entry) / risk
            if (res != null && roomR < cfg.minRoomR) return reject(Gate.NOT_ENOUGH_ROOM,
                "${res.second} $resistanceWord at ${px(res.first)} is only ${f1(roomR)}R away (need ${f1(cfg.minRoomR)}R)")
            val target = res?.first ?: (entry + cfg.openSkyTargetR * risk)

            // 9. Score.
            val dailyAligned = d1.emaFast[jd] > d1.emaSlow[jd]
            val trendScore = 15 + (if (adx >= cfg.strongAdx) 5 else 0) + (if (dailyAligned) 5 else 0)
            val locationScore = if (conf.size >= 2) Score.MAX_LOCATION else 12
            val triggerScore = min(Score.MAX_TRIGGER, (if (engulf && bos) 10 else 6) + (if (rsiTurn || macdFlip) 5 else 0))
            val obvNewHigh = (i - cfg.obvLookback until i).all { h1.obv[i] >= h1.obv[it] }
            val obvRising = h1.obv[i] > h1.obv[i - 10]
            val volumeScore = min(Score.MAX_VOLUME,
                (when { volRatio >= cfg.strongVolumeRatio -> 10; volRatio >= cfg.goodVolumeRatio -> 7; else -> 3 }) +
                    (when { obvNewHigh -> 5; obvRising -> 3; else -> 0 }))
            val roomScore = when { openSky || roomR >= 5.0 -> 15; roomR >= 4.0 -> 12; else -> 8 }
            val steady = regime in cfg.steadyMin..cfg.steadyMax
            val ownMomentum = h4.close[j4] > h4.emaFast[j4]
            val contextScore = (when (btc) {
                BtcState.ALIGNED -> 6
                BtcState.NEUTRAL -> 3
                BtcState.UNKNOWN -> 0
                BtcState.NOT_APPLICABLE -> if (ownMomentum) 6 else 3
                BtcState.AGAINST -> 0
            }) + (if (steady) 4 else 0)
            val score = Score(trendScore, locationScore, triggerScore, volumeScore, roomScore, contextScore)
            val grade = when {
                score.total >= cfg.aPlusScore -> Grade.A_PLUS
                score.total >= cfg.alertScore -> Grade.ALERT
                else -> Grade.LOG_ONLY
            }

            // 10. Plan in real prices.
            val plan = TradePlan(
                direction = dir,
                entry = real(entry),
                stop = real(stop),
                tp1 = real(entry + cfg.tp1R * risk),
                tp2 = real(entry + cfg.tp2R * risk),
                target = real(target),
                roomR = roomR,
                openSky = openSky,
                tp1Fraction = cfg.tp1Fraction,
                tp2Fraction = cfg.tp2Fraction,
                runnerFraction = cfg.runnerFraction,
                trailDistance = cfg.runnerTrailAtr4h * a4,
            )

            val reasons = buildList {
                add("Daily and 4H trend $upWord, 4H ADX ${f1(adx)}")
                add(notes.joinToString(" + ").replaceFirstChar { it.uppercase() })
                add(buildString {
                    append(listOfNotNull(if (engulf) "$bullish engulfing candle" else null, if (bos) "1H break of structure" else null).joinToString(" + "))
                    if (rsiTurn) append(", RSI turning $upWord")
                    if (macdFlip) append(", MACD flipped ${if (long) "positive" else "negative"}")
                })
                add("Volume ${f1(volRatio)}x average" + if (obvNewHigh) ", OBV at a 20-bar ${if (long) "high" else "low"}" else "")
                add(if (res == null) "No $resistanceWord found: target set at ${f1(cfg.openSkyTargetR)}R"
                    else "Target ${px(res.first)} (${res.second} $resistanceWord), ${f1(roomR)}R away")
                when (btc) {
                    BtcState.ALIGNED -> add("BTC 4H trend agrees")
                    BtcState.UNKNOWN -> add("BTC data unavailable: context not checked")
                    else -> {}
                }
            }

            return Signal(asset.id, dir, t, i, setups, conf, plan, score, grade, reasons, adx, volRatio, regime)
        }

        // ------------------------------------------------------------------ rule helpers

        private fun btcState(): BtcState {
            if (!asset.followsBtc) return BtcState.NOT_APPLICABLE
            val b = side.btcH4 ?: return BtcState.UNKNOWN
            val k = b.series.closedIndexAt(Timeframe.H4, t)
            if (k < 0 || b.emaSlow[k].isNaN() || b.emaFast[k].isNaN()) return BtcState.UNKNOWN
            // BTC 4H trend against us (EMA 50 below 200) blocks; trend with us but price dipping below EMA 50 is neutral.
            return when {
                b.emaFast[k] < b.emaSlow[k] -> BtcState.AGAINST
                b.close[k] > b.emaFast[k] -> BtcState.ALIGNED
                else -> BtcState.NEUTRAL
            }
        }

        /** 0.5-0.618 retracement of the last 4H impulse (swing low -> confirmed swing high), as (top, bottom). */
        private fun fibZone(j4: Int, a4: Double): Pair<Double, Double>? {
            val highs = h4.swingHighs(cfg.swing4hLeft, cfg.swing4hRight)
            val hPos = Swings.lastConfirmedPosition(highs, j4)
            if (hPos < 0) return null
            val top = highs[hPos]
            val lows = h4.swingLows(cfg.swing4hLeft, cfg.swing4hRight)
            // Newest swing low before the swing high.
            var lPos = Swings.lastConfirmedPosition(lows, top.index - 1 + cfg.swing4hRight)
            while (lPos >= 0 && lows[lPos].index >= top.index) lPos--
            if (lPos < 0) return null
            val bottom = lows[lPos]
            val leg = top.price - bottom.price
            if (leg < cfg.minImpulseAtr * a4) return null
            // Still retracing: no 4H high above the swing high since.
            for (k in top.index + 1..j4) if (h4.high[k] > top.price) return null
            return Pair(top.price - cfg.fibShallow * leg, top.price - cfg.fibDeep * leg)
        }

        /** Level of a recent 4H breakout (on volume, still holding) that the last 1H bars came back to test. */
        private fun breakoutLevel(j4: Int, from: Int, a1: Double, a4: Double): Double? {
            val oldest = max(cfg.breakoutRangeBars, j4 - cfg.breakoutRecent4h + 1)
            for (k in j4 downTo oldest) {
                var rangeHigh = Double.NEGATIVE_INFINITY
                for (m in k - cfg.breakoutRangeBars until k) rangeHigh = max(rangeHigh, h4.high[m])
                val avgVol = h4.volumeSma[k - 1]
                if (!(h4.close[k] > rangeHigh && avgVol > 0 && h4.volume[k] >= cfg.breakoutVolumeMult * avgVol)) continue
                // The breakout must still hold on 4H closes.
                if ((k + 1..j4).any { h4.close[it] < rangeHigh - 0.2 * a4 }) continue
                // Retest = 1H bars after the breakout candle closed.
                val breakoutClose = h4.series.openTime[k] + Timeframe.H4.millis
                var retestLow = Double.POSITIVE_INFINITY
                for (m in from..i) if (h1.series.openTime[m] >= breakoutClose) retestLow = min(retestLow, h1.low[m])
                if (retestLow == Double.POSITIVE_INFINITY) continue
                if (retestLow <= rangeHigh + cfg.retestAtr1h * a1 && retestLow >= rangeHigh - 0.5 * a4 && h1.close[i] > rangeHigh) {
                    return rangeHigh
                }
            }
            return null
        }

        /** A recent 1H swing low that a candle in the window wicked under and closed back above. */
        private fun sweptLevel(from: Int): Double? {
            val lows = h1.swingLows(cfg.sweepSwingLeft, cfg.sweepSwingRight)
            for (m in i downTo from) {
                // Only swings already known before the sweep candle.
                var pos = Swings.lastConfirmedPosition(lows, m - 1)
                while (pos >= 0 && lows[pos].index >= i - cfg.sweepLookback1h) {
                    val level = lows[pos].price
                    if (h1.low[m] < level && h1.close[m] > level && h1.close[i] > level) return level
                    pos--
                }
            }
            return null
        }

        private fun isEngulfing(): Boolean =
            h1.close[i] > h1.open[i] && h1.close[i - 1] < h1.open[i - 1] &&
                h1.close[i] >= h1.open[i - 1] && h1.open[i] <= h1.close[i - 1]

        /** This candle closed above the most recent 1H swing high, which the previous candle had not. */
        private fun isBreakOfStructure(): Boolean {
            val highs = h1.swingHighs(cfg.bosSwingLeft, cfg.bosSwingRight)
            val pos = Swings.lastConfirmedPosition(highs, i - 1)
            if (pos < 0) return false
            val s = highs[pos]
            if (s.index < i - cfg.bosRecentBars) return false
            return h1.close[i] > s.price && h1.close[i - 1] <= s.price
        }

        /** RSI dipped into the 40-50 zone during the pullback and is now turning up. */
        private fun isRsiTurn(): Boolean {
            var lowest = Double.POSITIVE_INFINITY
            for (m in i - cfg.rsiTurnLookback until i) lowest = min(lowest, h1.rsi[m])
            return lowest in cfg.rsiZoneLow..cfg.rsiZoneHigh && h1.rsi[i] > h1.rsi[i - 1]
        }

        /** Nearest swing high above [entry] that no later close has broken: (price, "4H"/"daily"), or null. */
        private fun nearestResistance(j4: Int, jd: Int, entry: Double): Pair<Double, String>? {
            var best: Pair<Double, String>? = null
            fun scan(view: TfView, byIndex: DoubleArray, right: Int, j: Int, lookback: Int, label: String) {
                var laterMaxClose = Double.NEGATIVE_INFINITY
                for (k in j downTo max(0, j - lookback)) {
                    val p = byIndex[k]
                    if (!p.isNaN() && k + right <= j && p > entry && p > laterMaxClose && (best == null || p < best!!.first)) {
                        best = Pair(p, label)
                    }
                    laterMaxClose = max(laterMaxClose, view.close[k])
                }
            }
            scan(h4, side.h4ResistanceByIndex, cfg.swing4hRight, j4, cfg.resistanceLookback4h, "4H")
            scan(d1, side.d1ResistanceByIndex, cfg.dailySwingRight, jd, cfg.resistanceLookback1d, "daily")
            return best
        }

        private fun px(p: Double): String = formatPrice(real(p))
    }

    private enum class BtcState { NOT_APPLICABLE, ALIGNED, NEUTRAL, AGAINST, UNKNOWN }

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun pct(v: Double) = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}

/** Price with sensible decimals: 91,250.5 / 2,650.25 / 31.42 / 0.5123 */
fun formatPrice(p: Double): String {
    val a = abs(p)
    val decimals = when { a >= 1000 -> 1; a >= 1 -> 2; else -> 4 }
    return String.format(Locale.US, "%,.${decimals}f", p)
}
