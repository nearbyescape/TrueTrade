package com.signalbot.engine.tools

import com.signalbot.engine.data.MarketDataException
import com.signalbot.engine.model.Assets
import com.signalbot.engine.strategy.AssetDataLoader
import com.signalbot.engine.strategy.Direction
import com.signalbot.engine.strategy.EnsembleEngine
import com.signalbot.engine.strategy.LegAction
import com.signalbot.engine.strategy.PositionSizer
import com.signalbot.engine.strategy.formatPrice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Live scan with the version 3 strategy (breakout ensemble: 20/30/60/90-day legs).
 *
 *   ./gradlew :engine:scan --args="100000 0.5 2"     (capital, risk % PER LEG, leverage)
 *
 * Run it after 05:30 IST (00:00 UTC), when the daily candle has closed. For each asset it lists:
 *  - TODAY: legs to enter or exit at the next open,
 *  - OPEN: legs in a trade, with the stop to use now,
 *  - the last 14 days of leg events.
 */
fun main(args: Array<String>) {
    val capital = args.getOrNull(0)?.toDouble() ?: 100_000.0
    val riskPct = args.getOrNull(1)?.toDouble() ?: 0.5
    val leverage = args.getOrNull(2)?.toDouble() ?: 2.0
    val engine = EnsembleEngine()
    val loader = AssetDataLoader()
    val date = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US).withZone(ZoneId.of("Asia/Kolkata"))

    println("Version 3: breakout ensemble, legs ${engine.cfg.legs} days. Watch list: ${Assets.DEFAULT.joinToString { it.id }} futures")
    println("Sizing: capital ${"%,.0f".format(capital)}, risk $riskPct% per leg (max ${riskPct * engine.cfg.legs.size}% per asset), leverage ${leverage}x\n")

    for (asset in Assets.DEFAULT) {
        val data = try {
            loader.load(asset, AssetDataLoader.Counts(h1 = 48, h4 = 10, d1 = 500))
        } catch (e: MarketDataException) {
            println("== ${asset.displayName}: could not load data (${e.message})\n"); continue
        }
        val d = data.d1
        val last = d.lastIndex
        val replay = engine.replay(data)
        val close = d.close[last]
        println("== ${asset.displayName} (${data.source}), daily close ${formatPrice(close)} on ${date.format(Instant.ofEpochMilli(d.openTime[last]))}")

        val today = replay.events.filter { it.dayIndex == last }
        if (today.isEmpty()) println("   TODAY: nothing to do.")
        for (e in today) {
            if (e.action == LegAction.ENTER) {
                val size = PositionSizer.size(capital, riskPct / 100, e.close, e.stop, leverage)
                println("   TODAY: ENTER ${e.direction} ${e.leg}-day leg at the next open (~${formatPrice(e.close)}), stop ${formatPrice(e.stop)}")
                println("      Size ${"%.4f".format(size.quantity)} units = ${"%,.0f".format(size.notional)}, margin ${"%,.0f".format(size.margin)}, " +
                    "loss at stop ~${"%,.0f".format(size.riskAmount)}" + if (size.liquidationSafe) "" else
                    " (liquidation too close: use at most ${PositionSizer.maxSafeLeverage(e.close, e.stop)}x)")
            } else {
                println("   TODAY: EXIT ${e.direction} ${e.leg}-day leg at the next open")
            }
            e.reasons.forEach { println("      - $it") }
        }
        val stillOpen = replay.open.filter { o -> today.none { it.leg == o.leg && it.action == LegAction.ENTER } }
        for (o in stillOpen) {
            val sign = if (o.direction == Direction.LONG) 1 else -1
            val r = sign * (close - o.signalClose) / (sign * (o.signalClose - o.initialStop))
            println("   OPEN: ${o.direction} ${o.leg}-day leg since ${date.format(Instant.ofEpochMilli(d.openTime[o.entryDayIndex]))}, " +
                "entered ~${formatPrice(o.signalClose)}, now ${"%+.2f".format(r)}R. Stop to use now: ${formatPrice(o.stop)} " +
                "(exit only if a DAILY close is ${if (sign > 0) "below" else "above"} it)")
        }
        val recent = replay.events.filter { it.dayIndex in (last - 14) until last }
        if (recent.isNotEmpty()) println("   Last 14 days: " + recent.joinToString("; ") {
            "${date.format(Instant.ofEpochMilli(d.openTime[it.dayIndex]))} ${it.action} ${it.direction} ${it.leg}d"
        })
        println()
    }
}
