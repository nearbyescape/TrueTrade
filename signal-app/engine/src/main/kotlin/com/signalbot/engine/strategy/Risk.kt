package com.signalbot.engine.strategy

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/** Futures position for one signal, sized by risk (not by leverage). */
data class PositionSize(
    /** Contracts in units of the asset (coins, ounces), rounded down to the exchange's step. */
    val quantity: Double,
    /** Position value at entry. */
    val notional: Double,
    /** What you lose if the stop is hit, entry and exit fees included. */
    val riskAmount: Double,
    val leverage: Double,
    /** Margin the exchange locks for this position: notional / leverage. */
    val margin: Double,
    /** Estimated isolated-margin liquidation price. */
    val liquidationPrice: Double,
    /** True when liquidation sits at least 2x further away than the stop, so the stop always hits first. */
    val liquidationSafe: Boolean,
    /** True when the size was cut because capital x leverage could not cover it. */
    val cappedByCapital: Boolean,
)

object PositionSizer {
    /** Binance USD-M taker fee (0.05%). Limit orders (maker) are cheaper. */
    const val DEFAULT_FEE = 0.0005
    /** Maintenance margin rate for small positions on major perpetuals. */
    const val DEFAULT_MAINTENANCE = 0.005

    /**
     * Size so that a stop-out costs [riskFraction] of [capital], fees on entry and exit included:
     *
     *   quantity = capital x risk% / (|entry - stop| + fee x (entry + stop))
     *
     * Leverage does NOT change how much you risk. It only changes how much margin is locked,
     * and how close liquidation is. Example: Rs 1,00,000, 1% risk, entry 100, stop 96 -> about
     * 244 units (Rs 24,400 position). At 2x that needs Rs 12,200 margin; liquidation near 50.5,
     * far below the stop.
     */
    fun size(
        capital: Double,
        riskFraction: Double,
        entry: Double,
        stop: Double,
        leverage: Double = 1.0,
        feeRate: Double = DEFAULT_FEE,
        maintenanceMargin: Double = DEFAULT_MAINTENANCE,
        step: Double = 0.0,
    ): PositionSize {
        require(capital > 0 && riskFraction > 0 && riskFraction < 0.1) { "risk must be between 0 and 10% of capital" }
        require(entry > 0 && stop > 0 && entry != stop)
        require(leverage >= 1.0)
        val long = stop < entry
        val lossPerUnit = abs(entry - stop) + feeRate * (entry + stop)
        var qty = capital * riskFraction / lossPerUnit
        val maxQty = capital * leverage / entry
        val capped = qty > maxQty
        qty = min(qty, maxQty)
        if (step > 0) qty = floor(qty / step + 1e-9) * step
        val liq = liquidationPrice(entry, leverage, long, maintenanceMargin)
        val safe = abs(entry - liq) >= 2 * abs(entry - stop)
        return PositionSize(qty, qty * entry, qty * lossPerUnit, leverage, qty * entry / leverage, liq, safe, capped)
    }

    /**
     * Isolated-margin liquidation estimate (ignores fees and funding, so treat as approximate):
     * long  = entry x (1 - 1/leverage + mmr),  short = entry x (1 + 1/leverage - mmr).
     */
    fun liquidationPrice(entry: Double, leverage: Double, long: Boolean, maintenanceMargin: Double = DEFAULT_MAINTENANCE): Double =
        if (long) entry * (1 - 1 / leverage + maintenanceMargin) else entry * (1 + 1 / leverage - maintenanceMargin)

    /** Highest whole leverage that keeps liquidation at least [buffer] x the stop distance away. */
    fun maxSafeLeverage(entry: Double, stop: Double, buffer: Double = 2.0, maintenanceMargin: Double = DEFAULT_MAINTENANCE): Int {
        val stopPct = abs(entry - stop) / entry
        return maxOf(1, floor(1.0 / (buffer * stopPct + maintenanceMargin)).toInt())
    }
}

/**
 * Stops repeat alerts on the same move: after a signal for an asset and direction, the next one is
 * held back for [hours] hours. A better setup (higher grade) still gets through.
 */
class SignalCooldown(private val hours: Int = 12) {
    private data class Key(val assetId: String, val direction: Direction)
    private val last = HashMap<Key, Signal>()

    /** True if [signal] should alert; also records it when it does. */
    fun admit(signal: Signal): Boolean {
        val key = Key(signal.assetId, signal.direction)
        val prev = last[key]
        val cooling = prev != null && signal.timeMs - prev.timeMs < hours * 3_600_000L
        val upgrade = prev != null && signal.grade.ordinal < prev.grade.ordinal
        if (cooling && !upgrade) return false
        last[key] = signal
        return true
    }

    /** Restore state after an app restart (from the journal). */
    fun remember(signal: Signal) {
        last[Key(signal.assetId, signal.direction)] = signal
    }
}
