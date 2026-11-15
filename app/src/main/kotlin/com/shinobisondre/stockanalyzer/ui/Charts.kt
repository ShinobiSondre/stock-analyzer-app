package com.shinobisondre.stockanalyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinobisondre.stockanalyzer.engine.Level
import com.shinobisondre.stockanalyzer.engine.LevelType
import com.shinobisondre.stockanalyzer.engine.Series
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import kotlin.math.max
import kotlin.math.min

enum class Overlay(val label: String) { SMA20("SMA 20"), SMA50("SMA 50"), SMA200("SMA 200"), BB("Bollinger"), LEVELS("Levels") }

private const val RIGHT_GUTTER = 56f

/** Visible window [from, to] of a series for [bars] bars. */
fun windowOf(s: Series, bars: Int): IntRange = max(0, s.n - bars)..(s.n - 1)

private fun Modifier.scrub(count: Int, gutterPx: Float, onSel: (Int?) -> Unit): Modifier = this
    .pointerInput(count) {
        detectTapGestures(onTap = { o ->
            val w = size.width - gutterPx
            onSel(((o.x / w) * count).toInt().coerceIn(0, count - 1))
        }, onLongPress = { onSel(null) })
    }
    .pointerInput(count) {
        detectHorizontalDragGestures(
            onDragStart = { o -> onSel(((o.x / (size.width - gutterPx)) * count).toInt().coerceIn(0, count - 1)) },
            onHorizontalDrag = { change, _ -> onSel(((change.position.x / (size.width - gutterPx)) * count).toInt().coerceIn(0, count - 1)) },
        )
    }

private fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float, color: Color = Palette.muted, size: Int = 10) {
    drawText(tm, text, Offset(x, y), style = TextStyle(color = color, fontSize = size.sp))
}

private fun DrawScope.seriesLine(values: DoubleArray, range: IntRange, xOf: (Int) -> Float, yOf: (Double) -> Float, color: Color, width: Float = 2f, dashed: Boolean = false) {
    val path = Path()
    var started = false
    for (i in range) {
        val v = values[i]
        if (v.isNaN()) { started = false; continue }
        val x = xOf(i - range.first); val y = yOf(v)
        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
}

@Composable
fun PriceChart(
    s: Series,
    bars: Int,
    overlays: Set<Overlay>,
    levels: List<Level>,
    sel: Int?,
    onSel: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 260.dp,
) {
    val tm = rememberTextMeasurer()
    val r = windowOf(s, bars)
    val count = r.last - r.first + 1
    Canvas(modifier.fillMaxWidth().height(height).scrub(count, RIGHT_GUTTER, onSel)) {
        val w = size.width - RIGHT_GUTTER
        val priceH = size.height * 0.80f
        val volTop = size.height * 0.84f
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (i in r) { lo = min(lo, s.low[i]); hi = max(hi, s.high[i]) }
        if (Overlay.BB in overlays) for (i in r) { if (!s.bb.lower[i].isNaN()) lo = min(lo, s.bb.lower[i]); if (!s.bb.upper[i].isNaN()) hi = max(hi, s.bb.upper[i]) }
        val pad = (hi - lo) * 0.05
        lo -= pad; hi += pad
        if (hi <= lo) { hi = lo + 1 }
        val step = w / count
        fun xOf(k: Int) = k * step + step / 2
        fun yOf(v: Double) = (priceH * (1 - (v - lo) / (hi - lo))).toFloat()

        // Grid + axis labels
        for (g in 0..3) {
            val v = lo + (hi - lo) * g / 3
            val y = yOf(v)
            drawLine(Palette.outline.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), 1f)
            label(tm, F.num(v, if (v >= 1000) 0 else 2), w + 6f, y - 14f)
        }
        // Bollinger band fill + lines
        if (Overlay.BB in overlays) {
            seriesLine(s.bb.upper, r, ::xOf, ::yOf, Palette.band, 1.5f)
            seriesLine(s.bb.lower, r, ::xOf, ::yOf, Palette.band, 1.5f)
            seriesLine(s.bb.middle, r, ::xOf, ::yOf, Palette.band, 1f, dashed = true)
        }
        // Levels
        if (Overlay.LEVELS in overlays) for (lv in levels) {
            if (lv.price < lo || lv.price > hi) continue
            val y = yOf(lv.price)
            val c = if (lv.type == LevelType.SUPPORT) Palette.up else Palette.down
            drawLine(c.copy(alpha = 0.45f), Offset(0f, y), Offset(w, y), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        // Candles or line
        if (count <= 130) {
            val bw = max(1.5f, step * 0.62f)
            for (k in 0 until count) {
                val i = r.first + k
                val up = s.close[i] >= s.open[i]
                val c = if (up) Palette.up else Palette.down
                val x = xOf(k)
                drawLine(c, Offset(x, yOf(s.high[i])), Offset(x, yOf(s.low[i])), max(1f, step * 0.12f))
                val top = yOf(max(s.open[i], s.close[i])); val bot = yOf(min(s.open[i], s.close[i]))
                drawRect(c, Offset(x - bw / 2, top), Size(bw, max(1.5f, bot - top)))
            }
        } else {
            seriesLine(s.close, r, ::xOf, ::yOf, Palette.accent, 2.5f)
        }
        if (Overlay.SMA20 in overlays) seriesLine(s.sma20, r, ::xOf, ::yOf, Palette.sma20, 1.8f)
        if (Overlay.SMA50 in overlays) seriesLine(s.sma50, r, ::xOf, ::yOf, Palette.sma50, 1.8f)
        if (Overlay.SMA200 in overlays) seriesLine(s.sma200, r, ::xOf, ::yOf, Palette.sma200, 1.8f)

        // Volume
        var vmax = 0.0
        for (i in r) vmax = max(vmax, s.volume[i])
        if (vmax > 0) for (k in 0 until count) {
            val i = r.first + k
            val hgt = ((s.volume[i] / vmax) * (size.height - volTop)).toFloat()
            val c = if (s.close[i] >= s.open[i]) Palette.up else Palette.down
            drawRect(c.copy(alpha = 0.35f), Offset(xOf(k) - max(1f, step * 0.31f), size.height - hgt), Size(max(1.5f, step * 0.62f), hgt))
        }
        // Crosshair + readout
        val k = sel?.coerceIn(0, count - 1)
        if (k != null) {
            val i = r.first + k
            val x = xOf(k)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), 1f)
            val y = yOf(s.close[i])
            drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), 1f)
            val prev = if (i > 0) s.close[i - 1] else s.close[i]
            val chg = (s.close[i] - prev) / prev * 100
            val txt = "${s.dates[i]}  O ${F.num(s.open[i])}  H ${F.num(s.high[i])}  L ${F.num(s.low[i])}  C ${F.num(s.close[i])}  ${F.pct(chg)}"
            val layout = tm.measure(txt, TextStyle(fontSize = 11.sp))
            drawRoundRect(Palette.surface2.copy(alpha = 0.92f), Offset(4f, 4f), Size(layout.size.width + 16f, layout.size.height + 10f), CornerRadius(10f, 10f))
            drawText(layout, Palette.text, Offset(12f, 9f))
        }
    }
}

@Composable
fun RsiPane(s: Series, bars: Int, sel: Int?, onSel: (Int?) -> Unit, modifier: Modifier = Modifier, height: Dp = 84.dp) {
    val tm = rememberTextMeasurer()
    val r = windowOf(s, bars)
    val count = r.last - r.first + 1
    Canvas(modifier.fillMaxWidth().height(height).scrub(count, RIGHT_GUTTER, onSel)) {
        val w = size.width - RIGHT_GUTTER
        val step = w / count
        fun xOf(k: Int) = k * step + step / 2
        fun yOf(v: Double) = (size.height * (1 - v / 100)).toFloat()
        drawRect(Palette.up.copy(alpha = 0.06f), Offset(0f, yOf(30.0)), Size(w, yOf(0.0) - yOf(30.0)))
        drawRect(Palette.down.copy(alpha = 0.06f), Offset(0f, yOf(100.0)), Size(w, yOf(70.0) - yOf(100.0)))
        for (lvl in listOf(30.0, 50.0, 70.0)) {
            drawLine(Palette.outline, Offset(0f, yOf(lvl)), Offset(w, yOf(lvl)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            label(tm, lvl.toInt().toString(), w + 6f, yOf(lvl) - 14f)
        }
        seriesLine(s.rsi, r, ::xOf, ::yOf, Palette.sma50, 2f)
        val k = sel?.coerceIn(0, count - 1)
        if (k != null) {
            drawLine(Color.White.copy(alpha = 0.5f), Offset(xOf(k), 0f), Offset(xOf(k), size.height), 1f)
            val v = s.rsi[r.first + k]
            if (!v.isNaN()) label(tm, "RSI ${F.num(v, 1)}", 6f, 2f, Palette.text, 11)
        } else label(tm, "RSI 14", 6f, 2f)
    }
}

@Composable
fun MacdPane(s: Series, bars: Int, sel: Int?, onSel: (Int?) -> Unit, modifier: Modifier = Modifier, height: Dp = 84.dp) {
    val tm = rememberTextMeasurer()
    val r = windowOf(s, bars)
    val count = r.last - r.first + 1
    Canvas(modifier.fillMaxWidth().height(height).scrub(count, RIGHT_GUTTER, onSel)) {
        val w = size.width - RIGHT_GUTTER
        val step = w / count
        var m = 1e-9
        for (i in r) for (v in doubleArrayOf(s.macd.line[i], s.macd.signal[i], s.macd.hist[i])) if (!v.isNaN()) m = max(m, kotlin.math.abs(v))
        fun xOf(k: Int) = k * step + step / 2
        fun yOf(v: Double) = (size.height / 2 * (1 - v / m)).toFloat()
        drawLine(Palette.outline, Offset(0f, yOf(0.0)), Offset(w, yOf(0.0)), 1f)
        for (k in 0 until count) {
            val v = s.macd.hist[r.first + k]
            if (v.isNaN()) continue
            val y0 = yOf(0.0); val y1 = yOf(v)
            drawRect((if (v >= 0) Palette.up else Palette.down).copy(alpha = 0.5f), Offset(xOf(k) - max(1f, step * 0.3f), min(y0, y1)), Size(max(1.5f, step * 0.6f), kotlin.math.abs(y1 - y0)))
        }
        seriesLine(s.macd.line, r, ::xOf, ::yOf, Palette.sma50, 1.8f)
        seriesLine(s.macd.signal, r, ::xOf, ::yOf, Palette.sma20, 1.5f)
        val k = sel?.coerceIn(0, count - 1)
        if (k != null) {
            drawLine(Color.White.copy(alpha = 0.5f), Offset(xOf(k), 0f), Offset(xOf(k), size.height), 1f)
            val i = r.first + k
            label(tm, "MACD ${F.num(s.macd.line[i], 2)}  signal ${F.num(s.macd.signal[i], 2)}", 6f, 2f, Palette.text, 11)
        } else label(tm, "MACD 12/26/9", 6f, 2f)
    }
}

@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val up = values.last() >= values.first()
    Canvas(modifier) {
        val lo = values.min(); val hi = values.max().let { if (it <= lo) lo + 1 else it }
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * size.width / (values.size - 1)
            val y = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (up) Palette.up else Palette.down, style = Stroke(width = 3f))
    }
}

/** Reliability diagram: predicted (x) vs realised (y) up-rate per decile. */
@Composable
fun CalibrationChart(rows: List<DoubleArray>, modifier: Modifier = Modifier, height: Dp = 160.dp) {
    if (rows.isEmpty()) return
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height)) {
        val all = rows.flatMap { listOf(it[0], it[1]) }
        val lo = (all.min() - 0.02).coerceAtLeast(0.0); val hi = (all.max() + 0.02).coerceAtMost(1.0)
        val left = 40f; val bottom = size.height - 18f
        fun x(v: Double) = (left + (size.width - left - 8f) * (v - lo) / (hi - lo)).toFloat()
        fun y(v: Double) = (bottom * (1 - (v - lo) / (hi - lo))).toFloat()
        drawLine(Palette.outline, Offset(x(lo), y(lo)), Offset(x(hi), y(hi)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        for (g in listOf(lo, (lo + hi) / 2, hi)) {
            label(tm, "${(g * 100).toInt()}%", 0f, y(g) - 8f)
            label(tm, "${(g * 100).toInt()}%", x(g) - 12f, bottom + 2f)
        }
        for (r in rows) drawCircle(Palette.accent, 7f, Offset(x(r[0]), y(r[1])))
    }
}
