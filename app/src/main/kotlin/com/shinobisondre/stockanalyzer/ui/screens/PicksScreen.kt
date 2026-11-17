package com.shinobisondre.stockanalyzer.ui.screens

import com.shinobisondre.stockanalyzer.ui.AdaptiveGrid
import com.shinobisondre.stockanalyzer.ui.full
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import com.shinobisondre.stockanalyzer.data.Repository
import com.shinobisondre.stockanalyzer.engine.PickTier
import com.shinobisondre.stockanalyzer.ui.ChangeText
import com.shinobisondre.stockanalyzer.ui.EmptyState
import com.shinobisondre.stockanalyzer.ui.F
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.SectionCard
import com.shinobisondre.stockanalyzer.ui.appear
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import com.shinobisondre.stockanalyzer.ui.theme.toneColor

/**
 * Stocks that look good to buy and hold for a few months — the original web app's
 * Top Potential scoring plus this app's odds, Tyler's checklists and the news,
 * every reason in plain words.
 */
@Composable
fun PicksScreen(vm: MainViewModel) {
    val p by vm.picks.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadPicks() }
    var showAvoid by rememberSaveable { mutableStateOf(false) }
    val buys = p.items.filter { it.pick.tier == PickTier.BUY }
    val watch = p.items.filter { it.pick.tier == PickTier.WATCH }
    val avoid = p.items.filter { it.pick.tier == PickTier.AVOID }

    AdaptiveGrid(contentPadding = PaddingValues(bottom = 24.dp)) {
        full {
            ScreenHeader("Picks", "Stocks that look good to buy — and why, in plain words") {
                IconButton(onClick = { vm.loadPicks(true) }, enabled = !p.running) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
        }
        full {
            Text("Not quick wins: these are for holding a few months. Each stock is scored on the business (profits, growth, price vs profits, debt), what analysts say, the measured odds, Tyler Hill's checklists and the latest news.",
                color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        if (p.running) full {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Checking ${p.done} of ${p.total} stocks — company facts, analysts, odds and news…", color = Palette.muted, fontSize = 13.sp)
                LinearProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Palette.accent)
            }
        }
        if (!p.running && p.at > 0 && p.items.isEmpty()) full {
            EmptyState("Couldn't check any stocks", "No connection to Yahoo Finance right now. Tap refresh in a bit.")
        }
        if (p.items.isNotEmpty()) {
            full { PickGroupHeader("Good to buy", if (buys.isEmpty()) "Nothing stands out right now" else "${buys.size} with clearly more reasons for than against", Palette.up) }
            if (buys.isEmpty()) full {
                Text("Nothing passes every test today. That's normal — good buys don't show up every day. Check the \"Keep an eye on it\" list.",
                    color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            itemsIndexed(buys, key = { _, v -> "pk-b-" + v.pick.symbol }) { i, v -> PickCard(vm, v, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).appear(i + 1)) }
            full { PickGroupHeader("Keep an eye on it", "${watch.size} with a mix of good and bad", Palette.warn) }
            itemsIndexed(watch, key = { _, v -> "pk-w-" + v.pick.symbol }) { i, v -> PickCard(vm, v, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).appear(i + 1)) }
            full {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PickGroupHeader("Stay away for now", "${avoid.size} with more against than for", Palette.down) }
                    TextButton(onClick = { showAvoid = !showAvoid }, modifier = Modifier.padding(end = 8.dp, top = 12.dp)) { Text(if (showAvoid) "Hide" else "Show") }
                }
            }
            if (showAvoid) itemsIndexed(avoid, key = { _, v -> "pk-a-" + v.pick.symbol }) { i, v -> PickCard(vm, v, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).appear(i)) }
        }
        full {
            Text("These are odds and rules of thumb, not promises. Even good picks fall sometimes — buy in small pieces and don't put everything in one stock. Not investment advice.",
                color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun PickGroupHeader(title: String, sub: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            Text(sub, fontSize = 12.sp, color = Palette.muted)
        }
    }
}

@Composable
fun PickCard(vm: MainViewModel, v: Repository.PickView, modifier: Modifier = Modifier) {
    val pk = v.pick
    SectionCard(pk.symbol.removeSuffix(".OL") + if (v.owned) "  ★" else "", modifier.clickable { vm.open(pk.symbol) }, subtitle = pk.name, action = {
        Column(horizontalAlignment = Alignment.End) {
            Text(F.price(v.price, v.currency), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            ChangeText(v.changePct, size = 12)
        }
    }) {
        Text(pk.tier.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = toneColor(pk.tier.tone))
        Text(pk.tier.headline, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        pk.breaking?.let { b ->
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp))
                .background((if (b.sentiment > 0) Palette.up else Palette.down).copy(alpha = 0.12f)).padding(10.dp)) {
                Text("Breaking", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (b.sentiment > 0) Palette.up else Palette.down)
                Text(b.title, fontSize = 13.sp)
            }
        }
        Text("Why:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        pk.reasons.forEach { r ->
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                val (mark, color) = when (r.good) { true -> "✓" to Palette.up; false -> "✗" to Palette.down; null -> "•" to Palette.muted }
                Text(mark, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(20.dp))
                Text(r.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(toneColor(pk.tier.tone)))
            Text("  Score ${F.num(pk.score, 1)}" + (pk.quality?.let { " · business: ${it.tier.lowercase()}" } ?: ""), fontSize = 11.sp, color = Palette.muted)
        }
    }
}
