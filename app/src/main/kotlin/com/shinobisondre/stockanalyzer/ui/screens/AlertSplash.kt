package com.shinobisondre.stockanalyzer.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinobisondre.stockanalyzer.data.ChartResult
import com.shinobisondre.stockanalyzer.engine.PlainCall
import com.shinobisondre.stockanalyzer.engine.Tone
import com.shinobisondre.stockanalyzer.push.AlertSplash
import com.shinobisondre.stockanalyzer.ui.ChangeText
import com.shinobisondre.stockanalyzer.ui.F
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.Tab
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import com.shinobisondre.stockanalyzer.ui.theme.toneColor

private enum class AlertKind(val icon: String, val label: String) {
    UP("📈", "Big move up"), DOWN("📉", "Big move down"), LOW("🎯", "Predicted low reached"), HIGH("🏁", "Predicted high reached"),
    GOOD_NEWS("📰", "Good news"), BAD_NEWS("📰", "Bad news"), QUICK("⚡", "Quick win"), SELL("🔔", "Sell now?"), BUY("🛒", "Buy back?"), OTHER("🔔", "Alert"),
}

private fun kindOf(a: AlertSplash): AlertKind {
    val t = a.title.lowercase()
    return when {
        t.startsWith("⚡") || a.tab == "QUICK" -> AlertKind.QUICK
        "likely low" in t -> AlertKind.LOW
        "likely high" in t -> AlertKind.HIGH
        t.startsWith("news on") -> if ("good" in t) AlertKind.GOOD_NEWS else AlertKind.BAD_NEWS
        t.startsWith("sell ") -> AlertKind.SELL
        t.startsWith("buy ") -> AlertKind.BUY
        Regex(" up [0-9.]+% today").containsMatchIn(t) -> AlertKind.UP
        Regex(" down [0-9.]+% today").containsMatchIn(t) -> AlertKind.DOWN
        else -> AlertKind.OTHER
    }
}

private fun colorOf(k: AlertKind): Color = when (k) {
    AlertKind.UP, AlertKind.HIGH, AlertKind.GOOD_NEWS, AlertKind.QUICK, AlertKind.BUY -> Palette.up
    AlertKind.DOWN, AlertKind.BAD_NEWS, AlertKind.SELL -> Palette.down
    AlertKind.LOW -> Palette.accent
    AlertKind.OTHER -> Palette.warn
}

/** Full-screen splash for a stock alert: what happened, the live price, and what to do — in plain words. */
@Composable
fun AlertSplashScreen(vm: MainViewModel, a: AlertSplash) {
    val kind = kindOf(a)
    val color = colorOf(kind)
    val sym = a.symbol
    var chart by remember(a) { mutableStateOf<ChartResult?>(null) }
    var call by remember(a) { mutableStateOf<PlainCall?>(null) }
    LaunchedEffect(a) {
        if (sym == null) return@LaunchedEffect
        val c = runCatching { vm.repo.chart(sym, 0L) }.getOrNull() ?: return@LaunchedEffect
        chart = c
        // For a jump: the same "sell before the close or keep it?" call as Quick wins.
        if ((c.changePct ?: 0.0) > 0) {
            val st = vm.state.value
            call = vm.repo.quickFromChart(sym, c, st.holdings.any { it.symbol == sym }, sym in st.watchlist)?.let { plainCall(it) }
        }
    }
    val pulse = rememberInfiniteTransition(label = "alert")
    val s by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Palette.bg.copy(alpha = 0.97f), Palette.bg)))
            .clickable(remember { MutableInteractionSource() }, null) { }, // swallow taps behind
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).widthIn(max = 520.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(104.dp).scale(s).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text(kind.icon, fontSize = 52.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text(kind.label.uppercase(), color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Text(a.title.removePrefix("⚡ "), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = Palette.text)
            chart?.let { c ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(F.price(c.price, c.meta.currency), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                    Spacer(Modifier.size(10.dp))
                    ChangeText(c.changePct, size = 18)
                }
                Text("live price now", fontSize = 11.sp, color = Palette.muted)
            }
            Spacer(Modifier.height(16.dp))
            Text(a.body, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp, color = Palette.text)
            call?.let { pc ->
                Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(toneColor(pc.tone).copy(alpha = 0.14f)).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("What to do", fontSize = 12.sp, color = Palette.muted)
                    Text(pc.action, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = toneColor(pc.tone), textAlign = TextAlign.Center)
                    Text("Because: ${pc.because}", fontSize = 13.sp, textAlign = TextAlign.Center, color = Palette.text, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            val openLabel = when {
                kind == AlertKind.QUICK -> "Open Quick wins"
                sym != null -> "Open ${sym.removeSuffix(".OL")}"
                else -> null
            }
            if (openLabel != null) Button(
                onClick = {
                    vm.dismissAlert()
                    if (kind == AlertKind.QUICK) { vm.close(); vm.tab.value = Tab.QUICK } else sym?.let { vm.open(it) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = color),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(openLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
            TextButton(onClick = { vm.dismissAlert() }, modifier = Modifier.padding(top = 4.dp)) { Text("Close", color = Palette.muted) }
            Text("The app's best read, not a guarantee.", fontSize = 11.sp, color = Palette.muted)
        }
    }
}
