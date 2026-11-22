package com.signalbot.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.signalbot.app.data.ActionView
import com.signalbot.app.data.AssetView
import com.signalbot.app.data.ScanResult
import com.signalbot.app.ui.theme.Palette
import com.signalbot.app.ui.theme.Type
import com.signalbot.engine.model.Asset
import com.signalbot.engine.strategy.formatPrice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US)
private val shortDayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.US)
private val clockFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private fun zoned(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone)

private val numberWords = listOf("No", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")

/** "No orders for today's open." / "Two orders for today's open." */
fun headline(n: Int): String {
    val count = numberWords.getOrNull(n) ?: n.toString()
    return "$count order${if (n == 1) "" else "s"} for today's open."
}

private val screenPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 32.dp)

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.Title, color = Palette.Ink, modifier = modifier.padding(top = 20.dp, bottom = 4.dp))
}

@Composable
private fun AccentAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = Type.BodyStrong,
        color = if (enabled) Palette.Saffron else Palette.Muted,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, if (enabled) Palette.Saffron else Palette.Line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

// ================================================================================== Today

@Composable
fun TodayContent(
    scan: ScanResult?,
    scanning: Boolean,
    message: String?,
    onCheck: () -> Unit,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val actions = scan?.assets?.flatMap { it.actions }.orEmpty()
    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column {
                Text(dayFmt.format(zoned(nowMs, zone)), style = Type.Small, color = Palette.Muted)
                Spacer(Modifier.height(6.dp))
                Text(if (scan == null) "Not checked yet." else headline(actions.size), style = Type.Headline, color = Palette.Ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (scan == null) "Check now to load today's signals."
                    else "Checked at ${clockFmt.format(zoned(scan.timeMs, zone))}. New signals come after each daily close at 05:30.",
                    style = Type.Small, color = Palette.Muted,
                )
                message?.let { Text(it, style = Type.Small, color = Palette.Warning, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(14.dp))
                AccentAction(if (scanning) "Checking..." else "Check now", enabled = !scanning, onClick = onCheck)
            }
        }
        items(actions) { OrderTicket(it) }
        if (scan != null) {
            item { SectionTitle("Positions") }
            items(scan.assets) { PositionRow(it) }
        }
    }
}

@Composable
fun PositionRow(a: AssetView, initiallyExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val entering = a.actions.filter { it.action == "ENTER" }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(a.name, style = Type.Name, color = Palette.Ink, modifier = Modifier.weight(1f))
            Text(if (a.close.isNaN()) "" else formatPrice(a.close), style = Type.Figure, color = Palette.Ink)
        }
        Spacer(Modifier.height(8.dp))
        if (a.error != null) {
            Text("Prices didn't load: ${a.error}. Check again in a minute.", style = Type.Small, color = Palette.Warning)
        } else {
            LegRail(a.open, entering)
            Spacer(Modifier.height(10.dp))
            if (a.open.isEmpty()) {
                Text(summary(a), style = Type.Small, color = Palette.Muted)
            } else {
                StopLadder(a.close, a.open, caption = summary(a))
            }
        }
        if (expanded && a.error == null) {
            Spacer(Modifier.height(12.dp))
            PriceLine(a.closes, a.open)
            Text("Last ${a.closes.size} daily closes. Dashed lines are your stops.", style = Type.Tiny, color = Palette.Muted)
            a.open.forEach { o ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${o.leg}-day ${o.direction.lowercase()}", style = Type.BodyStrong, color = Palette.direction(o.direction),
                        modifier = Modifier.width(118.dp))
                    Text("stop ${formatPrice(o.stop)}", style = Type.Small, color = Palette.Ink, modifier = Modifier.weight(1f))
                    Text(rText(o.rNow), style = Type.Small, color = if (o.rNow >= 0) Palette.Long else Palette.Short)
                }
            }
            Text("Exit a leg only when a daily close crosses its stop.", style = Type.Tiny, color = Palette.Muted,
                modifier = Modifier.padding(top = 8.dp))
        }
        HorizontalDivider(Modifier.padding(top = 14.dp), color = Palette.Line)
    }
}

private fun summary(a: AssetView): String {
    if (a.open.isEmpty()) return if (a.actions.isEmpty()) "Flat. No legs open." else "Flat until the next open."
    val parts = a.open.groupBy { it.direction }.map { (d, legs) ->
        val n = numberWords.getOrNull(legs.size)?.lowercase() ?: legs.size.toString()
        "$n leg${if (legs.size == 1) "" else "s"} ${d.lowercase()}"
    }
    val best = a.open.maxOf { it.rNow }
    return parts.joinToString(" and ").replaceFirstChar { it.uppercase() } + ", best ${rText(best)}"
}

// ================================================================================== Record

@Composable
fun RecordContent(scan: ScanResult?) {
    val rows = scan?.assets?.mapNotNull { a -> a.paper?.let { a.name to it } }.orEmpty()
    val legs = rows.sumOf { it.second.trades }
    val total = rows.sumOf { it.second.totalR }
    val maxAbs = rows.maxOfOrNull { kotlin.math.abs(it.second.totalR) } ?: 0.0
    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column {
                Text("What the strategy did by itself over the last 180 days", style = Type.Small, color = Palette.Muted)
                Spacer(Modifier.height(6.dp))
                if (scan == null) {
                    Text("Nothing to show until the first check.", style = Type.Headline, color = Palette.Ink)
                } else {
                    Text(rText(total), style = Type.BigFigure, color = if (total >= 0) Palette.Long else Palette.Short)
                    Text("from $legs closed legs, after fees and slippage", style = Type.Body, color = Palette.Ink)
                }
            }
        }
        items(rows) { (name, p) ->
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(name, style = Type.Name, color = Palette.Ink, modifier = Modifier.weight(1f))
                    Text(rText(p.totalR), style = Type.Figure, color = if (p.totalR >= 0) Palette.Long else Palette.Short)
                }
                Spacer(Modifier.height(6.dp))
                RBar(p.totalR, maxAbs)
                Text(
                    if (p.trades == 0) "No closed legs in this period."
                    else "${p.trades} legs, ${String.format(Locale.US, "%.0f", p.winRate * 100)}% won, ${rText(p.avgR)} on average",
                    style = Type.Small, color = Palette.Muted, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            Column(Modifier.padding(top = 10.dp)) {
                SectionTitle("The longer test")
                Text(
                    "On Binance futures from July 2023 to November 2026, these rules closed 284 legs on your five assets: " +
                        "about seven a month, 43% winners, +0.64R on average. The months it had never seen averaged +0.35R.",
                    style = Type.Body, color = Palette.Ink,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Expect losing runs. Legs on one asset lose together; the worst run was 27 legs, about 15% at 0.5% risk per leg.",
                    style = Type.Small, color = Palette.Muted,
                )
            }
        }
    }
}

// ================================================================================== Journal

@Composable
fun JournalContent(journal: List<ActionView>, zone: ZoneId = ZoneId.systemDefault()) {
    val byDay = journal.groupBy { zoned(it.timeMs - 1, zone).toLocalDate() }.toSortedMap(compareByDescending { it })
    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column {
                Text("Every order the app has given you", style = Type.Small, color = Palette.Muted)
                Text("Journal", style = Type.Headline, color = Palette.Ink)
            }
        }
        if (journal.isEmpty()) {
            item { Text("Orders appear here after the first signal.", style = Type.Body, color = Palette.Muted) }
        }
        byDay.forEach { (date, entries) ->
            item {
                Text(shortDayFmt.format(date), style = Type.Name, color = Palette.Ink, modifier = Modifier.padding(top = 16.dp))
            }
            items(entries) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "${a.leg}d",
                        style = Type.Tiny,
                        color = Palette.direction(a.direction),
                        modifier = Modifier
                            .width(40.dp)
                            .padding(top = 3.dp)
                            .border(1.dp, Palette.direction(a.direction).copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                            .padding(vertical = 2.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${a.assetName}: ${verb(a).lowercase().replaceFirstChar { it.uppercase() }}", style = Type.BodyStrong, color = Palette.Ink)
                        Text(
                            if (a.action == "ENTER") "Close ${formatPrice(a.close)}, stop ${formatPrice(a.stop)}"
                            else "Close ${formatPrice(a.close)} crossed the stop ${formatPrice(a.stop)}",
                            style = Type.Small, color = Palette.Muted,
                        )
                    }
                }
            }
        }
    }
}

// ================================================================================== Settings

data class SettingsState(val capital: String, val riskPerLeg: String, val leverage: String, val enabled: Map<String, Boolean>)

@Composable
fun SettingsContent(
    initial: SettingsState,
    assets: List<Asset>,
    onSave: (SettingsState) -> SettingsState,
    onToggle: (String, Boolean) -> Unit,
    onBatterySettings: () -> Unit,
) {
    var capital by remember { mutableStateOf(initial.capital) }
    var risk by remember { mutableStateOf(initial.riskPerLeg) }
    var leverage by remember { mutableStateOf(initial.leverage) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var saved by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Saffron, unfocusedBorderColor = Palette.Line, focusedLabelColor = Palette.Saffron,
        unfocusedLabelColor = Palette.Muted, cursorColor = Palette.Saffron, focusedTextColor = Palette.Ink, unfocusedTextColor = Palette.Ink,
    )
    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = Type.Headline, color = Palette.Ink) }
        item { SectionTitle("Position size") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(capital, { capital = it; saved = false }, label = { Text("Capital") }, colors = fieldColors,
                    textStyle = Type.Figure, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(risk, { risk = it; saved = false }, label = { Text("Risk per leg, %") }, colors = fieldColors,
                    textStyle = Type.Figure, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("0.5% suggested. Up to four legs per asset can be open, so 2% per asset at most.") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(leverage, { leverage = it; saved = false }, label = { Text("Leverage") }, colors = fieldColors,
                    textStyle = Type.Figure, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("Changes margin, not risk. The app warns if liquidation would come before a stop.") },
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccentAction("Save sizes") {
                        val s = onSave(SettingsState(capital, risk, leverage, enabled))
                        capital = s.capital; risk = s.riskPerLeg; leverage = s.leverage
                        saved = true
                    }
                    if (saved) Text("Saved. New sizes show from the next check.", style = Type.Small, color = Palette.Muted,
                        modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
        item { SectionTitle("Assets") }
        items(assets) { asset ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(asset.displayName, style = Type.Name, color = Palette.Ink)
                    Text("${asset.routes.first().symbol} perpetual on Binance, Bybit as backup", style = Type.Small, color = Palette.Muted)
                }
                Switch(
                    checked = enabled[asset.id] == true,
                    onCheckedChange = { on -> enabled = enabled + (asset.id to on); onToggle(asset.id, on) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.Night, checkedTrackColor = Palette.Saffron,
                        uncheckedThumbColor = Palette.Muted, uncheckedTrackColor = Palette.Card, uncheckedBorderColor = Palette.Line,
                    ),
                )
            }
        }
        item { SectionTitle("Background checks") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The app checks after 05:30 every morning and every three hours after that. Some phones pause apps to save battery; " +
                    "if signals arrive late, let this app run without battery limits.", style = Type.Body, color = Palette.Ink)
                AccentAction("Open battery settings", onClick = onBatterySettings)
            }
        }
        item { SectionTitle("How it decides") }
        item {
            Text(
                "Each asset has four legs: 20, 30, 60 and 90 days. A leg buys when the daily close is the highest of its period and " +
                    "above the 200-day average, or sells short on the mirror image. Its stop sits at the middle of that period's closing " +
                    "range and only moves your way. A leg closes when a daily close crosses its stop. Not financial advice: paper trade first.",
                style = Type.Small, color = Palette.Muted,
            )
        }
    }
}
