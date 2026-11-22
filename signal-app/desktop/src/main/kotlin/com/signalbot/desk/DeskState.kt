package com.signalbot.desk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.signalbot.engine.model.Assets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Everything the window shows, and the alert logic. One instance for the app's life. */
class DeskState(private val scope: CoroutineScope) {
    val assets = Assets.DESKTOP
    var settings by mutableStateOf(DeskSettings.load())
        private set
    val market = LiveMarket(assets, scope)
    private val viewMap = ConcurrentHashMap<Pair<String, Tf>, DeskView>()
    val views = MutableStateFlow<Map<Pair<String, Tf>, DeskView>>(emptyMap())
    val feedLog = MutableStateFlow(AlertLog.load())
    var selected by mutableStateOf("BTC")
    var tf by mutableStateOf(Tf.H1)
    var grid by mutableStateOf(false)
    var loading by mutableStateOf<String?>("Starting")
    var showSettings by mutableStateOf(false)
    /** Set by the window: shows a desktop notification. */
    var notify: (title: String, text: String) -> Unit = { _, _ -> }

    fun start() {
        // Off the socket's thread: several charts can close at the same moment (e.g. 00:00 UTC closes all five).
        market.onClose = { b, n -> scope.launch(Dispatchers.Default) { recompute(b.asset.id, b.tf, newCandles = n) } }
        scope.launch(Dispatchers.IO) {
            market.start { msg -> loading = msg }
            loading = null
        }
    }

    fun view(id: String, tf: Tf) = views.value[id to tf]

    fun update(s: DeskSettings) {
        settings = s
        s.save()
        scope.launch(Dispatchers.Default) { for (a in assets) for (t in Tf.entries) recompute(a.id, t, alert = false) }
    }

    /** Re-runs her rules for one asset and timeframe after a candle closed, and alerts anything new. */
    @Synchronized
    fun recompute(id: String, tf: Tf, alert: Boolean = true, newCandles: Int = 1) {
        val asset = assets.first { it.id == id }
        val book = market.book(id, tf)
        if (book.closed.size < 120) return
        val higher = Tf.entries.filter { it.engine.millis > tf.engine.millis }.associateWith { market.book(id, it).closed }
        val v = runCatching { DeskAnalysis.build(asset, tf, book.closed, higher, market.book(id, Tf.D1).closed, settings,
            actionCandles = newCandles.coerceIn(1, 3)) }.getOrNull() ?: return
        viewMap[id to tf] = v
        views.value = HashMap(viewMap)
        if (alert) alertNew(v)
    }

    private fun alertNew(v: DeskView) {
        val fresh = v.actions.filter { a ->
            a.tf in settings.alertTfs && Seen.add(a.key)
        }
        if (fresh.isEmpty()) return
        feedLog.value = AlertLog.add(fresh)
        for (a in fresh) {
            val title = when (a.action) {
                "ENTER" -> "${a.name} ${a.tf.label}: ${if (a.direction == "LONG") "BUY" else "SELL SHORT"} signal"
                "MOVE_STOP" -> "${a.name} ${a.tf.label}: move your stop"
                "EXIT" -> "${a.name} ${a.tf.label}: trade closed"
                "BREAK" -> "${a.name} ${a.tf.label}: trendline broken"
                else -> "${a.name} ${a.tf.label}: retest"
            }
            notify(title, a.text)
        }
        if (settings.sound && fresh.any { it.action == "ENTER" }) Chime.play()
    }
}

/** Alert keys already shown, so each signal alerts once, even across restarts. */
object Seen {
    private val file = File(DeskSettings.dir, "seen.txt")
    private val keys: MutableSet<String> = runCatching { file.readLines().toMutableSet() }.getOrDefault(mutableSetOf())

    @Synchronized
    fun add(key: String): Boolean {
        if (!keys.add(key)) return false
        runCatching { file.writeText(keys.toList().takeLast(5_000).joinToString("\n")) }
        return true
    }
}

/** The alert feed, newest first, kept on disk (last 300). */
object AlertLog {
    private val file = File(DeskSettings.dir, "alerts.tsv")

    fun load(): List<DeskAction> = runCatching { file.readLines().mapNotNull(::parse) }.getOrDefault(emptyList())

    @Synchronized
    fun add(new: List<DeskAction>): List<DeskAction> {
        val all = (new.sortedByDescending { it.timeMs } + load()).take(300)
        runCatching { file.writeText(all.joinToString("\n", transform = ::line)) }
        return all
    }

    private fun line(a: DeskAction) = listOf(a.timeMs, a.assetId, a.name, a.symbol, a.tf.name, a.action, a.direction, a.price,
        a.stop ?: "", a.target ?: "", a.quantity ?: "", a.text.replace('\t', ' ').replace('\n', ' ')).joinToString("\t")

    private fun parse(s: String): DeskAction? = runCatching {
        val c = s.split('\t')
        DeskAction(c[1], c[2], c[3], Tf.valueOf(c[4]), c[5], c[6], c[7].toDouble(), c[8].toDoubleOrNull(), c[9].toDoubleOrNull(),
            c[10].toDoubleOrNull(), null, c[11], c[0].toLong())
    }.getOrNull()
}

/** A soft two-note chime for entry signals: gentle enough to hear all day. */
object Chime {
    fun play() {
        Thread {
            runCatching {
                val rate = 44_100f
                val notes = listOf(659.25 to 0.16, 880.0 to 0.32)
                val pcm = notes.flatMap { (f, d) ->
                    val n = (rate * d).toInt()
                    List(n) { i -> (sin(2 * PI * f * i / rate) * exp(-4.0 * i / n) * 0.22 * Short.MAX_VALUE).toInt() }
                }
                val bytes = ByteArray(pcm.size * 2)
                pcm.forEachIndexed { i, v -> bytes[2 * i] = (v and 0xff).toByte(); bytes[2 * i + 1] = (v shr 8 and 0xff).toByte() }
                val fmt = AudioFormat(rate, 16, 1, true, false)
                AudioSystem.getSourceDataLine(fmt).apply { open(fmt); start(); write(bytes, 0, bytes.size); drain(); close() }
            }
        }.apply { isDaemon = true }.start()
    }
}
