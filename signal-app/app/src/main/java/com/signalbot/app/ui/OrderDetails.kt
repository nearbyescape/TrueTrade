package com.signalbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.signalbot.app.data.ActionView
import com.signalbot.app.ui.theme.Palette
import com.signalbot.app.ui.theme.Type
import com.signalbot.engine.model.Assets
import com.signalbot.engine.strategy.formatPrice
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Full-screen order details, opened from a ticket or a journal entry. */
@Composable
fun OrderDetailsDialog(a: ActionView, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        OrderDetailsContent(a, onClose)
    }
}

/**
 * Entry, stop loss, take profit and quantity, each with its own Copy button, so they can be pasted
 * into the exchange one at a time. Numbers are copied plain (no thousands commas), as order forms expect.
 */
@Composable
fun OrderDetailsContent(a: ActionView, onClose: () -> Unit) {
    val symbol = Assets.byId(a.assetId)?.routes?.firstOrNull()?.symbol ?: a.assetId
    val dir = Palette.direction(a.direction)
    val enter = a.action == "ENTER"
    Column(
        Modifier.fillMaxSize().background(Palette.Night).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Close", style = Type.BodyStrong, color = Palette.Saffron,
            modifier = Modifier.clickable(onClick = onClose).padding(vertical = 8.dp))
        Column {
            Text(
                (if (a.leg == 0) "Break and retest" else "${a.leg}-day leg") + ", " + whenText(a.timeMs),
                style = Type.Small, color = Palette.Muted,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(a.assetName, style = Type.Headline, color = Palette.Ink, modifier = Modifier.weight(1f))
                Text(verb(a), style = Type.Title, color = dir)
            }
            Text("$symbol perpetual futures${if (enter) ", at the next open" else ""}", style = Type.Small, color = Palette.Muted)
        }

        when (a.action) {
            "ENTER" -> {
                CopyRow("Entry", a.close, "About this price: a market order at the next open, or a limit order here.")
                CopyRow("Stop loss", a.stop, if (a.target == null) "Moves with each new swing in your favour. Check the app for the latest."
                    else "Fixed. Exit at the target or here, whichever comes first.")
                if (a.target != null) CopyRow("Take profit", a.target, "A little before the nearest support or resistance.")
                else NoCopyRow("Take profit", "None: the stop trails and lets the trend run.")
                a.quantity?.let { q -> CopyRow("Quantity", q, a.sizeText ?: "", copyText = qty(q)) }
                if (a.target != null) {
                    val risk = abs(a.close - a.stop)
                    if (risk > 0) Text("Reward to risk about ${String.format(Locale.US, "%.1f", abs(a.target - a.close) / risk)} to 1.",
                        style = Type.Small, color = Palette.Muted)
                }
            }
            "MOVE_STOP" -> CopyRow("New stop loss", a.stop, "Replace the old stop on your open trade with this one.")
            else -> {
                CopyRow(if (a.action == "EXIT") "Exit price" else "Price", a.close, a.reasons.firstOrNull() ?: "")
                if (!a.stop.isNaN()) CopyRow("Stop at the time", a.stop, "")
            }
        }

        if (enter) {
            CopyAll(buildString {
                append("$symbol ${if (a.direction == "LONG") "LONG" else "SHORT"}")
                append(" entry ").append(plain(a.close))
                append(" SL ").append(plain(a.stop))
                a.target?.let { append(" TP ").append(plain(it)) }
                a.quantity?.let { append(" qty ").append(qty(it)) }
            })
        }

        if (a.reasons.isNotEmpty()) {
            Text("Why", style = Type.BodyStrong, color = Palette.Ink, modifier = Modifier.padding(top = 8.dp))
            a.reasons.forEach { Text(it, style = Type.Small, color = Palette.Muted) }
        }
        Text("Not financial advice. Check every number on the exchange before you send the order.",
            style = Type.Small, color = Palette.Muted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CopyRow(label: String, value: Double, note: String, copyText: String = plain(value)) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Card)
            .clickable { clipboard.setText(AnnotatedString(copyText)); copied = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.Small, color = Palette.Muted)
            Text(if (label == "Quantity") copyText else formatPrice(value), style = Type.BigFigure.copy(fontSize = Type.Title.fontSize,
                lineHeight = Type.Title.lineHeight), color = Palette.Ink)
            if (note.isNotEmpty()) Text(note, style = Type.Small, color = Palette.Muted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.padding(start = 12.dp))
        CopyPill(copied)
    }
}

@Composable
private fun NoCopyRow(label: String, note: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Palette.Line, RoundedCornerShape(14.dp))
        .padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(label, style = Type.Small, color = Palette.Muted)
        Text(note, style = Type.Body, color = Palette.Ink)
    }
}

@Composable
private fun CopyAll(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AccentAction(if (copied) "Copied all" else "Copy all") { clipboard.setText(AnnotatedString(text)); copied = true }
        Text(text, style = Type.Tiny, color = Palette.Muted, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun CopyPill(copied: Boolean) {
    val shape = RoundedCornerShape(50)
    Text(
        if (copied) "Copied" else "Copy",
        style = Type.BodyStrong,
        color = if (copied) Palette.Night else Palette.Saffron,
        modifier = Modifier.clip(shape).background(if (copied) Palette.Long else Palette.Night)
            .border(1.dp, if (copied) Palette.Long else Palette.Saffron, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Plain number for order forms: no commas, the same decimals the app shows. */
fun plain(p: Double): String {
    val a = abs(p)
    val decimals = when { a >= 1000 -> 1; a >= 1 -> 2; else -> 4 }
    return String.format(Locale.US, "%.${decimals}f", p)
}

private fun qty(q: Double): String = String.format(Locale.US, if (q >= 100) "%.1f" else if (q >= 1) "%.3f" else "%.4f", q)

private val whenFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US)
private fun whenText(ms: Long) = "signal at " + whenFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
