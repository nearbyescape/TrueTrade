package com.signalbot.desk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.signalbot.engine.strategy.formatPrice
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the study found per timeframe for her full method (filled from the backtest). */
val TESTED: Map<Tf, String> = mapOf(
    Tf.D1 to "+0.40R a trade, +0.58R on months it never saw. About 2 trades a month. The only one that made money.",
    Tf.H4 to "Lost 0.11R a trade. Watch it, don't trade it with real money.",
    Tf.H1 to "Lost 0.42R a trade, mostly fake breaks.",
    Tf.M15 to "Lost 0.65R a trade over the last year; fees alone were 0.31R.",
    Tf.M5 to "Lost 0.72R a trade over the last year; fees alone were 0.38R.",
)

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK)
private val shortWhen = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.UK)

// ===================================================================================== small pieces

@Composable
fun Dots(checks: List<Boolean>, size: Int = 8) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        checks.forEach { ok -> Box(Modifier.size(size.dp).clip(RoundedCornerShape(50)).background(if (ok) Desk.Long else Desk.Hairline)) }
    }
}

@Composable
fun Tick(ok: Boolean) {
    Canvas(Modifier.size(16.dp)) {
        val c = if (ok) Desk.Long else Desk.Hairline
        drawCircle(c, radius = size.minDimension / 2, style = Stroke(1.6.dp.toPx()))
        if (ok) {
            val w = size.width
            drawLine(c, Offset(w * 0.28f, w * 0.52f), Offset(w * 0.44f, w * 0.68f), 1.8.dp.toPx(), StrokeCap.Round)
            drawLine(c, Offset(w * 0.44f, w * 0.68f), Offset(w * 0.73f, w * 0.36f), 1.8.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Text(
        text, style = T.Strong, color = if (selected) Desk.Base else Desk.Dim,
        modifier = Modifier.clip(shape).background(if (selected) Desk.Saffron else Color.Transparent)
            .border(1.dp, if (selected) Desk.Saffron else Desk.Hairline, shape).clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 4.dp),
    )
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = T.Name, color = Desk.Paper)
        content()
    }
}

@Composable
fun CopyRow(label: String, value: Double?, copyText: String? = value?.let { DeskAnalysis.plain(it) }, note: String? = null, shown: String? = null) {
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Desk.Raised)
            .clickable(enabled = copyText != null) { clip.setText(AnnotatedString(copyText!!)); copied = true }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = T.Tiny, color = Desk.Dim)
            Text(shown ?: value?.let { formatPrice(it) } ?: "None", style = T.Figure, color = Desk.Paper)
            note?.let { Text(it, style = T.Tiny, color = Desk.Faint) }
        }
        if (copyText != null) Text(if (copied) "Copied" else "Copy", style = T.Strong, color = if (copied) Desk.Long else Desk.Saffron)
    }
}

fun changePct(state: DeskState, id: String): Double? {
    val h = state.market.book(id, Tf.H1)
    val closed = h.closed
    if (closed.size < 25) return null
    val now = h.forming?.close ?: closed.last().close
    return (now / closed[closed.size - 24].close - 1) * 100
}

fun livePrice(state: DeskState, id: String, tf: Tf): Double? {
    val b = state.market.book(id, tf)
    return b.forming?.close ?: b.closed.lastOrNull()?.close
}

// ===================================================================================== watchlist

@Composable
fun Watchlist(state: DeskState, views: Map<Pair<String, Tf>, DeskView>, @Suppress("UNUSED_PARAMETER") tick: Long) {
    Column(Modifier.width(250.dp).fillMaxHeight().background(Desk.Panel).padding(vertical = 14.dp)) {
        Text("Watchlist", style = T.Title, color = Desk.Paper, modifier = Modifier.padding(horizontal = 16.dp))
        Text("Her checks on ${state.tf.label} candles", style = T.Small, color = Desk.Dim, modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 10.dp))
        for (a in state.assets) {
            val v = views[a.id to state.tf]
            val sel = a.id == state.selected
            val chg = changePct(state, a.id)
            Column(
                Modifier.fillMaxWidth().background(if (sel) Desk.Raised else Color.Transparent)
                    .clickable { state.selected = a.id; state.grid = false }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sel) Box(Modifier.size(width = 3.dp, height = 16.dp).background(Desk.Saffron))
                    if (sel) Spacer(Modifier.width(8.dp))
                    Text(a.displayName, style = T.Name, color = Desk.Paper, modifier = Modifier.weight(1f))
                    Text(livePrice(state, a.id, state.tf)?.let { formatPrice(it) } ?: "...", style = T.Price, color = Desk.Paper)
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.routes.first().symbol, style = T.Tiny, color = Desk.Faint)
                    Spacer(Modifier.width(10.dp))
                    v?.let { Dots(it.checks, 7) }
                    Spacer(Modifier.weight(1f))
                    chg?.let { Text(String.format(Locale.US, "%+.2f%% 24h", it), style = T.Tiny, color = if (it >= 0) Desk.Long else Desk.Short) }
                }
                v?.let { vv ->
                    val signal = vv.actions.any { it.action == "ENTER" }
                    val text = when {
                        signal -> vv.headline
                        vv.trade != null -> "In a ${vv.trade.direction.lowercase()}, ${String.format(Locale.US, "%+.1fR", vv.trade.rNow)}"
                        vv.checks.first() -> "Line broken, waiting for retest"
                        else -> null
                    }
                    text?.let { Text(it, style = T.Tiny, color = if (signal) Desk.Saffron else Desk.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Settings", style = T.Strong, color = Desk.Saffron, modifier = Modifier.padding(16.dp).clickable { state.showSettings = true })
    }
}

// ===================================================================================== chart header + focus

@Composable
fun FocusChart(state: DeskState, views: Map<Pair<String, Tf>, DeskView>, tick: Long, zone: ZoneId) {
    val a = state.assets.first { it.id == state.selected }
    val v = views[a.id to state.tf]
    val book = state.market.book(a.id, state.tf)
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(a.displayName, style = T.Title, color = Desk.Paper)
                Text("${a.routes.first().symbol} perpetual futures", style = T.Small, color = Desk.Dim)
            }
            Spacer(Modifier.width(22.dp))
            val px = livePrice(state, a.id, state.tf)
            Text(px?.let { formatPrice(it) } ?: "", style = T.BigPrice, color = Desk.Paper)
            Spacer(Modifier.width(10.dp))
            changePct(state, a.id)?.let { Text(String.format(Locale.US, "%+.2f%%", it), style = T.Price, color = if (it >= 0) Desk.Long else Desk.Short) }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tf.entries.forEach { t -> Chip(t.label, t == state.tf) { state.tf = t } }
                Spacer(Modifier.width(10.dp))
                Chip("One chart", !state.grid) { state.grid = false }
                Chip("All six", state.grid) { state.grid = true }
            }
        }
        Row(Modifier.padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(v?.headline ?: book.error?.let { "Couldn't load: $it" } ?: "Loading candles...", style = T.Body,
                color = if (v?.actions?.any { it.action == "ENTER" } == true) Desk.Saffron else Desk.Paper, modifier = Modifier.weight(1f))
            Text(closesIn(state.tf, tick), style = T.Small, color = Desk.Dim)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (v != null) DeskChart(v, book.forming, Modifier.fillMaxSize(), zone = zone)
        }
        Legend()
    }
}

@Composable
private fun Legend() {
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(Desk.Short, "Downtrend line")
        LegendItem(Desk.Long, "Uptrend line")
        LegendItem(Desk.Short.copy(alpha = 0.5f), "Higher-timeframe line (tagged 4H, 1D)", thick = true)
        LegendItem(Desk.Saffron, "Broken, waiting for retest")
        LegendItem(Desk.Paper.copy(alpha = 0.55f), "Your stop line")
        LegendItem(Desk.Level.copy(alpha = 0.6f), "Support/resistance")
    }
}

@Composable
private fun LegendItem(c: Color, text: String, thick: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 16.dp, height = if (thick) 4.dp else 2.dp).background(c))
        Spacer(Modifier.width(6.dp))
        Text(text, style = T.Tiny, color = Desk.Dim)
    }
}

/** "Candle closes in 03:12": her third check waits for this. */
fun closesIn(tf: Tf, @Suppress("UNUSED_PARAMETER") tick: Long, now: Long = System.currentTimeMillis()): String {
    val ms = tf.engine.millis
    val left = (now - Math.floorMod(now, ms) + ms - now) / 1000
    val h = left / 3600; val m = left % 3600 / 60; val s = left % 60
    return "${tf.label} candle closes in " + if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

// ===================================================================================== grid of six

@Composable
fun GridCharts(state: DeskState, views: Map<Pair<String, Tf>, DeskView>, tick: Long, zone: ZoneId) {
    Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("All six on ${state.tf.label}", style = T.Title, color = Desk.Paper)
            Spacer(Modifier.width(14.dp))
            Text(closesIn(state.tf, tick), style = T.Small, color = Desk.Dim)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tf.entries.forEach { t -> Chip(t.label, t == state.tf) { state.tf = t } }
                Spacer(Modifier.width(10.dp))
                Chip("One chart", !state.grid) { state.grid = false }
                Chip("All six", state.grid) { state.grid = true }
            }
        }
        Spacer(Modifier.height(10.dp))
        for (row in state.assets.chunked(3)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (a in row) {
                    val v = views[a.id to state.tf]
                    Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(Desk.Panel)
                        .clickable { state.selected = a.id; state.grid = false }.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.displayName, style = T.Name, color = Desk.Paper)
                            Spacer(Modifier.width(8.dp))
                            v?.let { Dots(it.checks, 7) }
                            Spacer(Modifier.weight(1f))
                            Text(livePrice(state, a.id, state.tf)?.let { formatPrice(it) } ?: "", style = T.Price, color = Desk.Paper)
                        }
                        Text(v?.headline ?: "Loading...", style = T.Tiny,
                            color = if (v?.actions?.any { it.action == "ENTER" } == true) Desk.Saffron else Desk.Dim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (v != null) DeskChart(v, state.market.book(a.id, state.tf).forming, Modifier.fillMaxSize(), compact = true, zone = zone)
                        }
                    }
                }
            }
        }
    }
}

// ===================================================================================== right panel

@Composable
fun SidePanel(state: DeskState, views: Map<Pair<String, Tf>, DeskView>, alerts: List<DeskAction>, zone: ZoneId) {
    val a = state.assets.first { it.id == state.selected }
    val v = views[a.id to state.tf]
    Column(Modifier.width(330.dp).fillMaxHeight().background(Desk.Panel).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("Her three checks", style = T.Title, color = Desk.Paper)
        Text("${a.displayName}, ${state.tf.label} candles", style = T.Small, color = Desk.Dim, modifier = Modifier.padding(bottom = 8.dp))
        DeskAnalysis.CHECKS.forEachIndexed { i, text ->
            val ok = v?.checks?.getOrNull(i) == true
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Tick(ok); Spacer(Modifier.width(10.dp))
                Text("${i + 1}. $text", style = T.Body, color = if (ok) Desk.Paper else Desk.Dim)
            }
        }

        val entry = v?.actions?.firstOrNull { it.action == "ENTER" }
        when {
            entry != null -> Section(if (entry.direction == "LONG") "Buy signal" else "Sell short signal") {
                Text("At the next candle's open. Click a price to copy it.", style = T.Small, color = Desk.Dim)
                CopyRow("Entry (about)", entry.price)
                CopyRow("Stop loss", entry.stop, note = "On the opposing trendline; it moves with the line")
                if (entry.target != null) CopyRow("Take profit", entry.target)
                else CopyRow("Take profit", null, null, shown = "No fixed target", note = "She lets the trend run; the stop trails the opposing line")
                entry.quantity?.let { q -> CopyRow("Quantity", q, DeskAnalysis.qty(q), shown = DeskAnalysis.qty(q), note = entry.sizeText) }
            }
            v?.trade != null -> Section("Open trade") {
                val t = v!!.trade!!
                Text("${if (t.direction == "LONG") "Long" else "Short"} since ${shortWhen.withZone(zone).format(Instant.ofEpochMilli(t.sinceMs))}, " +
                    String.format(Locale.US, "%+.1fR now", t.rNow), style = T.Body, color = Desk.Paper)
                CopyRow("Entry", t.entry)
                CopyRow("Stop now", t.stop, note = "Rises along the opposing line each candle")
                if (t.target != null) CopyRow("Take profit", t.target)
                else CopyRow("Take profit", null, null, shown = "No fixed target", note = "Exit when price breaks the opposing line")
                t.quantity?.let { q -> CopyRow("Quantity", q, DeskAnalysis.qty(q), shown = DeskAnalysis.qty(q), note = t.sizeText) }
            }
            else -> Section("No trade") { Text("Waiting for price to meet all three checks.", style = T.Body, color = Desk.Dim) }
        }

        Section("Top-down, every timeframe") {
            Tf.entries.reversed().forEach { t ->
                val tv = views[a.id to t]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (t == state.tf) Desk.Raised else Color.Transparent)
                    .clickable { state.tf = t }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.label, style = T.Strong, color = Desk.Paper, modifier = Modifier.width(38.dp))
                    tv?.let { Dots(it.checks, 7) }
                    Spacer(Modifier.width(10.dp))
                    Text(tv?.let { short(it) } ?: "...", style = T.Tiny, color = Desk.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (v != null && v.recent.isNotEmpty()) Section("What happened on this chart") {
            v.recent.take(5).forEach { e ->
                Column {
                    Text(shortWhen.withZone(zone).format(Instant.ofEpochMilli(e.timeMs)), style = T.Tiny, color = Desk.Faint)
                    Text(e.text, style = T.Small, color = when (e.color) { "signal" -> Desk.Saffron; "bad" -> Desk.Short; "good" -> Desk.Long; else -> Desk.Dim })
                }
            }
        }

        Section("Alerts") {
            if (alerts.isEmpty()) Text("Entry signals on ${state.settings.alertTfs.sortedBy { it.ordinal }.joinToString { it.label }} candles appear here " +
                "and as desktop notifications.", style = T.Small, color = Desk.Dim)
            alerts.take(10).forEach { al ->
                Column(Modifier.fillMaxWidth().clickable { state.selected = al.assetId; state.tf = al.tf; state.grid = false }.padding(vertical = 3.dp)) {
                    Text("${al.name} ${al.tf.label}: " + when (al.action) {
                        "ENTER" -> if (al.direction == "LONG") "Buy" else "Sell short"
                        "MOVE_STOP" -> "Move stop to ${al.stop?.let { formatPrice(it) }}"
                        "EXIT" -> "Closed"
                        else -> al.action.lowercase()
                    }, style = T.Strong, color = if (al.action == "ENTER") Desk.Saffron else Desk.Paper)
                    Text(shortWhen.withZone(zone).format(Instant.ofEpochMilli(al.timeMs)), style = T.Tiny, color = Desk.Faint)
                }
            }
        }
    }
}

private fun short(v: DeskView): String = when {
    v.actions.any { it.action == "ENTER" } -> "Signal now"
    v.trade != null -> "In a ${v.trade.direction.lowercase()}, " + String.format(Locale.US, "%+.1fR", v.trade.rNow)
    v.checks.first() -> "Broken, waiting for retest"
    else -> "Watching"
}

// ===================================================================================== status bar

@Composable
fun StatusBar(state: DeskState, feed: Feed, lastUpdate: Long, tick: Long, zone: ZoneId) {
    Row(Modifier.fillMaxWidth().height(30.dp).background(Desk.Panel).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        val (c, t) = when (feed) {
            Feed.LIVE -> Desk.Long to "Live from Binance futures"
            Feed.POLLING -> Desk.Warn to "Updating every 15 seconds (Bybit)"
            Feed.CONNECTING -> Desk.Warn to "Connecting"
            Feed.OFFLINE -> Desk.Short to "Offline, reconnecting"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(c))
            Spacer(Modifier.width(8.dp))
            Text(t, style = T.Small, color = Desk.Dim)
        }
        state.loading?.let { Text(it, style = T.Small, color = Desk.Warn) }
        Text("Alerts on ${state.settings.alertTfs.sortedBy { it.ordinal }.joinToString { it.label }.ifEmpty { "nothing" }}", style = T.Small, color = Desk.Dim)
        Spacer(Modifier.weight(1f))
        if (lastUpdate > 0) Text("Last price ${clock.withZone(zone).format(Instant.ofEpochMilli(lastUpdate))}", style = T.Small, color = Desk.Faint)
        Text("${clock.withZone(zone).format(Instant.ofEpochMilli(System.currentTimeMillis() + 0 * tick))} ${zone.id}", style = T.Small, color = Desk.Dim)
    }
}
