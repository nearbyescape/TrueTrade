package com.signalbot.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.signalbot.app.data.ActionView
import com.signalbot.app.data.OpenLegView
import com.signalbot.app.ui.theme.Palette
import com.signalbot.app.ui.theme.Type
import com.signalbot.engine.strategy.formatPrice
import java.util.Locale
import kotlin.math.abs

val LEGS = listOf(20, 30, 60, 90)

fun rText(v: Double) = if (v.isNaN()) "-" else String.format(Locale.US, "%+.1fR", v)

/** Plain-language order for a leg action. */
fun verb(a: ActionView): String = when {
    a.action == "ENTER" && a.direction == "LONG" -> "Buy"
    a.action == "ENTER" -> "Sell short"
    a.direction == "LONG" -> "Sell to close"
    else -> "Buy to close"
}

/**
 * An order for the next open, styled as a ticket: saffron edge = something to do.
 * Tapping "Why" opens the reasons.
 */
@Composable
fun OrderTicket(a: ActionView, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val dirColor = Palette.direction(a.direction)
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min) // lets the saffron edge match the ticket's height
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Card)
            .animateContentSize(),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(Palette.Saffron))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(a.assetName, style = Type.Name, color = Palette.Ink, modifier = Modifier.weight(1f))
                Text(verb(a), style = Type.BodyStrong, color = dirColor)
            }
            Text("${a.leg}-day leg, at the next open", style = Type.Small, color = Palette.Muted)
            Spacer(Modifier.height(10.dp))
            if (a.action == "ENTER") {
                Row {
                    Figure("Entry about", formatPrice(a.close), Modifier.weight(1f))
                    Figure("Stop", formatPrice(a.stop), Modifier.weight(1f))
                }
                a.sizeText?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = Type.Small, color = Palette.Muted)
                }
            } else {
                Text("Daily close ${formatPrice(a.close)} crossed the stop ${formatPrice(a.stop)}.", style = Type.Small, color = Palette.Muted)
            }
            Text(
                if (open) "Hide reasons" else "Why",
                style = Type.BodyStrong,
                color = Palette.Saffron,
                modifier = Modifier.padding(top = 8.dp).clickable { open = !open }.padding(vertical = 4.dp),
            )
            if (open) {
                a.reasons.forEach { Text(it, style = Type.Small, color = Palette.Ink, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = Type.Tiny, color = Palette.Muted)
        Text(value, style = Type.Figure, color = Palette.Ink)
    }
}

/**
 * The four legs of an asset, 20 to 90 days. Filled = in a trade (green long, pink short),
 * outlined = flat, saffron outline = entering at the next open.
 */
@Composable
fun LegRail(open: List<OpenLegView>, entering: List<ActionView>, modifier: Modifier = Modifier) {
    val label = LEGS.joinToString { leg ->
        val o = open.firstOrNull { it.leg == leg }
        "$leg-day ${o?.direction?.lowercase() ?: "flat"}"
    }
    Row(modifier.fillMaxWidth().semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LEGS.forEach { leg ->
            val o = open.firstOrNull { it.leg == leg }
            val e = entering.firstOrNull { it.leg == leg }
            val fill = o?.let { Palette.direction(it.direction) }
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(26.dp)
                    .clip(shape)
                    .background(fill?.copy(alpha = 0.22f) ?: Color.Transparent)
                    .border(1.dp, when { e != null -> Palette.Saffron; fill != null -> fill; else -> Palette.Line }, shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$leg",
                    style = Type.Tiny,
                    color = fill ?: if (e != null) Palette.Saffron else Palette.Muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Where today's price sits against every open stop. Ticks are stops (coloured by direction),
 * the cream dot is the price. Distance to the nearest stop is printed under it.
 */
@Composable
fun StopLadder(price: Double, open: List<OpenLegView>, modifier: Modifier = Modifier, caption: String? = null) {
    if (open.isEmpty() || price.isNaN()) return
    val stops = open.map { it.stop }
    val lo = minOf(price, stops.min())
    val hi = maxOf(price, stops.max())
    val pad = (hi - lo).coerceAtLeast(price * 0.002) * 0.12
    val min = lo - pad
    val max = hi + pad
    val nearest = open.minBy { abs(price - it.stop) }
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            fun x(v: Double) = ((v - min) / (max - min)).toFloat() * size.width
            val mid = size.height / 2
            drawLine(Palette.Line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            open.forEach { o ->
                val c = Palette.direction(o.direction)
                // The span you're protecting: from the stop to the price.
                drawLine(c.copy(alpha = 0.35f), Offset(x(o.stop), mid), Offset(x(price), mid), strokeWidth = 4.dp.toPx())
                drawLine(c, Offset(x(o.stop), mid - 8.dp.toPx()), Offset(x(o.stop), mid + 8.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(Palette.Ink, radius = 5.dp.toPx(), center = Offset(x(price), mid))
        }
        val pct = abs(price - nearest.stop) / price * 100
        caption?.let { Text(it, style = Type.Small, color = Palette.Ink, modifier = Modifier.padding(top = 2.dp)) }
        Text(
            "Nearest stop ${formatPrice(nearest.stop)} (${nearest.leg}-day), ${String.format(Locale.US, "%.1f", pct)}% away",
            style = Type.Tiny,
            color = Palette.Muted,
        )
    }
}

/** Recent daily closes with each open leg's stop as a dashed line. */
@Composable
fun PriceLine(closes: List<Double>, open: List<OpenLegView>, modifier: Modifier = Modifier) {
    val values = closes.filter { !it.isNaN() }
    if (values.size < 2) return
    val all = values + open.map { it.stop }
    val min = all.min()
    val max = all.max()
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (size.height - 6.dp.toPx()) - ((v - min) / range).toFloat() * (size.height - 12.dp.toPx())
        val step = size.width / (values.size - 1)
        open.forEach { o ->
            drawLine(
                Palette.direction(o.direction).copy(alpha = 0.8f), Offset(0f, y(o.stop)), Offset(size.width, y(o.stop)),
                strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
        }
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * step, y(v)) }
        drawPath(path, Palette.Ink, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Palette.Saffron, radius = 3.5.dp.toPx(), center = Offset(size.width, y(values.last())))
    }
}

/** A horizontal bar for a total R, centred on zero, scaled to [maxAbs]. */
@Composable
fun RBar(value: Double, maxAbs: Double, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val mid = size.width / 2
        drawLine(Palette.Line, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 1.dp.toPx())
        if (maxAbs > 0 && !value.isNaN()) {
            val w = (abs(value) / maxAbs).toFloat().coerceAtMost(1f) * mid
            val c = if (value >= 0) Palette.Long else Palette.Short
            val left = if (value >= 0) mid else mid - w
            drawRect(c, topLeft = Offset(left, 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(w, size.height - 2.dp.toPx()))
        }
    }
}
