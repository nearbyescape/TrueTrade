package com.signalbot.engine.strategy

/**
 * Every rule threshold in one place. Defaults follow the build plan; the app's Settings screen
 * and the backtester (phase 3) change these, never the rule code.
 */
data class StrategyConfig(
    // --- Trend filter (hard gate) ---
    val dailyEmaSlow: Int = 200,
    val dailyEmaFast: Int = 50,
    val emaFast: Int = 50,
    val emaSlow: Int = 200,
    /** 4H EMA 50 must be higher than it was this many 4H bars ago. */
    val emaRisingBars: Int = 10,
    val adxLength: Int = 14,
    val minAdx: Double = 20.0,
    val strongAdx: Double = 25.0,

    // --- Market condition (hard gate) ---
    val atrLength: Int = 14,
    /** 4H ATR% is compared with its median over this many 4H bars. */
    val regimeMedianBars: Int = 100,
    val regimeMin: Double = 0.5,
    val regimeMax: Double = 2.0,
    /** Inside this band the market counts as "steady" (+4 context points). */
    val steadyMin: Double = 0.75,
    val steadyMax: Double = 1.5,
    /** Times with no new signals (big scheduled news), as epoch-ms ranges. */
    val newsBlackouts: List<LongRange> = emptyList(),

    // --- Setups ---
    /** How many recent 1H bars (including the trigger bar) may hold the pullback/retest/sweep. */
    val setupLookback1h: Int = 6,
    /** EMA zone = 4H EMA 50 +/- this many 4H ATRs. */
    val emaZoneAtr: Double = 0.5,
    val fibShallow: Double = 0.5,
    val fibDeep: Double = 0.618,
    /** Impulse leg must be at least this many 4H ATRs to count for a retracement. */
    val minImpulseAtr: Double = 2.0,
    val swing4hLeft: Int = 3,
    val swing4hRight: Int = 3,
    val breakoutRangeBars: Int = 20,
    val breakoutVolumeMult: Double = 1.5,
    /** Breakouts older than this many 4H bars are stale. */
    val breakoutRecent4h: Int = 12,
    /** A retest must come within this many 1H ATRs of the breakout level. */
    val retestAtr1h: Double = 0.3,
    val sweepSwingLeft: Int = 3,
    val sweepSwingRight: Int = 3,
    /** Swing lows older than this many 1H bars are ignored for sweeps. */
    val sweepLookback1h: Int = 48,

    // --- Trigger (1H) ---
    val bosSwingLeft: Int = 2,
    val bosSwingRight: Int = 2,
    /** The 1H swing high being broken must be this recent. */
    val bosRecentBars: Int = 12,
    val rsiLength: Int = 14,
    val rsiZoneLow: Double = 40.0,
    val rsiZoneHigh: Double = 50.0,
    val rsiTurnLookback: Int = 5,

    // --- Volume ---
    val volumeSma: Int = 20,
    /** Hard minimum: trigger volume vs the previous 20-bar average. */
    val minVolumeRatio: Double = 1.0,
    val goodVolumeRatio: Double = 1.2,
    val strongVolumeRatio: Double = 1.5,
    val obvLookback: Int = 20,

    // --- Stop and target (hard gates) ---
    val stopBufferAtr1h: Double = 0.2,
    val maxStopAtr4h: Double = 1.5,
    /** Stops tighter than this get hit by normal noise. */
    val minStopAtr1h: Double = 0.5,
    val minRoomR: Double = 3.0,
    /** Target when no resistance is found overhead ("open sky"). */
    val openSkyTargetR: Double = 5.0,
    val resistanceLookback4h: Int = 300,
    val resistanceLookback1d: Int = 365,
    val dailySwingLeft: Int = 2,
    val dailySwingRight: Int = 2,

    // --- Trade plan ---
    val tp1R: Double = 1.5,
    val tp1Fraction: Double = 0.4,
    val tp2R: Double = 3.0,
    val tp2Fraction: Double = 0.3,
    val runnerTrailAtr4h: Double = 3.0,

    // --- Alerts ---
    val alertScore: Int = 75,
    val aPlusScore: Int = 85,
    /** Futures can be shorted, so downtrend setups are signalled too. Set false for longs only. */
    val allowShorts: Boolean = true,
) {
    init {
        require(tp1Fraction > 0 && tp2Fraction > 0 && tp1Fraction + tp2Fraction < 1.0) { "TP fractions must leave a runner" }
        require(tp1R > 0 && tp2R > tp1R) { "TP2 must be beyond TP1" }
        require(minRoomR >= tp2R) { "Room to target must cover TP2" }
        require(fibShallow < fibDeep) { "fibShallow must be < fibDeep" }
        require(regimeMin < regimeMax && steadyMin < steadyMax)
        require(aPlusScore >= alertScore)
    }

    val runnerFraction: Double get() = 1.0 - tp1Fraction - tp2Fraction

    /** Bars of history each timeframe needs before the first signal can be judged. */
    val warmup1h: Int get() = maxOf(volumeSma + 2, rsiLength * 3, 26 * 3, sweepLookback1h + 2, obvLookback + 2)
    val warmup4h: Int get() = maxOf(emaSlow, regimeMedianBars + atrLength, adxLength * 2) + emaRisingBars + 2
    val warmup1d: Int get() = dailyEmaSlow + 2
}
