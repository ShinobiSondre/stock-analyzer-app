package com.shinobisondre.stockanalyzer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.data.Core
import com.shinobisondre.stockanalyzer.data.Extras
import com.shinobisondre.stockanalyzer.engine.Analysis
import com.shinobisondre.stockanalyzer.engine.Call
import com.shinobisondre.stockanalyzer.engine.LevelType
import com.shinobisondre.stockanalyzer.engine.LoggedCall
import com.shinobisondre.stockanalyzer.engine.Read
import com.shinobisondre.stockanalyzer.engine.StackRead
import com.shinobisondre.stockanalyzer.engine.Tone
import com.shinobisondre.stockanalyzer.engine.TrackRecord
import com.shinobisondre.stockanalyzer.ui.CallBadge
import com.shinobisondre.stockanalyzer.ui.ChangeText
import com.shinobisondre.stockanalyzer.ui.DriverBar
import com.shinobisondre.stockanalyzer.ui.ErrorBox
import com.shinobisondre.stockanalyzer.ui.F
import com.shinobisondre.stockanalyzer.ui.KeyValue
import com.shinobisondre.stockanalyzer.ui.Load
import com.shinobisondre.stockanalyzer.ui.Loading
import com.shinobisondre.stockanalyzer.ui.MacdPane
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.Overlay
import com.shinobisondre.stockanalyzer.ui.Pill
import com.shinobisondre.stockanalyzer.ui.PriceChart
import com.shinobisondre.stockanalyzer.ui.ProbBar
import com.shinobisondre.stockanalyzer.ui.RsiPane
import com.shinobisondre.stockanalyzer.ui.SectionCard
import com.shinobisondre.stockanalyzer.ui.ToneChip
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import com.shinobisondre.stockanalyzer.ui.theme.changeColor
import com.shinobisondre.stockanalyzer.ui.theme.toneColor
import kotlin.math.abs
import kotlin.math.roundToInt

private val PERIODS = listOf("1M" to 22, "3M" to 66, "6M" to 126, "1Y" to 252, "2Y" to 504)

@Composable
fun AnalysisScreen(vm: MainViewModel, symbol: String, showBack: Boolean, tabletopTop: Dp?) {
    val cores by vm.cores.collectAsStateWithLifecycle()
    val extrasMap by vm.extras.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(symbol) { vm.load(symbol); vm.loadExtras(symbol) }
    val load = cores[symbol]
    val core = (load as? Load.Ok)?.v
    var addOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = { vm.close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            else Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(symbol, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
                Text(core?.name ?: "", color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val watching = symbol in st.watchlist
            IconButton(onClick = { vm.toggleWatch(symbol) }) {
                Icon(if (watching) Icons.Filled.Star else Icons.Outlined.Star, if (watching) "Remove from watchlist" else "Add to watchlist", tint = if (watching) Palette.sma20 else Palette.muted)
            }
            IconButton(onClick = { addOpen = true }) { Icon(Icons.Filled.Add, "Add to portfolio") }
            IconButton(onClick = { vm.load(symbol, true); vm.loadExtras(symbol, true) }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        when (load) {
            is Load.Ok -> AnalysisBody(vm, load.v, extrasMap[symbol], st.calls.filter { it.symbol == symbol }, tabletopTop)
            is Load.Err -> ErrorBox(load.msg) { vm.load(symbol, true) }
            else -> Loading(text = "Analysing $symbol…")
        }
    }
    if (addOpen) {
        val existing = st.holdings.firstOrNull { it.symbol == symbol }
        HoldingDialog(symbol, existing, core?.price, onDismiss = { addOpen = false }) { sym, shares, cost, replace ->
            vm.upsertHolding(sym, shares, cost, replace); addOpen = false; vm.say("$sym saved to your portfolio")
        }
    }
}

@Composable
private fun AnalysisBody(vm: MainViewModel, core: Core, extras: Load<Extras>?, calls: List<LoggedCall>, tabletopTop: Dp?) {
    var periodIdx by rememberSaveable(core.symbol) { mutableStateOf(2) }
    var overlays by remember { mutableStateOf(setOf(Overlay.SMA50, Overlay.SMA200)) }
    var sel by remember(core.symbol) { mutableStateOf<Int?>(null) }
    var panes by rememberSaveable { mutableStateOf("RSI") }
    val bars = PERIODS[periodIdx].second.coerceAtMost(core.analysis.series.n)

    val chart: @Composable () -> Unit = {
        ChartCard(core, bars, periodIdx, { periodIdx = it; sel = null }, overlays, { o -> overlays = if (o in overlays) overlays - o else overlays + o }, sel, { sel = it }, panes) { panes = it }
    }

    if (tabletopTop != null) {
        // Flex mode: chart on the upper half, everything else scrolls on the lower half.
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(tabletopTop - 56.dp).verticalScroll(rememberScrollState())) { Column(Modifier.padding(horizontal = 12.dp)) { chart() } }
            Spacer(Modifier.height(24.dp))
            CardsGrid(vm, core, extras, calls, includeChart = false, chart = chart)
        }
    } else {
        CardsGrid(vm, core, extras, calls, includeChart = true, chart = chart)
    }
}

@Composable
private fun CardsGrid(vm: MainViewModel, core: Core, extras: Load<Extras>?, calls: List<LoggedCall>, includeChart: Boolean, chart: @Composable () -> Unit) {
    val a = core.analysis
    val tylerState by vm.tyler.collectAsStateWithLifecycle()
    val book by vm.trades.collectAsStateWithLifecycle()
    val myTrades = remember(book, core.symbol) { book?.forSymbol(core.symbol).orEmpty() }
    val hasTylerTake = remember(tylerState.takes, core.symbol) {
        com.shinobisondre.stockanalyzer.engine.TylerTalk.latestOn(tylerState.takes.values.toList(), core.symbol) != null
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoCol = maxWidth >= 700.dp
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(if (twoCol) 2 else 1),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
            verticalItemSpacing = 10.dp,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "price", span = StaggeredGridItemSpan.FullLine) { PriceHeader(core, (extras as? Load.Ok)?.v) }
            item(key = "verdict", span = StaggeredGridItemSpan.FullLine) { VerdictCard(core) }
            if (includeChart) item(key = "chart", span = StaggeredGridItemSpan.FullLine) { chart() }
            ((extras as? Load.Ok)?.v?.ranges)?.takeIf { it.isNotEmpty() }?.let { rs -> item(key = "range") { RangeCard(rs, core.currency) } }
            item(key = "drivers") { DriversCard(a) }
            if (a.rules.setups.isNotEmpty()) item(key = "setups") { SetupsCard(a) }
            a.holding?.let { h -> item(key = "hold") { HoldingCard(h) } }
            if (hasTylerTake) item(key = "tylerTake") { TylerOnStock(vm, core.symbol) }
            if (myTrades.isNotEmpty()) item(key = "trades") { TradesCard(core, myTrades, vm.state.value.holdings.firstOrNull { it.symbol == core.symbol }) }
            item(key = "summary") { SummaryCard(a) }
            item(key = "invested") { InvestedCard(core, vm.state.value.settings.baseCurrency) }
            item(key = "ripple") { RippleCard(vm, core.symbol) }
            item(key = "fund") { FundamentalsCard(core, extras) }
            item(key = "cat") { CatalystsCard(extras) }
            item(key = "news") { NewsCard(extras) }
            item(key = "ind") { IndicatorsCard(a) }
            item(key = "tyler") { TylerCard(a) }
            item(key = "levels") { LevelsCard(a, core.currency) }
            item(key = "rules") { RulesCard(a) }
            item(key = "track") { SymbolTrackCard(a, calls) }
            item(key = "disc", span = StaggeredGridItemSpan.FullLine) {
                Text("Model ${a.modelVersion ?: "not loaded"} · data ${a.lastDate} · Yahoo Finance. Probabilities are measured frequencies from 2005-2026 back-tests, not promises. Not investment advice.",
                    color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun PriceHeader(core: Core, extras: Extras?) {
    val m = core.chart.meta
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(F.price(core.price, core.currency), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Column {
                ChangeText(core.changePct, size = 15)
                Text(core.change?.let { (if (it > 0) "+" else "") + F.num(it) } ?: "", color = changeColor(core.change), fontSize = 12.sp)
            }
        }
        val q = extras?.quote
        val ext = when (q?.marketState) {
            "PRE", "PREPRE" -> q?.preMarketPrice?.let { "Pre-market ${F.price(it, core.currency)} (${F.pct(q?.preMarketChangePct)})" }
            "POST", "POSTPOST", "CLOSED" -> q?.postMarketPrice?.let { "After hours ${F.price(it, core.currency)} (${F.pct(q?.postMarketChangePct)})" }
            else -> null
        }
        if (ext != null) Text(ext, color = Palette.warn, fontSize = 13.sp)
        val live = m.sessionOpen()
        Text(listOfNotNull(m.exchange, core.currency, if (live) "market open" else "last close ${core.analysis.lastDate}").joinToString(" · "), color = Palette.muted, fontSize = 12.sp)
        val lo = m.low52; val hi = m.high52; val p = core.price
        if (lo != null && hi != null && p != null && hi > lo) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(F.num(lo), color = Palette.muted, fontSize = 11.sp)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(6.dp).clip(RoundedCornerShape(50)).background(Palette.surface2)) {
                    val f = ((p - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
                    Row(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(f.coerceAtLeast(0.001f)))
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.accent))
                        Spacer(Modifier.weight((1 - f).coerceAtLeast(0.001f)))
                    }
                }
                Text(F.num(hi), color = Palette.muted, fontSize = 11.sp)
            }
            Text("52-week range", color = Palette.muted, fontSize = 11.sp)
        }
    }
}

/** Your own buys and sells of this stock, from the imported Nordnet trades. */
@Composable
private fun TradesCard(core: Core, trades: List<com.shinobisondre.stockanalyzer.engine.Trade>, holding: com.shinobisondre.stockanalyzer.data.Holding?) {
    val pos = remember(trades) { com.shinobisondre.stockanalyzer.engine.NordnetNotes.ledger(trades).firstOrNull() }
    val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
    fun d(s: String) = runCatching { java.time.LocalDate.parse(s).format(fmt) }.getOrDefault(s)
    var all by remember { mutableStateOf(false) }
    SectionCard("Your trades", subtitle = "${trades.size} from your Nordnet export") {
        if (pos != null) {
            if (pos.open) {
                Text("You've held ${F.num(pos.quantity, if (pos.quantity % 1.0 == 0.0) 0 else 2)} shares since ${pos.firstBuy?.let { d(it) } ?: "—"}.", fontSize = 14.sp)
                pos.avgCostNok?.let { cn ->
                    Text("They cost ${F.num(cn, 0)} kr each on average (exchange rate and fees on the day) — ${F.price(pos.avgCost, pos.currency)} in ${pos.currency}.",
                        fontSize = 13.sp, color = Palette.muted, modifier = Modifier.padding(top = 2.dp))
                }
            } else Text("You've sold all your shares.", fontSize = 14.sp)
            if (kotlin.math.abs(pos.realizedNok) > 0.5) Text(
                "Already sold: ${if (pos.realizedNok >= 0) "gain" else "loss"} of ${F.num(kotlin.math.abs(pos.realizedNok), 0)} kr after fees.",
                fontSize = 13.sp, color = if (pos.realizedNok >= 0) Palette.up else Palette.down, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(8.dp))
        }
        val shown = trades.sortedWith(compareByDescending<com.shinobisondre.stockanalyzer.engine.Trade> { it.date }.thenByDescending { it.time }).let { if (all) it else it.take(6) }
        shown.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${if (t.buy) "Bought" else "Sold"} ${F.num(t.quantity, if (t.quantity % 1.0 == 0.0) 0 else 4)} @ ${F.price(t.price, t.currency)}",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (t.buy) Palette.up else Palette.down)
                    Text(d(t.date) + (if (t.time.isNotBlank()) " · ${t.time.take(5)}" else "") + (t.fx?.let { " · ${t.currency}/NOK ${F.num(it, 2)}" } ?: ""),
                        fontSize = 11.sp, color = Palette.muted)
                }
                t.totalNok?.let { Text("${F.num(it, 0)} kr", fontSize = 13.sp) }
            }
        }
        if (trades.size > 6) TextButton(onClick = { all = !all }) { Text(if (all) "Show fewer" else "Show all ${trades.size}") }
    }
}

/** "If you had put 10,000 kr in…" — how the ride has looked, in money. */
@Composable
private fun InvestedCard(core: Core, base: String) {
    val cs = core.chart.candles
    if (cs.size < 30) return
    val last = cs.last().adjClose
    val sym = if (base == "NOK" || base == "SEK" || base == "DKK") "kr" else base
    val spans = listOf(21 to "1 month ago", 126 to "6 months ago", 252 to "1 year ago", 504 to "2 years ago").filter { it.first < cs.size }
    SectionCard("If you had invested", subtitle = "10,000 $sym in ${core.symbol.removeSuffix(".OL")}") {
        spans.forEach { (n, label) ->
            val then = cs[cs.size - 1 - n].adjClose
            if (then > 0) {
                val worth = 10_000 * last / then
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    com.shinobisondre.stockanalyzer.ui.AnimatedNumber(worth, { "%,.0f $sym".format(java.util.Locale.US, it).replace(',', ' ') },
                        style = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        color = if (worth >= 10_000) Palette.up else Palette.down)
                }
            }
        }
        // The worst drop along the way — what holding it actually felt like.
        val window = cs.takeLast(252).map { it.adjClose }
        var peak = window.first(); var worst = 0.0
        for (p in window) { peak = maxOf(peak, p); worst = minOf(worst, p / peak - 1) }
        Text("Along the way (last year) it was at worst ${F.num(-worst * 100, 0)}% below its high — that's the ride you'd have had to sit through.",
            fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
        Text("Ignores currency changes and fees.", fontSize = 11.sp, color = Palette.muted)
    }
}

/** Link to the ripple tree for this stock (and its top 3 links if already measured). */
@Composable
private fun RippleCard(vm: MainViewModel, symbol: String) {
    val r by vm.ripple.collectAsStateWithLifecycle()
    val view = (r as? Load.Ok)?.v?.takeIf { it.root == symbol }
    SectionCard("Ripple effect", subtitle = "Who tends to move when ${symbol.removeSuffix(".OL")} moves") {
        if (view != null) {
            view.children.filter { it.stat != null }.sortedByDescending { it.stat!!.score }.take(3).forEach { k ->
                Row(Modifier.fillMaxWidth().clickable { vm.open(k.node.symbol) }.padding(vertical = 4.dp)) {
                    Text(k.node.symbol.removeSuffix(".OL"), fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(80.dp))
                    Text(k.stat!!.strength.label, fontSize = 13.sp, color = com.shinobisondre.stockanalyzer.ui.theme.toneColor(k.stat.strength.tone))
                }
            }
        } else Text("Suppliers, customers and rivals — and how closely each one has actually moved with ${symbol.removeSuffix(".OL")}.", fontSize = 13.sp)
        TextButton(onClick = { vm.openRipple(symbol) }) { Text("Open the ripple tree") }
    }
}

@Composable
private fun VerdictCard(core: Core) {
    val a = core.analysis
    val v = a.verdict
    SectionCard("Verdict", subtitle = if (v.probability != null) "Calibrated probability · next ${v.horizonDays} trading days" else "Classic indicator read (model not loaded)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CallBadge(v.call, big = true)
            Spacer(Modifier.width(12.dp))
            Text(v.headline, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(v.description, fontSize = 14.sp, color = Palette.text.copy(alpha = 0.92f))
        if (!core.isUsListing && v.probability != null) {
            Spacer(Modifier.height(6.dp))
            Text("Note: the model is calibrated on US large caps and uses US market context (S&P 500, VIX). For this listing treat the odds as indicative.", color = Palette.warn, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints {
            val stack = maxWidth < 420.dp
            if (stack) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { a.forecasts.forEach { HorizonTile(it, core.currency, Modifier.fillMaxWidth()) } }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { a.forecasts.forEach { HorizonTile(it, core.currency, Modifier.weight(1f)) } }
        }
    }
}

@Composable
private fun HorizonTile(f: com.shinobisondre.stockanalyzer.engine.HorizonForecast, currency: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.surface2).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CallBadge(f.call)
        }
        val prob = f.probability
        if (prob != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(F.prob(prob), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = toneColor(f.call.tone))
                Spacer(Modifier.width(6.dp))
                Text("up · typical ${F.prob(f.baseRate)}", color = Palette.muted, fontSize = 12.sp)
            }
            ProbBar(prob, f.baseRate, f.call.tone, Modifier.padding(vertical = 4.dp))
            Text(F.edge(prob, f.baseRate) + " vs a typical stock", color = Palette.muted, fontSize = 11.sp)
        }
        val bp = f.beatProbability
        if (bp != null) {
            val bb = f.beatBase ?: 0.5
            Text("Beats S&P 500: ${F.prob(bp)} (typical ${F.prob(bb)})", fontSize = 12.sp,
                color = if (bp >= bb + 0.01) Palette.up else if (bp <= bb - 0.01) Palette.down else Palette.muted, modifier = Modifier.padding(top = 4.dp))
        }
        if (f.low != null && f.high != null) Text("80% range ${F.price(f.low, currency)} – ${F.price(f.high, currency)}", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        f.stat?.let { s ->
            if (s.n >= 100) Text("Past ${f.call.text} calls: ${(s.upRate * 100).roundToInt()}% rose (n=${"%,d".format(s.n)}, avg ${F.pct(s.meanRetPct, 1)})", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text("by ${f.targetDate}", color = Palette.muted, fontSize = 11.sp)
    }
}

@Composable
private fun ChartCard(
    core: Core, bars: Int, periodIdx: Int, onPeriod: (Int) -> Unit,
    overlays: Set<Overlay>, onOverlay: (Overlay) -> Unit,
    sel: Int?, onSel: (Int?) -> Unit, panes: String, onPanes: (String) -> Unit,
) {
    val s = core.analysis.series
    SectionCard("Chart") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PERIODS.forEachIndexed { i, (lbl, _) -> SmallChip(lbl, i == periodIdx) { onPeriod(i) } }
            Spacer(Modifier.width(8.dp))
            listOf("RSI", "MACD", "None").forEach { p -> SmallChip(p, panes == p) { onPanes(p) } }
        }
        Spacer(Modifier.height(6.dp))
        PriceChart(s, bars, overlays, core.analysis.levels, sel, onSel)
        when (panes) {
            "RSI" -> RsiPane(s, bars, sel, onSel, Modifier.padding(top = 6.dp))
            "MACD" -> MacdPane(s, bars, sel, onSel, Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Overlay.entries.forEach { o ->
                val c = when (o) { Overlay.SMA20 -> Palette.sma20; Overlay.SMA50 -> Palette.sma50; Overlay.SMA200 -> Palette.sma200; Overlay.BB -> Palette.accent; Overlay.LEVELS -> Palette.warn }
                SmallChip(o.label, o in overlays, dot = c) { onOverlay(o) }
            }
        }
        Text("Tap or drag the chart to inspect a day · long-press to clear", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun SmallChip(label: String, selected: Boolean, dot: Color? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = if (dot != null) { { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)) } } else null,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.accent.copy(alpha = 0.18f), selectedLabelColor = Palette.accent),
    )
}

@Composable
private fun DriversCard(a: Analysis) {
    val f = a.forecast(10) ?: return
    SectionCard("What's driving the odds", subtitle = if (f.probability != null) "Contribution to the 2-week probability vs an average stock-day" else "Strongest classic signals") {
        if (f.drivers.isEmpty()) Text("Nothing stands out — the read is close to average.", color = Palette.muted, fontSize = 13.sp)
        val mx = f.drivers.maxOfOrNull { abs(it.logOdds) } ?: 1.0
        f.drivers.forEach { d ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Row {
                    Text(d.label, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    Text(if (d.logOdds > 0) "helps" else "hurts", color = if (d.logOdds > 0) Palette.up else Palette.down, fontSize = 12.sp)
                }
                DriverBar(d.logOdds, mx, Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun SetupsCard(a: Analysis) {
    SectionCard("Measured setups", subtitle = "Patterns with a 21-year track record") {
        a.rules.setups.forEach { s ->
            Column(Modifier.padding(vertical = 5.dp)) {
                ToneChip(s.title, s.tone)
                Text(s.detail, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Text(s.evidence, color = Palette.muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HoldingCard(h: com.shinobisondre.stockanalyzer.engine.HoldingGuidance) {
    SectionCard("How long to hold", subtitle = "Where the edge has historically been largest") {
        h.points.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.days} sessions", fontWeight = if (p.isBest) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), fontSize = 13.sp)
                Text(p.date, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                Text(F.prob(p.probability), fontWeight = FontWeight.SemiBold, color = if (p.isBest) Palette.accent else Palette.text, fontSize = 13.sp)
            }
        }
        Text(h.note, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SummaryCard(a: Analysis) {
    SectionCard("In plain English") {
        a.summary.bullets.forEach { b ->
            Row(Modifier.padding(vertical = 5.dp)) {
                Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(toneColor(b.tone)))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(b.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(b.text, fontSize = 13.sp, color = Palette.text.copy(alpha = 0.9f))
                }
            }
        }
        if (a.summary.opportunities.isNotEmpty()) {
            Text("Opportunities", color = Palette.up, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            a.summary.opportunities.forEach { Text("• $it", fontSize = 13.sp) }
        }
        if (a.summary.risks.isNotEmpty()) {
            Text("Risks", color = Palette.down, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            a.summary.risks.forEach { Text("• $it", fontSize = 13.sp) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FundamentalsCard(core: Core, extras: Load<Extras>?) {
    SectionCard("Fundamentals", subtitle = "Context only — no measured short-term edge") {
        when (extras) {
            is Load.Ok -> {
                val s = extras.v.summary
                val q = extras.v.quality
                if (s == null) { Text("Fundamentals unavailable (Yahoo session not established or not a company).", color = Palette.muted, fontSize = 13.sp); return@SectionCard }
                val f = s.fundamentals
                if (q != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToneChip("Quality: ${q.tier}", when (q.tier) { "Quality" -> Tone.STRONG_BUY; "Solid" -> Tone.BUY; "Avoid" -> Tone.STRONG_SELL; "Speculative" -> Tone.SELL; else -> Tone.NEUTRAL })
                        Spacer(Modifier.width(8.dp))
                        Text(q.verdict, fontSize = 13.sp, color = Palette.muted)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        q.reasons.forEach { Pill(it, Palette.up) }
                        q.riskFlags.forEach { Pill(it, Palette.down) }
                    }
                }
                KeyValue("Sector", listOfNotNull(f.sector, f.industry).joinToString(" · ").ifEmpty { "—" })
                KeyValue("Market cap", F.big(f.marketCap))
                KeyValue("P/E (trailing / forward)", "${F.num(f.trailingPE, 1)} / ${F.num(f.forwardPE, 1)}")
                KeyValue("PEG · P/B", "${F.num(f.pegRatio)} · ${F.num(f.priceToBook)}")
                KeyValue("Profit margin · ROE", "${F.pct(f.profitMargins?.times(100), 1, false)} · ${F.pct(f.returnOnEquity?.times(100), 1, false)}")
                KeyValue("Revenue · earnings growth", "${F.pct(f.revenueGrowth?.times(100), 1)} · ${F.pct(f.earningsGrowth?.times(100), 1)}")
                KeyValue("Debt/equity · beta", "${F.num(f.debtToEquity, 0)} · ${F.num(f.beta)}")
                KeyValue("Dividend yield", F.pct(f.dividendYield?.times(100), 2, false))
                val up = q?.upsidePct
                KeyValue("Analysts", listOfNotNull(f.recommendationKey?.replace('_', ' '), f.recommendationMean?.let { "mean ${F.num(it, 1)}" }, f.numberOfAnalysts?.let { "$it analysts" }).joinToString(" · ").ifEmpty { "—" })
                KeyValue("Mean target", if (f.targetMeanPrice != null) "${F.price(f.targetMeanPrice, core.currency)} (${F.pct(up, 0)})" else "—", if (up != null) changeColor(up) else Palette.text)
                f.description?.let { d ->
                    var more by remember { mutableStateOf(false) }
                    Text(if (more) d else d.take(220) + if (d.length > 220) "…" else "", fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 8.dp).clickable { more = !more })
                }
            }
            is Load.Err -> Text("Fundamentals unavailable: ${extras.msg}", color = Palette.muted, fontSize = 13.sp)
            else -> Loading()
        }
    }
}

@Composable
private fun CatalystsCard(extras: Load<Extras>?) {
    val c = (extras as? Load.Ok)?.v?.summary?.catalysts
    SectionCard("Upcoming agenda", subtitle = "Earnings, analysts, insiders — context only") {
        if (extras !is Load.Ok) { if (extras is Load.Err) Text("Unavailable", color = Palette.muted) else Loading(); return@SectionCard }
        if (c == null) { Text("No catalyst data for this symbol.", color = Palette.muted, fontSize = 13.sp); return@SectionCard }
        KeyValue("Next earnings", c.nextEarnings?.let { d -> "$d" + (c.daysToEarnings?.let { " (in $it days)" } ?: "") } ?: "—",
            if ((c.daysToEarnings ?: 99) in 0..14) Palette.warn else Palette.text)
        KeyValue("Ex-dividend", c.exDividend ?: "—")
        val tot = (c.recStrongBuy + c.recBuy + c.recHold + c.recSell + c.recStrongSell).coerceAtLeast(1)
        Text("Analyst ratings", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(10.dp).clip(RoundedCornerShape(50))) {
            listOf(c.recStrongBuy to Palette.strongBuy, c.recBuy to Palette.buy, c.recHold to Palette.neutral, c.recSell to Palette.sell, c.recStrongSell to Palette.strongSell)
                .filter { it.first > 0 }.forEach { (n, col) -> Box(Modifier.weight(n.toFloat() / tot).fillMaxHeight().background(col)) }
        }
        Text("${c.recStrongBuy} strong buy · ${c.recBuy} buy · ${c.recHold} hold · ${c.recSell + c.recStrongSell} sell", fontSize = 12.sp, color = Palette.muted)
        KeyValue("Pulse vs last month", (if (c.pulseDelta > 0) "+" else "") + c.pulseDelta, changeColor(c.pulseDelta.toDouble()))
        KeyValue("Upgrades / downgrades (30 d)", "${c.upgrades30d} / ${c.downgrades30d}")
        KeyValue("Insider net buying (6 m)", F.pct(c.insiderNetPct, 1), changeColor(c.insiderNetPct))
        c.recent.forEach { Text(it, fontSize = 12.sp, color = Palette.muted) }
    }
}

@Composable
private fun NewsCard(extras: Load<Extras>?) {
    val uri = LocalUriHandler.current
    SectionCard("News", subtitle = "Sentiment is shown for context — it has no measured predictive edge") {
        val n = (extras as? Load.Ok)?.v?.news
        if (n == null) { if (extras is Load.Err) Text("News unavailable", color = Palette.muted) else Loading(); return@SectionCard }
        n.whyMoving?.let { Text(it, fontSize = 13.sp, color = Palette.warn, modifier = Modifier.padding(bottom = 6.dp)) }
        Text("Headline tone: ${n.overall} (${n.positive} positive · ${n.negative} negative · ${n.neutral} neutral)", color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        if (n.items.isEmpty()) Text("No recent headlines.", color = Palette.muted, fontSize = 13.sp)
        n.items.forEach { item ->
            Row(Modifier.fillMaxWidth().clickable(enabled = item.link.startsWith("http")) { runCatching { uri.openUri(item.link) } }.padding(vertical = 6.dp)) {
                Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(when { item.sentiment > 0 -> Palette.up; item.sentiment < 0 -> Palette.down; else -> Palette.neutral }))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(item.title, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(item.publisher + (item.timeSec?.let { " · " + F.ago(it * 1000) } ?: ""), color = Palette.muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun IndicatorsCard(a: Analysis) {
    val i = a.indicators
    SectionCard("Technical indicators", subtitle = "As of ${i.date}") {
        @Composable fun row(name: String, value: String, read: Read) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Palette.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 8.dp))
                Text(read.label, color = toneColor(read.tone), fontSize = 12.sp, modifier = Modifier.width(132.dp), maxLines = 2)
            }
        }
        row("RSI (14)", F.num(i.rsi, 1), i.rsiRead)
        row("MACD (12,26,9)", F.num(i.macdHist, 3), i.macdRead)
        row("Bollinger %B", F.num(i.percentB?.times(100), 0), i.bbRead)
        row("Moving averages", "", i.maRead)
        KeyValue("SMA 20 / 50 / 200", "${F.num(i.sma20)} / ${F.num(i.sma50)} / ${F.num(i.sma200)}")
        if (i.goldenCross) ToneChip("Golden cross (no measured edge)", Tone.NEUTRAL)
        if (i.deathCross) ToneChip("Death cross (historically contrarian)", Tone.NEUTRAL)
        row("Stochastic %K", F.num(i.stochK, 1), i.stochRead)
        row("Williams %R", F.num(i.williamsR, 1), i.willRRead)
        row("ADX", F.num(i.adx, 1), i.adxRead)
        row("Volume vs 20-day", i.volumeRatio?.let { F.num(it, 2) + "×" } ?: "—", i.volumeRead)
        KeyValue("ATR (14)", "${F.num(i.atr)} (${F.num(i.atrPct, 2)}% a day)")
        KeyValue("On-balance volume", when (i.obvBullish) { true -> "Accumulation"; false -> "Distribution"; null -> "—" })
        KeyValue("12-1 month return", F.pct(i.ret12_1?.times(100), 1), changeColor(i.ret12_1))
    }
}

@Composable
private fun TylerCard(a: Analysis) {
    val t = a.tyler
    SectionCard("Tyler 4-stack", subtitle = "Daily and weekly charts · weak but real tilt historically") {
        ToneChip("${t.verdict.label} · sell ${t.sellHits}/4 · buy ${t.buyHits}/4", t.verdict.tone)
        Text(t.verdict.message, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        @Composable fun checks(title: String, r: StackRead?) {
            if (r == null) return
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            (r.sellChecks.map { it to true } + r.buyChecks.map { it to false }).forEach { (c, sell) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(if (c.hit) "●" else "○", color = if (!c.hit) Palette.muted else if (sell) Palette.down else Palette.up, fontSize = 12.sp, modifier = Modifier.width(18.dp))
                    Column {
                        Text(c.name, fontSize = 12.sp, color = if (c.hit) Palette.text else Palette.muted)
                        if (c.hit) Text(c.detail, fontSize = 11.sp, color = Palette.muted)
                    }
                }
            }
        }
        checks("Daily", t.daily)
        checks("Weekly", t.weekly)
    }
}

@Composable
private fun LevelsCard(a: Analysis, currency: String) {
    SectionCard("Support, resistance & patterns") {
        if (a.levels.isEmpty()) Text("No clear pivot levels in the last 120 sessions.", color = Palette.muted, fontSize = 13.sp)
        a.levels.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (l.type == LevelType.SUPPORT) "Support" else "Resistance", color = if (l.type == LevelType.SUPPORT) Palette.up else Palette.down, fontSize = 13.sp, modifier = Modifier.width(90.dp))
                Text(F.price(l.price, currency), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(F.pct(l.distancePct, 1), color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                Text("×${l.touches}", color = Palette.muted, fontSize = 12.sp)
            }
        }
        if (a.patterns.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Palette.outline)
            a.patterns.forEach { p ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    ToneChip(p.name, p.type)
                    Spacer(Modifier.width(8.dp))
                    Text("${p.description}${if (p.barsAgo > 0) " (${p.barsAgo} d ago)" else ""}", fontSize = 12.sp, color = Palette.muted)
                }
            }
        }
    }
}

@Composable
private fun RulesCard(a: Analysis) {
    val r = a.rules
    SectionCard("Classic indicator score", subtitle = "The web app's weighted rules, bugs fixed · ${r.displayScore}/10") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CallBadge(r.call)
            Spacer(Modifier.width(8.dp))
            Text("raw ${F.num(r.rawScore, 2)}", color = Palette.muted, fontSize = 12.sp)
        }
        r.veto?.let { Text(it, color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(6.dp))
        r.signals.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(s.name, fontSize = 12.sp, modifier = Modifier.weight(1f), color = if (s.scored) Palette.text else Palette.muted)
                Text(if (s.scored) "${F.num(s.score, 1)} × ${F.num(s.weight, 2)}" else "info", fontSize = 12.sp, color = toneColor(com.shinobisondre.stockanalyzer.engine.toneOf(s.score)))
            }
        }
    }
}

@Composable
private fun SymbolTrackCard(a: Analysis, calls: List<LoggedCall>) {
    SectionCard("Track record on this stock", subtitle = "Calls this app logged, graded on trading days") {
        if (calls.isEmpty()) { Text("No logged calls yet. Each day you open a stock its call is logged and graded 5, 10 and 22 sessions later.", color = Palette.muted, fontSize = 13.sp); return@SectionCard }
        val graded = calls.sortedByDescending { it.date }.take(12).map { TrackRecord.grade(it, a.series) }
        Row { listOf("Date", "Call", "P(2w)", "5 d", "10 d", "22 d").forEachIndexed { i, h -> Text(h, color = Palette.muted, fontSize = 11.sp, modifier = Modifier.weight(if (i <= 1) 1.3f else 1f)) } }
        graded.forEach { g ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(g.logged.date.substring(5), fontSize = 12.sp, modifier = Modifier.weight(1.3f))
                Text(g.logged.call, fontSize = 11.sp, color = toneColor(Call.of(g.logged.call)?.tone ?: Tone.NEUTRAL), modifier = Modifier.weight(1.3f), maxLines = 1)
                Text(F.prob(g.logged.p10), fontSize = 12.sp, modifier = Modifier.weight(1f))
                listOf(g.ret5, g.ret10, g.ret22).forEach { r ->
                    Text(r?.let { F.pct(it * 100, 1) } ?: "…", fontSize = 12.sp, color = changeColor(r), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
