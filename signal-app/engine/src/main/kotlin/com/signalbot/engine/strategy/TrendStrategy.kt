package com.signalbot.engine.strategy

import com.signalbot.engine.indicators.Indicators
import com.signalbot.engine.model.Timeframe
import java.util.Locale

/**
 * Version 2 strategy: daily breakout trend-following (the classic "Turtle" 55-day breakout).
 * Chosen by backtest on Binance futures (Jul 2023 to Jun 2025) and confirmed on unseen data
 * (Jul 2025 to Nov 2026). See README "Version 2".
 *
 *  - LONG  when a daily candle CLOSES above the highest high of the previous 55 days, the day before
 *          had not, and the daily close is above the 200-day EMA.
 *  - SHORT is the mirror: close below the 55-day low, below the 200-day EMA.
 *  - Stop:  2 x daily ATR(14) from the close.
 *  - Exit:  no fixed target. The whole position trails 5 x daily ATR behind the highest high since
 *           entry (lowest low for shorts); the stop only ever moves in the trade's favour.
 *
 * Wins are few but large (typically 2.5-3.5R) and losses are about -1R, so expect a 35-50% win rate
 * and losing streaks of 5-7 trades. Signals come about once every 3-4 weeks across the watch list.
 */
data class TrendConfig(
    val breakoutDays: Int = 55,
    val stopAtr: Double = 2.0,
    val trailAtr: Double = 5.0,
    val atrLength: Int = 14,
    val regimeEma: Int = 200,
    val allowShorts: Boolean = true,
)

class TrendEngine(val cfg: TrendConfig = TrendConfig()) {

    /** Daily indicators for one asset, computed once. */
    class Prepared(val data: MarketData, cfg: TrendConfig) {
        val d1 = data.d1
        val atr = Indicators.atr(d1.high, d1.low, d1.close, cfg.atrLength)
        val ema = Indicators.ema(d1.close, cfg.regimeEma)
        val highest = Indicators.highest(d1.high, cfg.breakoutDays)
        val lowest = Indicators.lowest(d1.low, cfg.breakoutDays)
    }

    fun prepare(data: MarketData) = Prepared(data, cfg)

    fun directions(): List<Direction> = if (cfg.allowShorts) listOf(Direction.LONG, Direction.SHORT) else listOf(Direction.LONG)

    /**
     * Judge the 1H candle [barIndex]. Only the 1H candle that completes a UTC day (closing at 00:00 UTC,
     * 05:30 IST) can produce a signal; every other hour returns NOT_DAILY_CLOSE.
     */
    fun evaluate(p: Prepared, barIndex: Int, direction: Direction): Evaluation {
        val data = p.data
        val asset = data.asset
        val t = data.h1.openTime[barIndex] + Timeframe.H1.millis
        fun reject(gate: Gate, detail: String) = Rejected(asset.id, direction, t, barIndex, gate, detail)

        if (t % Timeframe.D1.millis != 0L) return reject(Gate.NO_TRIGGER, "Waiting for the daily close (00:00 UTC)")
        val j = p.d1.closedIndexAt(Timeframe.D1, t)
        if (j < 0 || p.d1.openTime[j] + Timeframe.D1.millis != t) return reject(Gate.INSUFFICIENT_DATA, "Daily candle missing")
        if (asset.underlyingClosedAt(t)) return reject(Gate.MARKET_CLOSED, "${asset.displayName} market is closed for the weekend")
        val n = cfg.breakoutDays
        if (j < n + 1 || p.ema[j].isNaN() || p.atr[j].isNaN()) {
            return reject(Gate.INSUFFICIENT_DATA, "Need more history: ${j + 1}/${maxOf(n + 2, cfg.regimeEma)} days")
        }

        val long = direction == Direction.LONG
        val close = p.d1.close[j]
        val ema = p.ema[j]
        if (long && close <= ema) return reject(Gate.TREND, "Daily close is below the ${cfg.regimeEma}-day EMA")
        if (!long && close > ema) return reject(Gate.TREND, "Daily close is above the ${cfg.regimeEma}-day EMA")

        // Channel of the PREVIOUS n days (today excluded), as of today and as of yesterday.
        val channel = if (long) p.highest[j - 1] else p.lowest[j - 1]
        val prevChannel = if (long) p.highest[j - 2] else p.lowest[j - 2]
        val broke = if (long) close > channel else close < channel
        val brokeYesterday = if (long) p.d1.close[j - 1] > prevChannel else p.d1.close[j - 1] < prevChannel
        if (!broke) return reject(Gate.NO_SETUP, "No ${n}-day ${if (long) "high" else "low"} break (level ${formatPrice(channel)})")
        if (brokeYesterday) return reject(Gate.NO_TRIGGER, "Breakout already signalled yesterday")

        val atr = p.atr[j]
        val sign = if (long) 1.0 else -1.0
        val risk = cfg.stopAtr * atr
        val plan = TradePlan(
            direction = direction,
            entry = close,
            stop = close - sign * risk,
            // No fixed targets in this strategy; these are milestones shown in the app only.
            tp1 = close + sign * 2 * risk,
            tp2 = close + sign * 4 * risk,
            target = close + sign * 6 * risk,
            roomR = Double.POSITIVE_INFINITY,
            openSky = true,
            tp1Fraction = 0.0,
            tp2Fraction = 0.0,
            runnerFraction = 1.0,
            trailDistance = cfg.trailAtr * atr,
        )
        val f1 = { v: Double -> String.format(Locale.US, "%.1f", v) }
        val reasons = listOf(
            "Daily close ${formatPrice(close)} broke the ${n}-day ${if (long) "high" else "low"} of ${formatPrice(channel)}",
            "Trend: daily close ${if (long) "above" else "below"} the ${cfg.regimeEma}-day EMA (${formatPrice(ema)})",
            "Stop ${formatPrice(plan.stop)} (${f1(cfg.stopAtr)} x daily ATR ${formatPrice(atr)})",
            "No fixed target: trail the stop ${f1(cfg.trailAtr)} x ATR (${formatPrice(plan.trailDistance)}) behind the best price",
        )
        return Signal(asset.id, direction, t, barIndex, setOf(SetupType.BREAKOUT_RETEST), setOf(Confluence.BREAKOUT_LEVEL),
            plan, Score(25, 20, 15, 10, 15, 10), Grade.A_PLUS, reasons, Double.NaN, Double.NaN, Double.NaN)
    }

    /** Judge the newest closed 1H candle in every direction. */
    fun evaluateLatest(p: Prepared): List<Evaluation> = directions().map { evaluate(p, p.data.h1.lastIndex, it) }
}
