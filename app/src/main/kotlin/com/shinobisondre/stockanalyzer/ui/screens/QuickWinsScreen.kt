package com.shinobisondre.stockanalyzer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.data.QuickWin
import com.shinobisondre.stockanalyzer.engine.EodLean
import com.shinobisondre.stockanalyzer.engine.Tone
import com.shinobisondre.stockanalyzer.ui.ChangeText
import com.shinobisondre.stockanalyzer.ui.EmptyState
import com.shinobisondre.stockanalyzer.ui.F
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.Pill
import com.shinobisondre.stockanalyzer.ui.SectionCard
import com.shinobisondre.stockanalyzer.ui.Sparkline
import com.shinobisondre.stockanalyzer.ui.ToneChip
import com.shinobisondre.stockanalyzer.ui.theme.Palette

/** Lightning bolt (material "flash_on"), drawn here so the app doesn't need the big icon library. */
val BoltIcon: ImageVector = materialIcon(name = "Filled.Bolt") {
    materialPath {
        moveTo(7f, 2f); verticalLineToRelative(11f); horizontalLineToRelative(3f); verticalLineToRelative(9f)
        lineToRelative(7f, -12f); horizontalLineToRelative(-4f); lineToRelative(4f, -8f); close()
    }
}

fun leanTone(l: EodLean?): Tone = when (l) { EodLean.SELL_BY_CLOSE -> Tone.SELL; EodLean.HOLD_OVERNIGHT -> Tone.BUY; else -> Tone.NEUTRAL }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickWinsScreen(vm: MainViewModel) {
    val q by vm.quick.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadQuick() }
    var how by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader("Quick wins", "Up big today — take the money by the close, or hold?") {
                IconButton(onClick = { vm.loadQuick(true) }, enabled = !q.running) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
        }
        item {
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip("Mine + watchlist", !q.includeMarket) { vm.setQuickMarket(false) }
                SmallChip("Whole market", q.includeMarket) { vm.setQuickMarket(true) }
            }
        }
        if (q.running) item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(if (q.total > 0) "Checking ${q.done} of ${q.total} stocks…" else "Finding today's movers…", color = Palette.muted, fontSize = 13.sp)
                LinearProgressIndicator(progress = { if (q.total > 0) q.done.toFloat() / q.total else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Palette.accent)
            }
        }
        if (!q.running && q.at > 0 && q.items.isEmpty()) item {
            EmptyState("Nothing up ${q.minMove.toInt()}%+ right now",
                "Quick wins lists stocks that are up at least ${q.minMove.toInt()}% today" + (if (q.includeMarket) " among your holdings, watchlist, Yahoo's top gainers and ~150 large US and Oslo names." else " among your holdings and watchlist.") +
                    " Check back while the market is open (Oslo 09:00–16:20, New York 15:30–22:00 Norwegian time).")
        }
        items(q.items, key = { "qw-" + it.symbol }) { w ->
            val held = st.holdings.firstOrNull { it.symbol == w.symbol }
            QuickWinCard(w, held?.shares, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { vm.open(w.symbol) }
        }
        item {
            SectionCard("How the lean is worked out", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), action = {
                TextButton(onClick = { how = !how }) { Text(if (how) "Hide" else "Show") }
            }) {
                val book = vm.repo.dayAfter
                Text("Every +3% day since 2005 on ~200 US and Oslo stocks (${book?.let { "%,d".format(it.us.base.n) } ?: "—"} US trading days in total), split by how big the move is, how heavy the volume is, where the price sits in the day's range, and the long-term trend.",
                    fontSize = 13.sp, color = Palette.muted)
                if (how) {
                    Spacer(Modifier.height(6.dp))
                    Text("• \"Take profit by the close\": days like this closed lower the next session clearly more often than an ordinary day, and the average next-day move was negative.", fontSize = 13.sp)
                    Text("• \"Holding overnight has paid\": they kept rising more often than usual, with a positive average.", fontSize = 13.sp)
                    Text("• Otherwise there's no measurable edge either way.", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("What stood out: big US up-days (+5–12%) that finish near the day's high have tended to slip the next session (43–46% up vs ~52% normally). Days that faded from their high have tended to bounce.", fontSize = 13.sp, color = Palette.muted)
                    Text("Costs: selling US shares from a NOK account costs roughly 0.5–0.6% in FX + commission round trip on Nordnet; Oslo shares ~0.1%. Chasing a stock that is already up for a next-day exit rarely clears that.", fontSize = 13.sp, color = Palette.muted, modifier = Modifier.padding(top = 4.dp))
                    book?.let { Text("Data to ${it.dataTo}. Survivorship bias: the stock list is today's, so past losers that delisted are missing.", fontSize = 11.sp, color = Palette.muted, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        item {
            Text("Historical tendencies, not promises: even the strongest lean is wrong roughly 45% of the time. Not investment advice.",
                color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickWinCard(w: QuickWin, sharesHeld: Double?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    SectionCard(w.symbol, modifier.clickable(onClick = onOpen), subtitle = w.name, action = {
        Column(horizontalAlignment = Alignment.End) {
            ChangeText(w.changePct, size = 18)
            Text(F.price(w.price, w.currency), fontSize = 13.sp, color = Palette.muted)
        }
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToneChip(w.advice?.lean?.title ?: "No history for this size of move", leanTone(w.advice?.lean))
            if (w.owned) Pill("You own it", Palette.accent)
            else if (w.watched) Pill("Watchlist", Palette.accent)
        }
        if (w.intraday.size > 3) {
            Spacer(Modifier.height(8.dp))
            Sparkline(w.intraday, Modifier.fillMaxWidth().height(44.dp))
        }
        Spacer(Modifier.height(8.dp))
        val facts = listOfNotNull(
            w.rvol?.let { "Volume ${F.num(it, 1)}× normal" },
            w.clv?.let { "at ${(it * 100).toInt()}% of today's range" },
            w.fromHighPct?.let { if (it < -0.05) "${F.num(-it, 1)}% below the high" else "at the day's high" },
            w.vwap?.let { v -> w.price?.let { p -> if (p >= v) "above VWAP" else "below VWAP" } },
            w.above200?.let { if (it) "above 200-day" else "below 200-day" },
        )
        Text(facts.joinToString(" · "), fontSize = 12.sp, color = Palette.muted)
        val timing = when {
            w.sessionOpen && w.minutesToClose != null -> "Market closes ${w.closeLocal ?: ""} — in ${w.minutesToClose / 60} h ${w.minutesToClose % 60} min."
            else -> "Market closed — this is today's final move. The lean is about tomorrow."
        }
        Text(timing, fontSize = 12.sp, color = Palette.text, modifier = Modifier.padding(top = 4.dp))
        if (sharesHeld != null && w.price != null) {
            val gain = sharesHeld * w.price * (w.changePct / (100 + w.changePct))
            Text("Today's gain on your ${F.num(sharesHeld, if (sharesHeld % 1.0 == 0.0) 0 else 2)} shares: ≈ ${F.price(gain, w.currency)}", fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, color = Palette.up, modifier = Modifier.padding(top = 4.dp))
        }
        w.advice?.let { a ->
            Spacer(Modifier.height(6.dp))
            a.lines.forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        Text("Round-trip cost ≈ ${F.num(w.costPct, 1)}%", fontSize = 11.sp, color = Palette.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** One-line home-page banner for a big mover you own or watch. */
@Composable
fun QuickWinBanner(movers: List<QuickWin>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (movers.isEmpty()) return
    SectionCard("Quick win spotted", modifier.clickable(onClick = onClick), action = { Icon(BoltIcon, null, tint = Palette.warn) }) {
        movers.take(3).forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(w.symbol, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp))
                ChangeText(w.changePct, size = 13)
                Spacer(Modifier.width(8.dp))
                Text(w.advice?.lean?.title?.removePrefix("Lean: ") ?: "", fontSize = 12.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        Text("Tap for sell-by-the-close odds", fontSize = 12.sp, color = Palette.accent, modifier = Modifier.padding(top = 4.dp))
    }
}
