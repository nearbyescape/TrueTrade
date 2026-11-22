package com.signalbot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.signalbot.app.data.SetupResult
import com.signalbot.app.data.SetupTest
import com.signalbot.app.data.SetupView
import com.signalbot.app.ui.theme.Palette
import com.signalbot.app.ui.theme.Type
import com.signalbot.engine.model.Asset
import com.signalbot.engine.strategy.formatPrice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the study found per timeframe (Binance futures, Jul 2023 - Nov 2026, after costs, trend filter on). */
fun testedText(tf: String): String = when (tf) {
    "1d" -> "Daily candles: +0.37R a trade in testing (+0.23R on months it never saw), about 1.6 trades a month."
    "4h" -> "4-hour candles lost 0.10R a trade in testing. Watch and learn with it; don't trade it with real money yet."
    else -> "1-hour candles lost 0.31R a trade in testing, mostly to fake breaks and fees. Watch and learn only."
}

private val tfLabels = listOf("1d" to "Daily", "4h" to "4 hours", "1h" to "1 hour")

// ================================================================================== Chart tab

@Composable
fun ChartContent(
    assets: List<Asset>,
    assetId: String,
    timeframe: String,
    setupTimeframe: String,
    view: SetupView?,
    loading: Boolean,
    onAsset: (String) -> Unit,
    onTimeframe: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column {
                Text("Break and retest, drawn by the app", style = Type.Small, color = Palette.Muted)
                Spacer(Modifier.height(4.dp))
                Text(assets.firstOrNull { it.id == assetId }?.displayName ?: assetId, style = Type.Headline, color = Palette.Ink)
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                assets.forEach { a -> Chip(a.displayName, a.id == assetId) { onAsset(a.id) } }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                tfLabels.forEach { (tf, label) -> Chip(label, tf == timeframe) { onTimeframe(tf) } }
                Spacer(Modifier.weight(1f))
                Text(if (loading) "Loading..." else "Refresh", style = Type.BodyStrong,
                    color = if (loading) Palette.Muted else Palette.Saffron,
                    modifier = Modifier.clickable(enabled = !loading, onClick = onRefresh).padding(vertical = 6.dp))
            }
        }
        item {
            val chart = view?.chart
            when {
                chart != null -> CandleChart(chart)
                view?.error != null -> Text("Couldn't load this chart: ${view.error}", style = Type.Body, color = Palette.Warning)
                else -> Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Card),
                    contentAlignment = Alignment.Center) {
                    Text(if (loading) "Loading candles..." else "No chart yet", style = Type.Body, color = Palette.Muted)
                }
            }
        }
        if (view != null && view.error == null) {
            item { SetupStatus(view) }
            items(view.actions.filter { it.action == "ENTER" || it.action == "MOVE_STOP" || it.action == "EXIT" }) { OrderTicket(it) }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(testedText(timeframe), style = Type.Small, color = if (timeframe == "1d") Palette.Muted else Palette.Warning)
                if (timeframe != setupTimeframe) {
                    Text("Alerts follow the ${tfLabels.first { it.first == setupTimeframe }.second.lowercase()} chart. Change it in Settings.",
                        style = Type.Small, color = Palette.Muted)
                }
                Text("Pinch to zoom, drag to scroll, tap a candle to read it.", style = Type.Small, color = Palette.Muted)
            }
        }
    }
}

/** Her three entry questions with ticks, the trade in progress, and the one-line state. */
@Composable
fun SetupStatus(v: SetupView, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(v.headline, style = Type.BodyStrong, color = Palette.Ink)
        v.criteria.forEachIndexed { i, (text, ok) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tick(ok)
                Spacer(Modifier.width(10.dp))
                Text("${i + 1}. $text", style = Type.Small, color = if (ok) Palette.Ink else Palette.Muted)
            }
        }
        v.trade?.let { t ->
            HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(vertical = 4.dp))
            Row {
                Figure(if (t.direction == "LONG") "Long from" else "Short from", formatPrice(t.entry), Modifier.weight(1f))
                Figure("Stop now", formatPrice(t.stop), Modifier.weight(1f))
                Figure(if (t.target != null) "Target" else "Result now", t.target?.let { formatPrice(it) } ?: rText(t.rNow), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Tick(ok: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val c = if (ok) Palette.Long else Palette.Line
        drawCircle(c, radius = size.minDimension / 2, style = Stroke(2.dp.toPx()))
        if (ok) {
            val w = size.width
            drawLine(c, Offset(w * 0.28f, w * 0.52f), Offset(w * 0.44f, w * 0.68f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(c, Offset(w * 0.44f, w * 0.68f), Offset(w * 0.73f, w * 0.36f), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = Type.Small,
        color = if (selected) Palette.Night else Palette.Ink,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Palette.Saffron else Color.Transparent)
            .border(1.dp, if (selected) Palette.Saffron else Palette.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

// ================================================================================== Today section

/** Her setup on the Today screen: orders first, then one line per asset. Tap a line to open its chart. */
@Composable
fun SetupTodaySection(setup: SetupResult?, onOpenChart: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Break and retest", style = Type.Title, color = Palette.Ink, modifier = Modifier.padding(top = 20.dp))
        if (setup == null) {
            Text("Not checked yet.", style = Type.Small, color = Palette.Muted)
            return@Column
        }
        Text("${tfLabels.first { it.first == setup.timeframe }.second} candles. ${testedText(setup.timeframe)}",
            style = Type.Small, color = if (setup.timeframe == "1d") Palette.Muted else Palette.Warning)
        setup.assets.flatMap { it.actions }.filter { it.action != "BREAK" && it.action != "RETEST" }.forEach { OrderTicket(it) }
        setup.assets.forEach { v ->
            Column(Modifier.fillMaxWidth().clickable { onOpenChart(v.assetId) }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.name, style = Type.Name, color = Palette.Ink, modifier = Modifier.weight(1f))
                    v.criteria.forEach { (_, ok) ->
                        Box(Modifier.padding(start = 5.dp).size(9.dp).clip(RoundedCornerShape(50))
                            .background(if (ok) Palette.Long else Palette.Line))
                    }
                }
                Text(v.headline, style = Type.Small, color = if (v.error != null) Palette.Warning else Palette.Muted,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

// ================================================================================== Record section

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

@Composable
fun SetupRecordSection(test: SetupTest?, running: Boolean, progress: String?, onRun: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
        Text("Her break and retest", style = Type.Title, color = Palette.Ink, modifier = Modifier.padding(top = 20.dp))
        Text(
            "Tested on Binance futures, July 2023 to November 2026, after fees, slippage and funding. Daily candles with the trend " +
                "filter made +0.37R a trade (67 trades). 4-hour lost 0.10R and 1-hour lost 0.31R a trade.",
            style = Type.Body, color = Palette.Ink,
        )
        if (test != null) {
            val zone = ZoneId.systemDefault()
            Text("Tested on this phone: ${dateFmt.format(Instant.ofEpochMilli(test.fromMs).atZone(zone))} to " +
                "${dateFmt.format(Instant.ofEpochMilli(test.toMs).atZone(zone))}, ${test.settingsText}.", style = Type.Small, color = Palette.Muted)
            val o = test.overall
            Text(rText(o.totalR), style = Type.BigFigure, color = if (o.totalR >= 0) Palette.Long else Palette.Short)
            Text(
                if (o.trades == 0) "No closed trades in this period."
                else "${o.trades} trades, ${pct(o.winRate)} won, ${rText(o.avgR)} on average. Profit factor " +
                    "${String.format(Locale.US, "%.2f", test.profitFactor)}, worst drop ${String.format(Locale.US, "%.1f", test.maxDrawdownR)}R.",
                style = Type.Body, color = Palette.Ink,
            )
            val maxAbs = test.perAsset.maxOfOrNull { kotlin.math.abs(it.totalR) } ?: 0.0
            test.perAsset.forEach { r ->
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(r.label, style = Type.Name, color = Palette.Ink, modifier = Modifier.weight(1f))
                        Text(rText(r.totalR), style = Type.Figure, color = if (r.totalR >= 0) Palette.Long else Palette.Short)
                    }
                    Spacer(Modifier.height(6.dp))
                    RBar(r.totalR, maxAbs)
                    Text(if (r.trades == 0) "No closed trades." else "${r.trades} trades, ${pct(r.winRate)} won, ${rText(r.avgR)} on average",
                        style = Type.Small, color = Palette.Muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
            test.notes.forEach { Text(it, style = Type.Small, color = Palette.Muted) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentAction(if (running) "Testing..." else if (test == null) "Test it on this phone" else "Test again", enabled = !running, onClick = onRun)
            progress?.let { Text(it, style = Type.Small, color = Palette.Muted, modifier = Modifier.padding(start = 14.dp)) }
        }
        Text("Downloads Binance futures history (daily: 4 years, 4-hour: 2 years, 1-hour: 1 year) and runs the same rules the alerts use.",
            style = Type.Small, color = Palette.Muted)
    }
}

private fun pct(v: Double) = if (v.isNaN()) "-" else String.format(Locale.US, "%.0f%%", v * 100)

// ================================================================================== Settings section

data class SetupSettingsState(val timeframe: String, val target: Boolean, val trend: Boolean, val risk: String, val steps: Boolean)

@Composable
fun SetupSettingsSection(initial: SetupSettingsState, onChange: (SetupSettingsState) -> SetupSettingsState) {
    var st by remember { mutableStateOf(initial) }
    var risk by remember { mutableStateOf(initial.risk) }
    fun set(n: SetupSettingsState) { st = onChange(n); risk = st.risk }
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = Palette.Night, checkedTrackColor = Palette.Saffron,
        uncheckedThumbColor = Palette.Muted, uncheckedTrackColor = Palette.Card, uncheckedBorderColor = Palette.Line,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Break and retest", style = Type.Title, color = Palette.Ink, modifier = Modifier.padding(top = 20.dp))
        Text("Chart the alerts use", style = Type.BodyStrong, color = Palette.Ink)
        tfLabels.forEach { (tf, label) ->
            Row(Modifier.fillMaxWidth().clickable { set(st.copy(timeframe = tf)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 3.dp).size(18.dp).clip(RoundedCornerShape(50))
                    .border(2.dp, if (st.timeframe == tf) Palette.Saffron else Palette.Line, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center) {
                    if (st.timeframe == tf) Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Palette.Saffron))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(label, style = Type.Body, color = Palette.Ink)
                    Text(testedText(tf), style = Type.Small, color = if (tf == "1d") Palette.Muted else Palette.Warning)
                }
            }
        }
        ToggleRow("Only trade with the daily trend", "Longs only above the 50-day average, shorts only below. Her top-down step; " +
            "it halved the losses on 1-hour and 4-hour charts.", st.trend, switchColors) { set(st.copy(trend = it)) }
        ToggleRow("Take profit at support or resistance", "Off: trail the stop under each new swing and let the trend run. " +
            "On: exit a little before the nearest level. Trailing tested better.", st.target, switchColors) { set(st.copy(target = it)) }
        ToggleRow("Also alert me on the break and the retest", "The entry is always alerted. These are the early steps.",
            st.steps, switchColors) { set(st.copy(steps = it)) }
        OutlinedTextField(risk, { risk = it }, label = { Text("Risk per trade, %") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.Saffron, unfocusedBorderColor = Palette.Line, focusedLabelColor = Palette.Saffron,
                unfocusedLabelColor = Palette.Muted, cursorColor = Palette.Saffron, focusedTextColor = Palette.Ink, unfocusedTextColor = Palette.Ink,
            ),
            textStyle = Type.Figure, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("She suggests 1-2% of capital. Uses the capital and leverage above.") },
            modifier = Modifier.fillMaxWidth())
        AccentAction("Save risk") { set(st.copy(risk = risk)) }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, on: Boolean, colors: androidx.compose.material3.SwitchColors, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.Body, color = Palette.Ink)
            Text(detail, style = Type.Small, color = Palette.Muted)
        }
        Switch(checked = on, onCheckedChange = onChange, colors = colors)
    }
}
