package com.signalbot.engine.backtest

import kotlin.math.max

/** Performance summary of a set of trades. All R figures are after fees and funding. */
data class Stats(
    val trades: Int,
    /** Share of trades that ended in profit (any exit). */
    val winRate: Double,
    /** Average result per trade in R: the expectancy. */
    val avgR: Double,
    val totalR: Double,
    /** Gross profit / gross loss. Above 1 = profitable. */
    val profitFactor: Double,
    /** Deepest peak-to-trough fall of the cumulative R curve. */
    val maxDrawdownR: Double,
    val longestLosingStreak: Int,
    val avgWinR: Double,
    val avgLossR: Double,
    val tradesPerWeek: Double,
    val avgHoldHours: Double,
    val feesR: Double,
    val fundingR: Double,
    /** Return on capital compounding [BacktestSettings.riskPerTrade] per trade, in order of exit. */
    val compoundedReturn: Double,
    val maxDrawdownPct: Double,
    val outcomes: Map<Outcome, Int>,
) {
    /** The minimum a result needs for statistics, so other trade types (ensemble legs) can use [Stats]. */
    data class Row(val entryTime: Long, val exitTime: Long, val netR: Double, val feesR: Double, val fundingR: Double, val outcome: Outcome) {
        val holdHours: Double get() = (exitTime - entryTime) / 3_600_000.0
    }

    companion object {
        fun of(trades: List<Trade>, riskPerTrade: Double, periodMs: Long? = null): Stats =
            ofRows(trades.map { Row(it.entryTime, it.exitTime, it.netR, it.feesR, it.fundingR, it.outcome) }, riskPerTrade, periodMs)

        fun ofRows(rows: List<Row>, riskPerTrade: Double, periodMs: Long? = null): Stats {
            val sorted = rows.sortedBy { it.exitTime }
            val n = sorted.size
            if (n == 0) return Stats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap())
            val rs = sorted.map { it.netR }
            val wins = rs.filter { it > 0 }
            val losses = rs.filter { it <= 0 }
            var cum = 0.0; var peak = 0.0; var dd = 0.0
            var eq = 1.0; var eqPeak = 1.0; var ddPct = 0.0
            var streak = 0; var worstStreak = 0
            for (r in rs) {
                cum += r; peak = max(peak, cum); dd = max(dd, peak - cum)
                eq *= 1 + riskPerTrade * r; eqPeak = max(eqPeak, eq); ddPct = max(ddPct, 1 - eq / eqPeak)
                if (r <= 0) { streak++; worstStreak = max(worstStreak, streak) } else streak = 0
            }
            val span = periodMs ?: (sorted.last().exitTime - sorted.minOf { it.entryTime })
            val grossWin = wins.sum()
            val grossLoss = -losses.sum()
            return Stats(
                trades = n,
                winRate = wins.size.toDouble() / n,
                avgR = rs.average(),
                totalR = rs.sum(),
                profitFactor = if (grossLoss > 0) grossWin / grossLoss else Double.POSITIVE_INFINITY,
                maxDrawdownR = dd,
                longestLosingStreak = worstStreak,
                avgWinR = if (wins.isEmpty()) 0.0 else wins.average(),
                avgLossR = if (losses.isEmpty()) 0.0 else losses.average(),
                tradesPerWeek = if (span > 0) n / (span / (7 * 86_400_000.0)) else 0.0,
                avgHoldHours = sorted.map { it.holdHours }.average(),
                feesR = sorted.sumOf { it.feesR },
                fundingR = sorted.sumOf { it.fundingR },
                compoundedReturn = eq - 1,
                maxDrawdownPct = ddPct,
                outcomes = sorted.groupingBy { it.outcome }.eachCount(),
            )
        }
    }

    /**
     * The go-live checklist. Version 1 aimed for a 50%+ win rate; version 2 (trend-following) trades
     * win rate for size of wins, so pass [minWinRate] = null to drop that line.
     */
    fun checklist(minWinRate: Double? = 0.5, minTrades: Int = 100): List<Pair<String, Boolean>> = listOfNotNull(
        "$minTrades+ trades (got $trades)" to (trades >= minTrades),
        minWinRate?.let { "Win rate ${pct(it)}+ (got ${pct(winRate)})" to (winRate >= it) },
        "Expectancy +0.3R or more after costs (got ${r2(avgR)}R)" to (avgR >= 0.3),
        "Profit factor 1.5+ (got ${r2(profitFactor)})" to (profitFactor >= 1.5),
        "Max drawdown under 15R (got ${r2(maxDrawdownR)}R)" to (maxDrawdownR < 15.0),
    )

    private fun pct(v: Double) = String.format(java.util.Locale.US, "%.1f%%", v * 100)
    private fun r2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
