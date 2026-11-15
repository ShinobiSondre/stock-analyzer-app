package com.shinobisondre.stockanalyzer.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinobisondre.stockanalyzer.engine.Call
import com.shinobisondre.stockanalyzer.engine.Money
import com.shinobisondre.stockanalyzer.engine.Tone
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import com.shinobisondre.stockanalyzer.ui.theme.changeColor
import com.shinobisondre.stockanalyzer.ui.theme.toneColor
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Formatting ───────────────────────────────────────────────────────────────
object F {
    private fun nf(d: Int) = NumberFormat.getNumberInstance(Locale.US).apply { minimumFractionDigits = d; maximumFractionDigits = d }

    fun price(v: Double?, currency: String? = null): String {
        if (v == null || v.isNaN()) return "—"
        val digits = if (abs(v) >= 1000) 0 else if (abs(v) < 1) 4 else 2
        val sym = when (currency) { "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; "GBp" -> ""; "JPY" -> "¥"; null -> ""; else -> "" }
        val suffix = when (currency) { null, "USD", "EUR", "GBP", "JPY" -> ""; "GBp" -> "p"; else -> " $currency" }
        return sym + nf(digits).format(v) + suffix
    }

    fun money(v: Double?, currency: String): String {
        if (v == null || v.isNaN()) return "—"
        val (c, _) = Money.normalize(currency)
        val a = abs(v)
        val s = when {
            a >= 10_000_000 -> nf(1).format(v / 1_000_000) + "M"
            a >= 100_000 -> nf(0).format(v)
            else -> nf(if (a >= 1000) 0 else 2).format(v)
        }
        return "$s $c"
    }

    fun pct(v: Double?, digits: Int = 2, signed: Boolean = true): String {
        if (v == null || v.isNaN()) return "—"
        return (if (signed && v > 0) "+" else "") + nf(digits).format(v) + "%"
    }

    fun prob(p: Double?): String = if (p == null || p.isNaN()) "—" else "${(p * 100).roundToInt()}%"

    fun edge(p: Double?, base: Double?): String {
        if (p == null || base == null) return ""
        val e = ((p - base) * 100)
        return (if (e >= 0) "+" else "") + nf(1).format(e) + " pts"
    }

    fun big(v: Double?): String {
        if (v == null || v.isNaN()) return "—"
        val a = abs(v)
        return when {
            a >= 1e12 -> nf(2).format(v / 1e12) + "T"
            a >= 1e9 -> nf(1).format(v / 1e9) + "B"
            a >= 1e6 -> nf(1).format(v / 1e6) + "M"
            a >= 1e3 -> nf(1).format(v / 1e3) + "K"
            else -> nf(0).format(v)
        }
    }

    fun num(v: Double?, d: Int = 2): String = if (v == null || v.isNaN()) "—" else nf(d).format(v)

    fun ago(ms: Long): String {
        val s = (System.currentTimeMillis() - ms) / 1000
        return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} d ago" }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────────
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    help: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Every card with jargon gets a "?" that explains it in plain words.
    val explain = help ?: Glossary.cards[title]
    var showHelp by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                if (explain != null) Box(
                    Modifier.padding(start = 6.dp).size(26.dp).clip(RoundedCornerShape(50)).background(Palette.surface2).clickable { showHelp = true },
                    contentAlignment = Alignment.Center,
                ) { Text("?", color = Palette.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                if (action != null) action()
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
    if (showHelp && explain != null) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showHelp = false },
        title = { Text(title) },
        text = { Text(explain, fontSize = 15.sp) },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Got it") } },
    )
}

@Composable
fun CallBadge(call: Call, modifier: Modifier = Modifier, big: Boolean = false) {
    val c = toneColor(call.tone)
    Box(
        modifier
            .clip(RoundedCornerShape(if (big) 12.dp else 8.dp))
            .background(c.copy(alpha = 0.16f))
            .border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(if (big) 12.dp else 8.dp))
            .padding(horizontal = if (big) 14.dp else 8.dp, vertical = if (big) 8.dp else 3.dp),
    ) {
        Text(call.text, color = c, fontWeight = FontWeight.Bold, fontSize = if (big) 20.sp else 11.sp, maxLines = 1)
    }
}

@Composable
fun ToneChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = toneColor(tone)
    Box(modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = c, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Pill(text: String, color: Color = Palette.muted, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(Palette.surface2).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = color, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun ChangeText(pct: Double?, modifier: Modifier = Modifier, size: Int = 14) {
    val arrow = when { pct == null -> ""; pct > 0 -> "▲ "; pct < 0 -> "▼ "; else -> "" }
    Text(arrow + F.pct(pct), color = changeColor(pct), fontSize = size.sp, fontWeight = FontWeight.SemiBold, modifier = modifier, maxLines = 1)
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Palette.text, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, color = Palette.muted, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, maxLines = 2)
    }
}

/** Horizontal bar: probability [p] with a tick at the base rate. */
@Composable
fun ProbBar(p: Double, base: Double?, tone: Tone, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    var target by remember { mutableStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(p) { target = p.toFloat().coerceIn(0f, 1f) }
    val fill by animateFloatAsState(target, tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "prob")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Palette.surface2)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fill.coerceIn(0.001f, 1f)).clip(RoundedCornerShape(50)).background(toneColor(tone)))
        if (base != null) {
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.fillMaxHeight().weight(base.toFloat().coerceIn(0.01f, 0.99f)))
                Box(Modifier.width(2.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.85f)))
                Spacer(Modifier.fillMaxHeight().weight((1 - base).toFloat().coerceIn(0.01f, 0.99f)))
            }
        }
    }
}

/** Diverging bar for a driver's contribution (left = hurting, right = helping). */
@Composable
fun DriverBar(value: Double, maxAbs: Double, modifier: Modifier = Modifier) {
    val frac = (abs(value) / maxAbs.coerceAtLeast(1e-9)).toFloat().coerceIn(0.02f, 1f)
    Row(modifier.fillMaxWidth().height(10.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            if (value < 0) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(topStart = 50.dp, bottomStart = 50.dp)).background(Palette.down))
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.outline))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            if (value > 0) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(topEnd = 50.dp, bottomEnd = 50.dp)).background(Palette.up))
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier, text: String? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Palette.accent)
        if (text != null) { Spacer(Modifier.height(8.dp)); Text(text, color = Palette.muted, fontSize = 13.sp) }
    }
}

@Composable
fun ErrorBox(msg: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(msg, color = Palette.strongSell, textAlign = TextAlign.Center, fontSize = 14.sp)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, color = Palette.muted, textAlign = TextAlign.Center, fontSize = 14.sp)
        if (actionLabel != null && onAction != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}

fun regimeTone(name: String?): Tone = when (name) { "BULL" -> Tone.BUY; "BEAR" -> Tone.STRONG_SELL; "HIGH_VOL" -> Tone.SELL; "CAUTION" -> Tone.NEUTRAL; else -> Tone.NEUTRAL }
