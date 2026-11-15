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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.data.QuickWin
import com.shinobisondre.stockanalyzer.engine.DayAfter
import com.shinobisondre.stockanalyzer.engine.EodLean
import com.shinobisondre.stockanalyzer.engine.PlainCall
import com.shinobisondre.stockanalyzer.engine.Tone
import com.shinobisondre.stockanalyzer.ui.ChangeText
import com.shinobisondre.stockanalyzer.ui.EmptyState
import com.shinobisondre.stockanalyzer.ui.F
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.SectionCard
import com.shinobisondre.stockanalyzer.ui.Sparkline
import com.shinobisondre.stockanalyzer.ui.appear
import com.shinobisondre.stockanalyzer.ui.pulse
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import com.shinobisondre.stockanalyzer.ui.theme.toneColor

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
            ScreenHeader("Quick wins", "Jumped today — sell before the close, keep it, or buy?") {
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
        // Three clear groups: what you own (sell or keep?), what's worth buying, and what isn't.
        if (q.items.isNotEmpty()) {
            val mine = q.items.filter { it.owned }
            val others = q.items.filter { !it.owned }
            val buys = others.filter { plainCall(it).tone == Tone.BUY }
            val skips = others.filter { plainCall(it).tone != Tone.BUY }
            if (mine.isNotEmpty()) {
                item(key = "h-mine") { GroupHeader("Yours — sell or keep?", "${mine.size} of your stocks jumped today", Palette.accent, Modifier.appear(0)) }
                itemsIndexed(mine, key = { _, w -> "qw-m-" + w.symbol }) { i, w ->
                    val held = st.holdings.firstOrNull { it.symbol == w.symbol }
                    QuickWinCard(w, held?.shares, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).appear(i + 1)) { vm.open(w.symbol) }
                }
            }
            item(key = "h-buy") { GroupHeader("Worth buying", if (buys.isEmpty()) "Nothing right now" else "${buys.size} where jumps like this usually kept going", Palette.up, Modifier.appear(1)) }
            if (buys.isEmpty()) item(key = "buy-none") {
                Text("Stocks that already jumped today usually don't keep rising enough to beat the fees. Check back later — this updates as prices move.",
                    color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            itemsIndexed(buys, key = { _, w -> "qw-b-" + w.symbol }) { i, w ->
                QuickWinCard(w, null, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).appear(i + 2)) { vm.open(w.symbol) }
            }
            if (skips.isNotEmpty()) {
                item(key = "h-skip") { GroupHeader("Don't buy", "${skips.size} that jumped but usually stall or slip back", Palette.down, Modifier.appear(2)) }
                itemsIndexed(skips, key = { _, w -> "qw-s-" + w.symbol }) { i, w ->
                    QuickWinCard(w, null, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).appear(i + 3)) { vm.open(w.symbol) }
                }
            }
        }
        item {
            SectionCard("How it decides", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), action = {
                TextButton(onClick = { how = !how }) { Text(if (how) "Hide" else "Show") }
            }) {
                Text("We looked at every time a stock jumped 3% or more in a day since 2005 — about 54,000 jumps on ~200 US and Oslo stocks — and checked what it did the next day.",
                    fontSize = 13.sp, color = Palette.muted)
                if (how) {
                    Spacer(Modifier.height(6.dp))
                    Text("• Big jumps that end the day near the top have often dropped a bit the next day → sell before the close.", fontSize = 13.sp)
                    Text("• Jumps that already slipped back from the top have often bounced the next day → keep it.", fontSize = 13.sp)
                    Text("• Otherwise it's close to a coin flip.", fontSize = 13.sp)
                    Text("Fees: a buy + sell of a US stock costs about 0.6% (currency exchange + commission on Nordnet), an Oslo stock about 0.1%. So buying something that already jumped rarely pays.",
                        fontSize = 13.sp, color = Palette.muted, modifier = Modifier.padding(top = 4.dp))
                    vm.repo.dayAfter?.let { Text("Data up to ${it.dataTo}.", fontSize = 11.sp, color = Palette.muted, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        item {
            Text("These are odds from the past, not promises — even the best pattern is wrong about 45 times out of 100. Not investment advice.",
                color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

/** "Sell before 22:00 today — because …" for one mover. */
fun plainCall(w: QuickWin): PlainCall = DayAfter.plain(w.advice, w.owned, w.costPct, if (w.sessionOpen) w.closeLocal else null)

@Composable
fun QuickWinCard(w: QuickWin, sharesHeld: Double?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    var more by rememberSaveable(w.symbol) { mutableStateOf(false) }
    val call = plainCall(w)
    SectionCard(w.symbol, modifier.clickable(onClick = onOpen), subtitle = w.name + (if (w.owned) " · you own it" else if (w.watched) " · watchlist" else ""), action = {
        Column(horizontalAlignment = Alignment.End) {
            ChangeText(w.changePct, size = 18)
            Text(F.price(w.price, w.currency), fontSize = 13.sp, color = Palette.muted)
        }
    }) {
        Text(call.action, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = toneColor(call.tone))
        Text("Because: ${call.because}", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (sharesHeld != null && w.price != null) {
            val gain = sharesHeld * w.price * (w.changePct / (100 + w.changePct))
            Text("You're up about ${F.price(gain, w.currency)} today on your ${F.num(sharesHeld, if (sharesHeld % 1.0 == 0.0) 0 else 2)} shares.",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.up, modifier = Modifier.padding(top = 6.dp))
        }
        Text(
            if (w.sessionOpen && w.minutesToClose != null) "Market closes at ${w.closeLocal ?: ""} (in ${w.minutesToClose / 60} h ${w.minutesToClose % 60} min)."
            else "The market is closed now — today's jump is final.",
            fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 4.dp),
        )
        if (w.intraday.size > 3) {
            Spacer(Modifier.height(8.dp))
            Sparkline(w.intraday, Modifier.fillMaxWidth().height(40.dp))
        }
        TextButton(onClick = { more = !more }) { Text(if (more) "Less" else "Why? Show the numbers") }
        if (more) {
            val facts = listOfNotNull(
                w.rvol?.let { "Trading ${F.num(it, 1)}× more than usual" },
                w.clv?.let { if (it >= 0.8) "Near today's high" else if (it < 0.5) "Slipped back from today's high" else "In the middle of today's range" },
                w.vwap?.let { v -> w.price?.let { p -> if (p >= v) "above today's average price" else "below today's average price" } },
            )
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), fontSize = 12.sp, color = Palette.muted)
            w.advice?.let { a -> a.lines.forEach { Text(it, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) } }
            Text("Fees for a buy + sell: about ${F.num(w.costPct, 1)}%.", fontSize = 11.sp, color = Palette.muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun GroupHeader(title: String, sub: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.width(4.dp).height(34.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            Text(sub, fontSize = 12.sp, color = Palette.muted)
        }
    }
}

/** One-line home-page banner for a big mover you own or watch. */
@Composable
fun QuickWinBanner(movers: List<QuickWin>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (movers.isEmpty()) return
    SectionCard("Quick win spotted", modifier.clickable(onClick = onClick), action = { Icon(BoltIcon, null, tint = Palette.warn, modifier = Modifier.pulse()) }) {
        movers.take(3).forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(w.symbol, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp))
                ChangeText(w.changePct, size = 13)
                Spacer(Modifier.width(8.dp))
                val c = plainCall(w)
                Text(c.action, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = toneColor(c.tone), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        Text("Tap to see why", fontSize = 12.sp, color = Palette.accent, modifier = Modifier.padding(top = 4.dp))
    }
}
