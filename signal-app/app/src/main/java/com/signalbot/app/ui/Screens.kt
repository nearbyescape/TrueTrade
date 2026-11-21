package com.signalbot.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.signalbot.app.MainViewModel
import com.signalbot.app.data.ActionView
import com.signalbot.app.data.AssetView
import com.signalbot.engine.model.Assets
import com.signalbot.engine.strategy.formatPrice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val longColor = Color(0xFF2E9E6B)
private val shortColor = Color(0xFFD0573E)
private val timeFmt = DateTimeFormatter.ofPattern("dd MMM, HH:mm", Locale.US)
private val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)

private fun time(ms: Long) = timeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
private fun date(ms: Long) = dateFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
private fun r(v: Double) = if (v.isNaN()) "-" else String.format(Locale.US, "%+.2fR", v)
private fun pct(v: Double) = if (v.isNaN()) "-" else String.format(Locale.US, "%.0f%%", v * 100)

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp))
}

// ------------------------------------------------------------------------------------------ Today

@Composable
fun TodayScreen(vm: MainViewModel) {
    val scan = vm.scan
    val actions = scan?.assets?.flatMap { it.actions }.orEmpty()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                Text("TrueTrade Signals", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (scan == null) "No scan yet" else "Last scan ${time(scan.timeMs)}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.scanNow() }, enabled = !vm.scanning) {
                    Text(if (vm.scanning) "Scanning..." else "Scan now")
                }
                vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(4.dp))
                Text("New signals come after the daily close at 05:30 IST. Act at the next open.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Title("Today's actions") }
        if (actions.isEmpty()) {
            item { Text("Nothing to do today.") }
        } else {
            items(actions) { ActionCard(it) }
        }
        item { Title("Open legs and stops") }
        if (scan != null) {
            items(scan.assets) { AssetCard(it) }
        }
    }
}

@Composable
fun ActionCard(a: ActionView) {
    val color = if (a.direction == "LONG") longColor else shortColor
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${a.assetName}: ${a.action} ${a.direction}, ${a.leg}-day leg", color = color, fontWeight = FontWeight.Bold)
            if (a.action == "ENTER") {
                Text("Enter at the next open (about ${formatPrice(a.close)}). Stop ${formatPrice(a.stop)}")
            } else {
                Text("Close this leg at the next open.")
            }
            a.sizeText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            a.reasons.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun AssetCard(a: AssetView) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${a.name}  ${if (a.close.isNaN()) "" else formatPrice(a.close)}", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            if (a.error != null) {
                Text("Couldn't load: ${a.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (a.open.isEmpty()) {
                Text("No open legs", style = MaterialTheme.typography.bodySmall)
            }
            a.open.forEach { o ->
                val color = if (o.direction == "LONG") longColor else shortColor
                Spacer(Modifier.height(4.dp))
                Text("${o.direction} ${o.leg}-day leg since ${date(o.sinceMs)}: ${r(o.rNow)}", color = color)
                Text("Stop now ${formatPrice(o.stop)}. Exit only if a daily close is ${if (o.direction == "LONG") "below" else "above"} it.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (a.source.isNotEmpty()) {
                Text("Daily close ${date(a.closeTimeMs - 1)}, data: ${a.source}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Results

@Composable
fun ResultsScreen(vm: MainViewModel) {
    val scan = vm.scan
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                Text("Paper results", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("What the strategy did by itself over the last 180 days, closed legs only, after 0.08% costs per side (funding not included).",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (scan == null) {
            item { Text("Run a scan first.") }
        } else {
            val all = scan.assets.mapNotNull { it.paper?.let { p -> it.name to p } }
            val trades = all.sumOf { it.second.trades }
            val total = all.sumOf { it.second.totalR }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("All assets: $trades legs, total ${r(total)}", fontWeight = FontWeight.Bold)
                        if (trades > 0) Text("Average ${r(total / trades)} per leg")
                    }
                }
            }
            items(all) { (name, p) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(name, fontWeight = FontWeight.SemiBold)
                        Text("${p.trades} legs, win rate ${pct(p.winRate)}, average ${r(p.avgR)}, total ${r(p.totalR)}")
                    }
                }
            }
        }
        item {
            Column {
                Title("Backtest (Jul 2023 to Nov 2026)")
                Text("Your 5 assets on Binance futures: 284 legs, about 7 a month, 43% winners, +0.64R average, profit factor 2.25. " +
                    "Unseen period from Jul 2025: +0.35R average. Worst drawdown 15.6% at 0.5% risk per leg. Losing streaks of 20+ legs happen.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Journal

@Composable
fun JournalScreen(vm: MainViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column {
                Text("Journal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Every leg action the app has signalled, newest first.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (vm.journal.isEmpty()) item { Text("Nothing yet.") }
        items(vm.journal) { a ->
            Column {
                Text("${date(a.timeMs - 1)}  ${a.assetName}: ${a.action} ${a.direction} ${a.leg}d",
                    color = if (a.direction == "LONG") longColor else shortColor)
                Text("Close ${formatPrice(a.close)}, stop ${formatPrice(a.stop)}", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Settings

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s = vm.settings
    val context = LocalContext.current
    var capital by remember { mutableStateOf(s.capital.toString()) }
    var risk by remember { mutableStateOf(s.riskPerLegPct.toString()) }
    var leverage by remember { mutableStateOf(s.leverage.toString()) }
    var saved by remember { mutableStateOf(false) }
    val enabled = remember { mutableStateMapOf<String, Boolean>().apply { Assets.DEFAULT.forEach { put(it.id, s.isEnabled(it.id)) } } }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = capital, onValueChange = { capital = it; saved = false }, label = { Text("Capital") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = risk, onValueChange = { risk = it; saved = false }, label = { Text("Risk per leg, % (0.5 suggested)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = leverage, onValueChange = { leverage = it; saved = false }, label = { Text("Leverage (1-3 suggested)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    capital.toDoubleOrNull()?.takeIf { it > 0 }?.let { s.capital = it }
                    risk.toDoubleOrNull()?.takeIf { it > 0 && it <= 5 }?.let { s.riskPerLegPct = it }
                    leverage.toDoubleOrNull()?.takeIf { it >= 1 && it <= 20 }?.let { s.leverage = it }
                    capital = s.capital.toString(); risk = s.riskPerLegPct.toString(); leverage = s.leverage.toString()
                    saved = true
                }) { Text("Save") }
                if (saved) Text("Saved. Sizes update on the next scan.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Title("Assets") }
        items(Assets.DEFAULT) { asset ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(asset.displayName)
                    Text(asset.routes.first().toString(), style = MaterialTheme.typography.labelSmall)
                }
                Switch(checked = enabled[asset.id] == true, onCheckedChange = { on ->
                    enabled[asset.id] = on
                    s.setEnabled(asset.id, on)
                })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Title("Reliable background scans")
                Text("Some phones stop background work to save battery. If signals arrive late, allow this app to run without battery limits.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("Open battery settings") }
                Title("Strategy")
                Text("Version 3 breakout ensemble: 20, 30, 60 and 90-day closing breakouts per asset, 200-day EMA trend filter, " +
                    "stop at the middle of each range that only moves in your favour, exit on a daily close beyond it. " +
                    "Not financial advice: paper trade first.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
