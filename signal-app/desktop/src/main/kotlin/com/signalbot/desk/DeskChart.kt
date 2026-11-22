package com.signalbot.desk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.signalbot.engine.model.Candle
import com.signalbot.engine.strategy.formatPrice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Candlestick chart for one asset and timeframe, with the forming candle live. Scroll the mouse wheel to
 * zoom, drag to move back in time, hover to read a candle. [compact] = small grid tile (fewer labels).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DeskChart(view: DeskView, forming: Candle?, modifier: Modifier = Modifier, compact: Boolean = false, zone: ZoneId = ZoneId.systemDefault()) {
    val key = view.assetId to view.tf
    val hasForming = forming != null && (view.time.isEmpty() || forming.openTime > view.time.last())
    val n = view.size + if (hasForming) 1 else 0
    var visible by remember(key) { mutableFloatStateOf(if (compact) 70f else 130f) }
    var right by remember(key) { mutableFloatStateOf(n - 1 + if (compact) 4f else 8f) }
    var hover by remember(key) { mutableStateOf<Offset?>(null) }
    val tm = rememberTextMeasurer()
    val axis = T.Tiny.copy(color = Desk.Faint)

    Canvas(
        modifier
            .clipToBounds()
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                visible = (visible * (1f + 0.12f * dy)).coerceIn(20f, max(20f, n + 20f))
            }
            .onPointerEvent(PointerEventType.Move) { e -> hover = e.changes.firstOrNull()?.position }
            .onPointerEvent(PointerEventType.Exit) { hover = null }
            .pointerInput(key) {
                detectDragGestures { change, drag ->
                    val axisW = 74.dp.toPx()
                    val cw = (size.width - axisW) / visible
                    right = (right - drag.x / cw).coerceIn(visible * 0.3f, n - 1 + 30f)
                    hover = change.position
                }
            },
    ) {
        draw(view, if (hasForming) forming else null, visible, right, hover, tm, axis, compact, zone)
    }
}

private fun DrawScope.draw(
    v: DeskView, forming: Candle?, visible: Float, right: Float, hover: Offset?, tm: TextMeasurer, axis: TextStyle,
    compact: Boolean, zone: ZoneId,
) {
    val n = v.size + if (forming != null) 1 else 0
    if (n == 0) return
    fun o(i: Int) = if (i < v.size) v.open[i] else forming!!.open
    fun h(i: Int) = if (i < v.size) v.high[i] else forming!!.high
    fun l(i: Int) = if (i < v.size) v.low[i] else forming!!.low
    fun c(i: Int) = if (i < v.size) v.close[i] else forming!!.close
    fun time(i: Int) = if (i < v.size) v.time[i] else forming!!.openTime

    val axisW = if (compact) 0f else 74.dp.toPx()
    val bottomH = if (compact) 0f else 22.dp.toPx()
    val topH = if (compact) 0f else 24.dp.toPx()
    val plotW = size.width - axisW
    val plotH = size.height - bottomH
    val cw = plotW / visible
    fun x(i: Float) = plotW - (right - i) * cw
    val lo = max(0, floor(right - visible).toInt())
    val hi = min(n - 1, ceil(right).toInt())

    var minP = Double.POSITIVE_INFINITY
    var maxP = Double.NEGATIVE_INFINITY
    for (k in lo..hi) { minP = min(minP, l(k)); maxP = max(maxP, h(k)) }
    if (minP > maxP) { minP = (0 until n).minOf { l(it) }; maxP = (0 until n).maxOf { h(it) } }
    val pad = (maxP - minP).coerceAtLeast(maxP * 1e-4) * 0.1
    minP -= pad; maxP += pad
    fun y(p: Double) = (topH + (maxP - p) / (maxP - minP) * (plotH - topH)).toFloat()

    // Grid and price axis.
    val step = niceStep((maxP - minP) / if (compact) 3 else 6)
    var g = ceil(minP / step) * step
    while (g < maxP) {
        val yy = y(g)
        drawLine(Desk.Hairline.copy(alpha = 0.55f), Offset(0f, yy), Offset(plotW, yy), 1f)
        if (!compact) label(tm, axisPrice(g, step), Offset(plotW + 8.dp.toPx(), yy), axis)
        g += step
    }
    if (!compact) {
        val every = max(1, (hi - lo) / 6)
        var k = lo + every / 2
        while (k <= hi) {
            val r = tm.measure(stamp(time(k), v.tf, zone), axis)
            val xx = x(k.toFloat()) - r.size.width / 2f
            if (xx > 0 && xx + r.size.width < plotW) drawText(r, topLeft = Offset(xx, plotH + 5.dp.toPx()))
            k += every
        }
    }

    clipRect(0f, 0f, plotW, plotH) {
        for (lv in v.levels) drawLine(Desk.Level.copy(alpha = 0.32f), Offset(0f, y(lv)), Offset(plotW, y(lv)), 1.5.dp.toPx())
        // Candles: bodies slightly translucent so lines behind them stay readable.
        val bodyW = max(1f, cw * 0.66f)
        val wick = max(1f, min(1.5.dp.toPx(), cw * 0.14f))
        for (i in lo..hi) {
            val up = c(i) >= o(i)
            val col = if (up) Desk.Long else Desk.Short
            val alpha = if (i >= v.size) 0.55f else 0.92f
            val xc = x(i.toFloat())
            drawLine(col.copy(alpha = alpha), Offset(xc, y(h(i))), Offset(xc, y(l(i))), wick)
            val top = y(max(o(i), c(i))); val bot = y(min(o(i), c(i)))
            drawRect(col.copy(alpha = alpha), Offset(xc - bodyW / 2, top), Size(bodyW, max(1f, bot - top)))
        }
        // Lines, higher timeframes first (underneath).
        for (ln in v.lines.sortedBy { it.style.ordinal * -1 }) {
            val (col, w, dash) = when (ln.style) {
                LStyle.HTF_DOWN -> Triple(Desk.Short.copy(alpha = 0.5f), 3.dp.toPx(), null)
                LStyle.HTF_UP -> Triple(Desk.Long.copy(alpha = 0.5f), 3.dp.toPx(), null)
                LStyle.DOWN -> Triple(Desk.Short, 1.6.dp.toPx(), null)
                LStyle.UP -> Triple(Desk.Long, 1.6.dp.toPx(), null)
                LStyle.BROKEN -> Triple(Desk.Saffron, 2.dp.toPx(), PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                LStyle.OLD -> Triple(Desk.Faint.copy(alpha = 0.7f), 1.dp.toPx(), null)
                LStyle.STOP -> Triple(Desk.Paper.copy(alpha = 0.55f), 1.4.dp.toPx(), PathEffect.dashPathEffect(floatArrayOf(4f, 5f)))
            }
            val a = ln.x1.toFloat(); val b = ln.endX.toFloat()
            drawLine(col, Offset(x(a), y(ln.at(a.toDouble()))), Offset(x(b), y(ln.at(b.toDouble()))), w, pathEffect = dash)
            if (ln.style == LStyle.DOWN || ln.style == LStyle.UP) {
                drawCircle(col, 3.dp.toPx(), Offset(x(a), y(ln.p1)))
                drawCircle(col, 3.dp.toPx(), Offset(x(ln.x2.toFloat()), y(ln.p2)))
            }
            if (ln.tag != null && !compact) {
                val tx = min(plotW - 30.dp.toPx(), x(min(b, right - 2f)))
                val r = tm.measure(ln.tag, T.Tiny.copy(color = col.copy(alpha = 1f)))
                drawText(r, topLeft = Offset(tx, y(ln.at(((tx - plotW) / cw + right).toDouble())) - r.size.height - 2.dp.toPx()))
            }
        }
        // Stops (step paths) and targets.
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        for (p in v.stopPaths) {
            for (k in 0 until p.size - 1) {
                val (i0, s0) = p[k]; val (i1, _) = p[k + 1]
                drawLine(Desk.Short.copy(alpha = 0.85f), Offset(x(i0 - 0.5f), y(s0)), Offset(x(i1 - 0.5f), y(s0)), 1.3.dp.toPx(), pathEffect = dash)
            }
        }
        for ((a, b, p) in v.targets) drawLine(Desk.Long.copy(alpha = 0.85f), Offset(x(a - 0.5f), y(p)), Offset(x(b + 0.5f), y(p)), 1.3.dp.toPx(), pathEffect = dash)
        val sz = (if (compact) 6 else 8).dp.toPx()
        for (m in v.marks) {
            val xc = x(m.x.toFloat())
            val i = m.x.coerceIn(0, n - 1)
            when (m.kind) {
                MarkKind.BUY -> tri(Desk.Saffron, xc, y(l(i)) + sz + 4f, sz, true)
                MarkKind.SELL -> tri(Desk.Saffron, xc, y(h(i)) - sz - 4f, sz, false)
                MarkKind.WIN -> drawCircle(Desk.Long, 4.dp.toPx(), Offset(xc, y(m.price)), style = Stroke(1.6.dp.toPx()))
                MarkKind.LOSS -> drawCircle(Desk.Short, 4.dp.toPx(), Offset(xc, y(m.price)), style = Stroke(1.6.dp.toPx()))
                MarkKind.BREAK -> drawCircle(Desk.Saffron, 2.6.dp.toPx(), Offset(xc, y(m.price)))
            }
        }
    }

    // Axis tags: live trade stop and target, then the live price.
    fun tag(price: Double, col: Color, text: String = formatPrice(price)) {
        if (compact) return
        val ty = y(price)
        if (ty !in topH..plotH) return
        val r = tm.measure(text, T.Tiny.copy(color = Desk.Base))
        drawRect(col, Offset(plotW + 2.dp.toPx(), ty - r.size.height / 2f - 3.dp.toPx()), Size(axisW - 2.dp.toPx(), r.size.height + 6.dp.toPx()))
        drawText(r, topLeft = Offset(plotW + 8.dp.toPx(), ty - r.size.height / 2f))
    }
    v.trade?.let { t -> tag(t.stop, Desk.Short.copy(alpha = 0.85f)); t.target?.let { tag(it, Desk.Long.copy(alpha = 0.85f)) } }
    val last = c(n - 1)
    val col = if (last >= o(n - 1)) Desk.Long else Desk.Short
    drawLine(col.copy(alpha = 0.45f), Offset(0f, y(last)), Offset(plotW, y(last)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
    tag(last, col)

    // Hover: crosshair and the candle's prices.
    val hv = hover
    if (hv != null && hv.x < plotW && !compact) {
        val i = Math.round(right - (plotW - hv.x) / cw).coerceIn(0, n - 1)
        val xc = x(i.toFloat())
        drawLine(Desk.Paper.copy(alpha = 0.25f), Offset(xc, topH), Offset(xc, plotH), 1f)
        drawLine(Desk.Paper.copy(alpha = 0.25f), Offset(0f, hv.y), Offset(plotW, hv.y), 1f)
        val p = maxP - (hv.y - topH) / (plotH - topH) * (maxP - minP)
        tag(p, Desk.Raised, formatPrice(p))
        val up = c(i) >= o(i)
        val text = "${stamp(time(i), v.tf, zone, long = true)}   O ${formatPrice(o(i))}  H ${formatPrice(h(i))}  L ${formatPrice(l(i))}  C ${formatPrice(c(i))}"
        drawText(tm.measure(text, T.Small.copy(color = if (up) Desk.Long else Desk.Short)), topLeft = Offset(6.dp.toPx(), 2.dp.toPx()))
    } else if (!compact) {
        drawText(tm.measure("Scroll to zoom, drag to look back, hover to read a candle", T.Small.copy(color = Desk.Faint)), topLeft = Offset(6.dp.toPx(), 2.dp.toPx()))
    }
}

private fun DrawScope.tri(c: Color, xc: Float, yc: Float, s: Float, up: Boolean) {
    val p = Path().apply {
        if (up) { moveTo(xc, yc - s * 0.6f); lineTo(xc - s * 0.6f, yc + s * 0.5f); lineTo(xc + s * 0.6f, yc + s * 0.5f) }
        else { moveTo(xc, yc + s * 0.6f); lineTo(xc - s * 0.6f, yc - s * 0.5f); lineTo(xc + s * 0.6f, yc - s * 0.5f) }
        close()
    }
    drawPath(p, c)
}

private fun DrawScope.label(tm: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    val r = tm.measure(text, style)
    drawText(r, topLeft = Offset(at.x, (at.y - r.size.height / 2f).coerceIn(0f, size.height - r.size.height)))
}

private fun niceStep(raw: Double): Double {
    if (raw <= 0 || raw.isNaN()) return 1.0
    val mag = 10.0.pow(floor(log10(raw)))
    val f = raw / mag
    return (when { f < 1.5 -> 1.0; f < 2.25 -> 2.0; f < 3.5 -> 2.5; f < 7.5 -> 5.0; else -> 10.0 }) * mag
}

private fun axisPrice(p: Double, step: Double): String {
    val d = when { step >= 1 -> 0; step >= 0.1 -> 1; step >= 0.01 -> 2; step >= 0.001 -> 3; else -> 4 }
    return String.format(Locale.US, "%,.${d}f", p)
}

fun stamp(ms: Long, tf: Tf, zone: ZoneId, long: Boolean = false): String {
    val pattern = when {
        long -> if (tf == Tf.D1) "EEE d MMM yyyy" else "EEE d MMM, HH:mm"
        tf == Tf.D1 || tf == Tf.H4 -> "d MMM"
        else -> "HH:mm"
    }
    return DateTimeFormatter.ofPattern(pattern, Locale.UK).withZone(zone).format(Instant.ofEpochMilli(ms))
}
