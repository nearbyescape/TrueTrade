package com.signalbot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.signalbot.app.data.ChartModel
import com.signalbot.app.data.LineStyle
import com.signalbot.app.data.MarkerKind
import com.signalbot.app.ui.theme.Palette
import com.signalbot.app.ui.theme.Type
import com.signalbot.engine.strategy.formatPrice
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private val levelColor = Color(0xFFFFD166)

/**
 * Candlestick chart with the setup drawn on it. Pinch to zoom, drag to scroll, tap a candle to read it.
 * Opens on the newest candles with a little room on the right for the next open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CandleChart(model: ChartModel, modifier: Modifier = Modifier, height: Dp = 380.dp) {
    val n = model.size
    val key = model.timeframe to model.time.lastOrNull()
    var visible by remember(key) { mutableFloatStateOf(min(n.toFloat(), 64f).coerceAtLeast(12f)) }
    var right by remember(key) { mutableFloatStateOf(n - 1 + 3.5f) }
    var selected by remember(key) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axisStyle = Type.Tiny.copy(color = Palette.Muted)

    Column(modifier) {
        val i = (selected ?: (n - 1)).coerceIn(0, n - 1)
        val up = model.close[i] >= model.open[i]
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stamp(model.time[i], model.timeframe), style = Type.Small, color = Palette.Muted)
            Spacer(Modifier.width(10.dp))
            Text(
                "O ${formatPrice(model.open[i])}  H ${formatPrice(model.high[i])}  L ${formatPrice(model.low[i])}  C ${formatPrice(model.close[i])}",
                style = Type.Small, color = if (up) Palette.Long else Palette.Short,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                .background(Palette.Night)
                .pointerInput(key) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val plotW = size.width - 58.dp.toPx()
                        visible = (visible / zoom).coerceIn(12f, max(12f, n + 8f))
                        val cw = plotW / visible
                        right = (right - pan.x / cw).coerceIn(visible * 0.5f, n - 1 + 8f)
                    }
                }
                .pointerInput(key) {
                    detectTapGestures { pos ->
                        val plotW = size.width - 58.dp.toPx()
                        if (pos.x > plotW) { selected = null; return@detectTapGestures }
                        val cw = plotW / visible
                        val idx = Math.round(right - (plotW - pos.x) / cw)
                        selected = if (idx !in 0 until n || idx == selected) null else idx
                    }
                },
        ) {
            drawChart(model, visible, right, selected, measurer, axisStyle)
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(Palette.Short, "Downtrend line")
            Legend(Palette.Long, "Uptrend line")
            Legend(Palette.Saffron, "Broken, waiting for retest")
            Legend(levelColor, "Support/resistance")
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.Tiny, color = Palette.Muted)
    }
}

private fun DrawScope.drawChart(m: ChartModel, visible: Float, right: Float, selected: Int?, tm: TextMeasurer, axis: TextStyle) {
    val n = m.size
    if (n == 0) return
    val axisW = 58.dp.toPx()
    val bottomH = 20.dp.toPx()
    val plotW = size.width - axisW
    val plotH = size.height - bottomH
    val cw = plotW / visible
    fun x(i: Float) = plotW - (right - i) * cw
    val lo = max(0, floor(right - visible).toInt())
    val hi = min(n - 1, ceil(right).toInt())

    // Price range of the visible candles, padded.
    var minP = Double.POSITIVE_INFINITY
    var maxP = Double.NEGATIVE_INFINITY
    for (k in lo..hi) { minP = min(minP, m.low[k]); maxP = max(maxP, m.high[k]) }
    if (minP > maxP) { minP = m.low.min(); maxP = m.high.max() }
    val pad = (maxP - minP).coerceAtLeast(maxP * 1e-4) * 0.08
    minP -= pad; maxP += pad
    fun y(p: Double) = ((maxP - p) / (maxP - minP) * plotH).toFloat()

    // Grid and price labels.
    val step = niceStep((maxP - minP) / 5)
    var g = ceil(minP / step) * step
    while (g < maxP) {
        val yy = y(g)
        drawLine(Palette.Line.copy(alpha = 0.45f), Offset(0f, yy), Offset(plotW, yy), 1f)
        label(tm, formatPrice(g), Offset(plotW + 6.dp.toPx(), yy), axis)
        g += step
    }
    // Time labels.
    val every = max(1, ((hi - lo) / 4))
    var k = lo + every / 2
    while (k <= hi) {
        val r = tm.measure(stamp(m.time[k], m.timeframe, short = true), axis)
        val xx = x(k.toFloat()) - r.size.width / 2f
        if (xx > 0 && xx + r.size.width < plotW) drawText(r, topLeft = Offset(xx, plotH + 4.dp.toPx()))
        k += every
    }

    clipRect(0f, 0f, plotW, plotH) {
        // Support / resistance.
        for (lv in m.levels) {
            val yy = y(lv)
            drawLine(levelColor.copy(alpha = 0.55f), Offset(0f, yy), Offset(plotW, yy), 2.dp.toPx())
        }
        // Candles.
        val bodyW = max(1f, cw * 0.68f)
        val wick = max(1f, min(2.dp.toPx(), cw * 0.12f))
        for (i in lo..hi) {
            val up = m.close[i] >= m.open[i]
            val c = if (up) Palette.Long else Palette.Short
            val xc = x(i.toFloat())
            drawLine(c, Offset(xc, y(m.high[i])), Offset(xc, y(m.low[i])), wick)
            val top = y(max(m.open[i], m.close[i]))
            val bot = y(min(m.open[i], m.close[i]))
            drawRect(c, Offset(xc - bodyW / 2, top), Size(bodyW, max(1f, bot - top)))
        }
        // Trendlines.
        for (l in m.lines) {
            val (color, width, dash) = when (l.style) {
                LineStyle.DOWNTREND -> Triple(Palette.Short, 2.dp.toPx(), null)
                LineStyle.UPTREND -> Triple(Palette.Long, 2.dp.toPx(), null)
                LineStyle.BROKEN -> Triple(Palette.Saffron, 2.dp.toPx(), PathEffect.dashPathEffect(floatArrayOf(14f, 9f)))
                LineStyle.OLD -> Triple(Palette.Muted.copy(alpha = 0.55f), 1.5.dp.toPx(), null)
            }
            val a = l.i1.toFloat()
            val b = l.endIndex.toFloat()
            drawLine(color, Offset(x(a), y(l.at(a.toDouble()))), Offset(x(b), y(l.at(b.toDouble()))), width, pathEffect = dash)
            if (l.style != LineStyle.OLD) {
                drawCircle(color, 3.5.dp.toPx(), Offset(x(a), y(l.p1)))
                drawCircle(color, 3.5.dp.toPx(), Offset(x(l.i2.toFloat()), y(l.p2)))
            }
        }
        // Stops and targets.
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        for (s in m.stops) drawLine(Palette.Short, Offset(x(s.from - 0.5f), y(s.price)), Offset(x(s.to + 0.5f), y(s.price)), 1.5.dp.toPx(), pathEffect = dash)
        for (s in m.targets) drawLine(Palette.Long, Offset(x(s.from - 0.5f), y(s.price)), Offset(x(s.to + 0.5f), y(s.price)), 1.5.dp.toPx(), pathEffect = dash)
        // Markers.
        val sz = 7.dp.toPx()
        for (mk in m.markers) {
            val xc = x(mk.index.toFloat())
            val i = mk.index.coerceIn(0, n - 1)
            when (mk.kind) {
                MarkerKind.BUY -> triangle(Palette.Saffron, xc, y(m.low[i]) + sz * 0.6f + 8f, sz, up = true)
                MarkerKind.SELL -> triangle(Palette.Saffron, xc, y(m.high[i]) - sz * 0.6f - 8f, sz, up = false)
                MarkerKind.EXIT_WIN -> drawCircle(Palette.Long, 4.5.dp.toPx(), Offset(xc, y(mk.price)), style = Stroke(2.dp.toPx()))
                MarkerKind.EXIT_LOSS -> drawCircle(Palette.Short, 4.5.dp.toPx(), Offset(xc, y(mk.price)), style = Stroke(2.dp.toPx()))
                MarkerKind.BREAK -> drawCircle(Palette.Saffron, 3.dp.toPx(), Offset(xc, y(mk.price)))
            }
        }
        // Crosshair.
        selected?.let { s ->
            val xc = x(s.toFloat())
            drawLine(Palette.Ink.copy(alpha = 0.35f), Offset(xc, 0f), Offset(xc, plotH), 1f)
            drawLine(Palette.Ink.copy(alpha = 0.35f), Offset(0f, y(m.close[s])), Offset(plotW, y(m.close[s])), 1f)
        }
    }

    // Last price tag on the axis.
    val last = m.close[n - 1]
    val ly = y(last)
    if (ly in 0f..plotH) {
        val tag = if (last >= m.open[n - 1]) Palette.Long else Palette.Short
        drawLine(tag.copy(alpha = 0.6f), Offset(x((n - 1).toFloat()), ly), Offset(plotW, ly), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        val r = tm.measure(formatPrice(last), axis.copy(color = Palette.Night))
        drawRect(tag, Offset(plotW + 2.dp.toPx(), ly - r.size.height / 2f - 2.dp.toPx()), Size(axisW - 2.dp.toPx(), r.size.height + 4.dp.toPx()))
        drawText(r, topLeft = Offset(plotW + 6.dp.toPx(), ly - r.size.height / 2f))
    }
}

private fun DrawScope.triangle(c: Color, xc: Float, yc: Float, s: Float, up: Boolean) {
    val p = Path().apply {
        if (up) { moveTo(xc, yc - s * 0.6f); lineTo(xc - s * 0.6f, yc + s * 0.5f); lineTo(xc + s * 0.6f, yc + s * 0.5f) }
        else { moveTo(xc, yc + s * 0.6f); lineTo(xc - s * 0.6f, yc - s * 0.5f); lineTo(xc + s * 0.6f, yc - s * 0.5f) }
        close()
    }
    drawPath(p, c)
}

private fun DrawScope.label(tm: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    val r = tm.measure(text, style)
    val yy = (at.y - r.size.height / 2f).coerceIn(0f, size.height - r.size.height)
    drawText(r, topLeft = Offset(at.x, yy))
}

private fun niceStep(raw: Double): Double {
    if (raw <= 0 || raw.isNaN()) return 1.0
    val mag = 10.0.pow(floor(log10(raw)))
    val f = raw / mag
    val nice = when { f < 1.5 -> 1.0; f < 2.25 -> 2.0; f < 3.5 -> 2.5; f < 7.5 -> 5.0; else -> 10.0 }
    return nice * mag
}

private fun stamp(ms: Long, tf: String, short: Boolean = false): String {
    val pattern = when {
        tf == "1d" -> if (short) "d MMM" else "EEE d MMM yyyy"
        tf == "1h" && short -> "HH:mm"
        short -> "d MMM"
        else -> "EEE d MMM, HH:mm 'UTC'"
    }
    return DateTimeFormatter.ofPattern(pattern, Locale.UK).withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(ms))
}

/** Tab icon: three little candles. */
@Composable
fun CandleIcon(modifier: Modifier = Modifier) {
    androidx.compose.material3.Icon(
        painter = remember { CandlePainter() }, contentDescription = null, modifier = modifier.size(24.dp),
    )
}

private class CandlePainter : androidx.compose.ui.graphics.painter.Painter() {
    override val intrinsicSize = Size(24f, 24f)
    override fun DrawScope.onDraw() {
        val w = size.width / 24f
        val c = Color.White // tinted by Icon
        fun candle(x: Float, top: Float, bodyTop: Float, bodyBot: Float, bottom: Float) {
            drawLine(c, Offset(x * w, top * w), Offset(x * w, bottom * w), 1.6f * w)
            drawRect(c, Offset((x - 2.2f) * w, bodyTop * w), Size(4.4f * w, (bodyBot - bodyTop) * w))
        }
        candle(5f, 7f, 10f, 17f, 20f)
        candle(12f, 3f, 6f, 13f, 16f)
        candle(19f, 9f, 11f, 16f, 21f)
    }
}
