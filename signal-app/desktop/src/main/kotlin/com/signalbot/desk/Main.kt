package com.signalbot.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.time.ZoneId

fun main() = application {
    val scope = rememberCoroutineScope()
    val state = remember { DeskState(scope).also { it.start() } }
    val tray = rememberTrayState()
    var visible by remember { mutableStateOf(true) }
    var toldAboutTray by remember { mutableStateOf(false) }
    state.notify = { title, text -> tray.sendNotification(Notification(title, text, Notification.Type.Info)) }
    if (isTraySupported) {
        Tray(
            icon = painterResource("icon.png"), state = tray, tooltip = "TrueTrade Desk",
            onAction = { visible = true },
            menu = {
                Item("Open TrueTrade Desk", onClick = { visible = true })
                Item("Quit TrueTrade Desk", onClick = ::exitApplication)
            },
        )
    }
    Window(
        // Closing the window keeps the app (and its alerts) running in the tray; quit from the tray icon.
        onCloseRequest = {
            if (isTraySupported) {
                visible = false
                if (!toldAboutTray) {
                    toldAboutTray = true
                    state.notify("TrueTrade Desk is still running", "Alerts keep coming. Open or quit it from the tray icon.")
                }
            } else exitApplication()
        },
        visible = visible,
        title = "TrueTrade Desk",
        icon = painterResource("icon.png"),
        // Opens maximised: fits any screen, from a 1366x768 laptop up.
        state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1400.dp, 860.dp)),
    ) {
        DeskTheme { DeskApp(state) }
    }
}

/** The whole window: watchlist, chart (one or all six), her checks and the trade, status bar. */
@Composable
fun DeskApp(state: DeskState, zone: ZoneId = ZoneId.systemDefault()) {
    val views by state.views.collectAsState()
    val alerts by state.feedLog.collectAsState()
    val feed by state.market.feed.collectAsState()
    val lastUpdate by state.market.lastUpdate.collectAsState()
    // Live prices redraw four times a second at most: smooth, but calm on the eyes and the CPU.
    val tick by produceState(0L) { while (true) { value = state.market.tick.value + System.currentTimeMillis() / 1000; delay(250) } }
    Box(Modifier.fillMaxSize().background(Desk.Base)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Watchlist(state, views, tick)
                Box(Modifier.weight(1f).fillMaxSize()) {
                    if (state.grid) GridCharts(state, views, tick, zone) else FocusChart(state, views, tick, zone)
                }
                SidePanel(state, views, alerts, zone)
            }
            StatusBar(state, feed, lastUpdate, tick, zone)
        }
        if (state.showSettings) SettingsSheet(state)
    }
}

// ===================================================================================== settings

@Composable
fun SettingsSheet(state: DeskState) {
    var s by remember { mutableStateOf(state.settings) }
    var capital by remember { mutableStateOf(trim(s.capital)) }
    var risk by remember { mutableStateOf(trim(s.riskPct)) }
    var lev by remember { mutableStateOf(trim(s.leverage)) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).clip(RoundedCornerShape(14.dp)).background(Desk.Panel).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Settings", style = T.Title, color = Desk.Paper)
            Text("Position size", style = T.Name, color = Desk.Paper)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Capital", capital, Modifier.weight(1f)) { capital = it }
                Field("Risk per trade, %", risk, Modifier.weight(1f)) { risk = it }
                Field("Leverage", lev, Modifier.weight(1f)) { lev = it }
            }
            Text("She risks 1-2% of capital per trade. Leverage changes margin, not risk.", style = T.Small, color = Desk.Dim)
            Text("Alert me on entry signals from", style = T.Name, color = Desk.Paper)
            Tf.entries.reversed().forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { s = s.copy(alertTfs = if (t in s.alertTfs) s.alertTfs - t else s.alertTfs + t) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 10.dp).width(16.dp).clip(RoundedCornerShape(4.dp))
                        .border(1.5.dp, if (t in s.alertTfs) Desk.Saffron else Desk.Hairline, RoundedCornerShape(4.dp))
                        .background(if (t in s.alertTfs) Desk.Saffron else Color.Transparent)) { Text(" ", style = T.Tiny) }
                    Text(t.label, style = T.Strong, color = Desk.Paper, modifier = Modifier.width(44.dp))
                    Text(TESTED[t] ?: "", style = T.Small, color = Desk.Dim)
                }
            }
            Toggle("Only trade with the daily trend", "Longs above the 50-day average, shorts below.", s.trendFilter) { s = s.copy(trendFilter = it) }
            Toggle("Take profit at support or resistance", "Off: ride the trend on the opposing line, as she does for the whole move.", s.takeProfit) { s = s.copy(takeProfit = it) }
            Toggle("Also alert the break and the retest", "The entry always alerts. These are the earlier steps.", s.stepAlerts) { s = s.copy(stepAlerts = it) }
            Toggle("Soft chime on entry signals", "", s.sound) { s = s.copy(sound = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Chip("Save", true) {
                    state.update(s.copy(
                        capital = capital.toDoubleOrNull()?.takeIf { it > 0 } ?: s.capital,
                        riskPct = risk.toDoubleOrNull()?.takeIf { it > 0 && it <= 5 } ?: s.riskPct,
                        leverage = lev.toDoubleOrNull()?.takeIf { it in 1.0..50.0 } ?: s.leverage,
                    ))
                    state.showSettings = false
                }
                Chip("Cancel", false) { state.showSettings = false }
            }
            Text("Not financial advice. Check every number on the exchange before you send an order.", style = T.Tiny, color = Desk.Faint)
        }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    Column(modifier) {
        Text(label, style = T.Tiny, color = Desk.Dim)
        BasicTextField(value, onChange, singleLine = true, textStyle = T.Figure.copy(color = Desk.Paper), cursorBrush = SolidColor(Desk.Saffron),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Desk.Raised).padding(10.dp))
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.Body, color = Desk.Paper)
            if (detail.isNotEmpty()) Text(detail, style = T.Small, color = Desk.Dim)
        }
        Spacer(Modifier.width(12.dp))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Desk.Base, checkedTrackColor = Desk.Saffron,
            uncheckedThumbColor = Desk.Dim, uncheckedTrackColor = Desk.Raised, uncheckedBorderColor = Desk.Hairline))
    }
}

private fun trim(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
