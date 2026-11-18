package com.shinobisondre.stockanalyzer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.data.ScoreView
import com.shinobisondre.stockanalyzer.engine.Checked
import com.shinobisondre.stockanalyzer.engine.GuessKind
import com.shinobisondre.stockanalyzer.engine.RangeForecast
import com.shinobisondre.stockanalyzer.engine.ScoreLine
import com.shinobisondre.stockanalyzer.ui.AdaptiveGrid
import com.shinobisondre.stockanalyzer.ui.EmptyState
import com.shinobisondre.stockanalyzer.ui.F
import com.shinobisondre.stockanalyzer.ui.Load
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.SectionCard
import com.shinobisondre.stockanalyzer.ui.Tab
import com.shinobisondre.stockanalyzer.ui.appear
import com.shinobisondre.stockanalyzer.ui.cell
import com.shinobisondre.stockanalyzer.ui.full
import com.shinobisondre.stockanalyzer.ui.theme.Palette

private fun waitText(k: GuessKind) = when (k) {
    GuessKind.PICK -> "Picks are checked a month (22 trading days) after they're made."
    GuessKind.QUICK -> "Quick-win calls are checked after the next day's close."
    GuessKind.RANGE -> "Lows & highs are checked after the week or month they cover."
}

/** "Was the app right?" — every pick, quick-win call and low/high it made, checked against what happened. */
@Composable
fun ScorecardScreen(vm: MainViewModel) {
    val s by vm.score.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadScore() }
    var filter by rememberSaveable { mutableStateOf<GuessKind?>(null) }
    AdaptiveGrid(contentPadding = PaddingValues(bottom = 24.dp)) {
        full {
            ScreenHeader("Scorecard", "Did the app get it right?") {
                IconButton(onClick = { vm.loadScore(true) }, enabled = s !is Load.Loading) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
        }
        full {
            Text("Every time the app makes a pick, a quick-win call or a guess at a stock's low and high, it saves what it said and the price at that moment. Later it checks what really happened — the good and the bad.",
                color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        when (val st = s) {
            is Load.Loading, Load.Idle -> full {
                Column(Modifier.padding(16.dp)) {
                    Text("Checking past calls against real prices…", color = Palette.muted, fontSize = 13.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = Palette.accent)
                }
            }
            is Load.Err -> full { EmptyState("Couldn't check", st.msg, actionLabel = "Try again") { vm.loadScore(true) } }
            is Load.Ok -> {
                val v = st.v
                if (v.saved == 0) full {
                    EmptyState("Nothing saved yet", "Open Picks, Quick wins or any stock and the app starts saving what it says. Come back in a few days to see how it did.")
                } else {
                    GuessKind.entries.forEachIndexed { i, k ->
                        cell("sum-$k") { KindCard(v, k, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).appear(i)) }
                    }
                    full {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            FilterChip(filter == null, { filter = null }, { Text("All") }, Modifier.padding(end = 6.dp))
                            GuessKind.entries.forEach { k -> FilterChip(filter == k, { filter = k }, { Text(k.title) }, Modifier.padding(end = 6.dp)) }
                        }
                    }
                    val list = v.items.filter { filter == null || it.guess.kind == filter }.take(120)
                    list.forEachIndexed { i, c ->
                        cell("g-${c.guess.key}") { GuessRow(vm, c, Modifier.padding(horizontal = 12.dp, vertical = 4.dp).appear(minOf(i, 8))) }
                    }
                }
            }
        }
        full {
            Text("A small number of checks can be lucky or unlucky — the numbers get trustworthy after a few dozen. Not investment advice.",
                color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun KindCard(v: ScoreView, k: GuessKind, modifier: Modifier = Modifier) {
    val lines = v.board.lines[k].orEmpty()
    val waiting = v.board.waiting[k] ?: 0
    SectionCard(k.title, modifier, subtitle = "${v.board.checked[k] ?: 0} checked · $waiting waiting") {
        if (lines.isEmpty()) Text("Nothing to check yet. ${waitText(k)}", fontSize = 13.sp, color = Palette.muted)
        lines.forEach { ScoreLineView(it) }
        if (lines.isNotEmpty() && waiting > 0) Text("$waiting more waiting. ${waitText(k)}", fontSize = 11.sp, color = Palette.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ScoreLineView(l: ScoreLine) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            val r = l.rate
            if (!r.isNaN()) Text("${(r * 100).toInt()}% right", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = if (r >= 0.55) Palette.up else if (r < 0.45) Palette.down else Palette.warn)
        }
        if (!l.rate.isNaN()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.muted.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(l.rate.toFloat().coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (l.rate >= 0.55) Palette.up else if (l.rate < 0.45) Palette.down else Palette.warn))
            }
        }
        Text(l.text, fontSize = 12.sp)
    }
}

@Composable
private fun GuessRow(vm: MainViewModel, c: Checked, modifier: Modifier = Modifier) {
    val g = c.guess
    val (mark, color) = when (c.right) { true -> "✓" to Palette.up; false -> "✗" to Palette.down; null -> (if (c.done) "•" else "…") to Palette.muted }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surface).clickable { vm.open(g.symbol) }.padding(10.dp),
        verticalAlignment = Alignment.Top) {
        Text(mark, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.symbol.removeSuffix(".OL"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(g.kind.title + " · " + java.time.Instant.ofEpochMilli(g.madeAt).atZone(java.time.ZoneId.systemDefault()).let {
                    "%02d.%02d %02d:%02d".format(it.dayOfMonth, it.monthValue, it.hour, it.minute) }, fontSize = 11.sp, color = Palette.muted)
            }
            val said = when (g.kind) {
                GuessKind.RANGE -> "Said: ${g.label.lowercase()} likely ${F.num(g.likelyLow, 2)}–${F.num(g.likelyHigh, 2)}, outer ${F.num(g.wideLow, 2)}–${F.num(g.wideHigh, 2)} (price then ${F.num(g.price, 2)})"
                else -> "Said: “${g.label}” at ${F.num(g.price, 2)}"
            }
            Text(said, fontSize = 12.sp)
            Text(c.text, fontSize = 12.sp, color = if (c.right == null) Palette.muted else color, fontWeight = if (c.done) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

/** Small "how did past calls of this kind do?" card for the Picks and Quick wins screens. */
@Composable
fun ScoreTeaser(vm: MainViewModel, kind: GuessKind, modifier: Modifier = Modifier) {
    val s by vm.score.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadScore() }
    val v = (s as? Load.Ok)?.v ?: return
    val lines = v.board.lines[kind].orEmpty()
    val waiting = v.board.waiting[kind] ?: 0
    SectionCard("How past ${kind.title.lowercase()} did", modifier, subtitle = "Saved and checked against real prices", action = {
        TextButton(onClick = { vm.tab.value = Tab.SCORE }) { Text("See all") }
    }) {
        if (lines.isEmpty()) Text(if (waiting > 0) "$waiting saved so far. ${waitText(kind)}" else "Nothing saved yet. ${waitText(kind)}",
            fontSize = 13.sp, color = Palette.muted)
        lines.forEach { ScoreLineView(it) }
    }
}

/** "How low could it dip, how high could it get?" for one stock. */
@Composable
fun RangeCard(ranges: List<RangeForecast>, currency: String, modifier: Modifier = Modifier) {
    if (ranges.isEmpty()) return
    var pick by rememberSaveable { mutableStateOf(0) }
    val r = ranges[pick.coerceIn(0, ranges.size - 1)]
    SectionCard("Likely lows & highs", modifier, subtitle = "From its own past swings, the odds, earnings and fresh news",
        help = "Not a promise — a range. \"Likely\" means it got that far about half the time on this stock's past. The outer numbers are only passed about 1 time in 10. The scorecard checks these later.") {
        Row(Modifier.padding(bottom = 8.dp)) {
            ranges.forEachIndexed { i, x -> FilterChip(pick == i, { pick = i }, { Text(x.label) }, Modifier.padding(end = 6.dp)) }
        }
        RangeBar(r)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Likely low", fontSize = 11.sp, color = Palette.muted)
                Text(F.price(r.likelyLow, currency), fontWeight = FontWeight.Bold, color = Palette.down)
                Text("1 in 10: below ${F.price(r.wideLow, currency)}", fontSize = 11.sp, color = Palette.muted)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("Likely high", fontSize = 11.sp, color = Palette.muted)
                Text(F.price(r.likelyHigh, currency), fontWeight = FontWeight.Bold, color = Palette.up)
                Text("1 in 10: above ${F.price(r.wideHigh, currency)}", fontSize = 11.sp, color = Palette.muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        r.lines.drop(2).forEach { l ->
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                val (mark, color) = when (l.good) { true -> "✓" to Palette.up; false -> "✗" to Palette.down; null -> "•" to Palette.muted }
                Text(mark, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(20.dp))
                Text(l.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
        val lh = r.pastLowHit; val hh = r.pastHighHit
        if (lh != null && hh != null)
            Text("Tested on this stock's last months (tuned on older data): reached the likely low ${(lh * 100).toInt()}% and the likely high ${(hh * 100).toInt()}% of the time — about 50% means well tuned.",
                fontSize = 11.sp, color = Palette.muted, modifier = Modifier.padding(top = 8.dp))
    }
}

/** A bar from the outer low to the outer high with the likely zone and today's price. */
@Composable
private fun RangeBar(r: RangeForecast) {
    val span = (r.wideHigh - r.wideLow).takeIf { it > 0 } ?: return
    fun f(x: Double) = ((x - r.wideLow) / span).toFloat().coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.muted.copy(alpha = 0.15f))) {
        Row(Modifier.fillMaxWidth().height(14.dp)) {
            val a = f(r.likelyLow); val p = f(r.price); val b = f(r.likelyHigh)
            if (a > 0f) Spacer(Modifier.weight(a))
            if (p - a > 0f) Box(Modifier.weight(p - a).height(14.dp).background(Palette.down.copy(alpha = 0.45f)))
            Box(Modifier.width(3.dp).height(14.dp).background(androidx.compose.material3.MaterialTheme.colorScheme.onSurface))
            if (b - p > 0f) Box(Modifier.weight(b - p).height(14.dp).background(Palette.up.copy(alpha = 0.45f)))
            if (1f - b > 0f) Spacer(Modifier.weight(1f - b))
        }
    }
}

/** One plain line for a pick card: next week's likely range. */
fun rangeLine(r: RangeForecast?, currency: String): String? = r?.let {
    "${it.label}: likely dips to ${F.price(it.likelyLow, currency)} and reaches ${F.price(it.likelyHigh, currency)}" +
        if (it.earningsInside) " (earnings inside — bigger swings)" else ""
}
