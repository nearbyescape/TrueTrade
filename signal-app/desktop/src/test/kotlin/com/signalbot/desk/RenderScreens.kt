package com.signalbot.desk

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Timeframe
import com.signalbot.engine.strategy.Resample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders the desktop window with simulated markets to PNGs (build/screens), so the design can be reviewed
 * from a cloud build with no screen. Same code paths as the real app, except prices are made up.
 */
fun main() {
    val out = File("build/screens").apply { mkdirs() }
    val state = DeskState(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    // Hour-aligned, like real exchange candles.
    val now = 1_795_300_000_000L - Math.floorMod(1_795_300_000_000L, 3_600_000L)
    val start = mapOf("BTC" to 91_000.0, "ETH" to 3_100.0, "SOL" to 140.0, "XRP" to 2.2, "GOLD" to 4_100.0, "SILVER" to 51.0)
    state.assets.forEachIndexed { n, a ->
        val books = simulate(start.getValue(a.id), seed = 40 + n * 7, end = now)
        for ((tf, candles) in books) {
            val b = state.market.book(a.id, tf)
            b.closed = candles.dropLast(1); b.forming = candles.last().copy(close = candles.last().open * 1.0015); b.source = "BINANCE"
        }
    }
    state.market.feed.value = Feed.LIVE
    state.market.lastUpdate.value = now
    state.loading = null
    for (a in state.assets) for (tf in Tf.entries) state.recompute(a.id, tf, alert = false)

    fun shot(name: String, w: Int = 1500, h: Int = 920) {
        val scene = ImageComposeScene(w * 5 / 4, h * 5 / 4, Density(1.25f)) { DeskTheme { DeskApp(state, ZoneId.of("Asia/Kolkata")) } }
        scene.render(0); scene.render(16_000_000)
        val img = scene.render(33_000_000)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("wrote $name")
    }

    state.selected = "BTC"; state.tf = Tf.H1
    shot("1_focus_btc_1h")
    // A chart with a trade or a signal, if the simulation produced one.
    val busy = state.views.value.values.sortedByDescending { it.tf.ordinal }.firstOrNull { it.actions.any { a -> a.action == "ENTER" } }
        ?: state.views.value.values.firstOrNull { it.trade != null && it.tf != Tf.D1 }
    busy?.let { state.selected = it.assetId; state.tf = it.tf; shot("2_focus_trade_${it.assetId.lowercase()}_${it.tf.label.lowercase()}") }
    state.tf = Tf.M15; state.grid = true
    shot("3_grid_15m")
    state.grid = false; state.selected = "XRP"; state.tf = Tf.M5
    shot("4_focus_xrp_5m")
    state.showSettings = true
    shot("5_settings")
    state.showSettings = false; state.selected = "BTC"; state.tf = Tf.H4
    shot("6_small_laptop_1280x720", 1280, 720)
    state.grid = true
    shot("7_small_laptop_grid", 1280, 720)
    kotlin.system.exitProcess(0)
}

/** A random-walk market with trending stretches: 1H history for two years, 5m for the last ten days. */
private fun simulate(p0: Double, seed: Int, end: Long): Map<Tf, List<Candle>> {
    val rnd = Random(seed)
    val h = 3_600_000L
    val m5 = 300_000L
    val fineBars = 10 * 288
    val fineStart = end - fineBars * m5
    val hours = 17_000
    val h1 = ArrayList<Candle>()
    var p = p0 * 0.7
    var drift = 0.0
    var t = fineStart - hours * h
    for (i in 0 until hours) {
        if (i % 300 == 0) drift = (rnd.nextDouble() - 0.47) * 0.0016
        val o = p
        val c = o * exp(drift + 0.006 * (rnd.nextDouble() - 0.5) + 0.0015 * sin(i / 30.0))
        h1 += Candle(t, o, maxOf(o, c) * (1 + 0.003 * rnd.nextDouble()), minOf(o, c) * (1 - 0.003 * rnd.nextDouble()), c, 100.0, t + h - 1)
        p = c; t += h
    }
    val scale = p0 / p
    val older = h1.map { it.copy(open = it.open * scale, high = it.high * scale, low = it.low * scale, close = it.close * scale) }
    p = older.last().close
    val fine = ArrayList<Candle>()
    t = fineStart
    for (i in 0 until fineBars) {
        if (i % 120 == 0) drift = (rnd.nextDouble() - 0.48) * 0.0004
        val o = p
        val c = o * exp(drift + 0.0016 * (rnd.nextDouble() - 0.5) + 0.0004 * sin(i / 18.0))
        fine += Candle(t, o, maxOf(o, c) * (1 + 0.0008 * rnd.nextDouble()), minOf(o, c) * (1 - 0.0008 * rnd.nextDouble()), c, 10.0, t + m5 - 1)
        p = c; t += m5
    }
    val allH1 = older + Resample.to(fine, Timeframe.H1)
    return mapOf(
        Tf.M5 to fine.takeLast(1000),
        Tf.M15 to Resample.to(fine, Timeframe.M15).takeLast(1000),
        Tf.H1 to allH1.takeLast(1000),
        Tf.H4 to Resample.from1h(allH1, Timeframe.H4).takeLast(1000),
        Tf.D1 to Resample.from1h(allH1, Timeframe.D1).takeLast(700),
    )
}
