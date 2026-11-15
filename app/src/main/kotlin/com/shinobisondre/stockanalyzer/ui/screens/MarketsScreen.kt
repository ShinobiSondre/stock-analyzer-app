package com.shinobisondre.stockanalyzer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.data.Core
import com.shinobisondre.stockanalyzer.data.MarketData
import com.shinobisondre.stockanalyzer.engine.FearTier
import com.shinobisondre.stockanalyzer.engine.Tone
import com.shinobisondre.stockanalyzer.ui.CallBadge
import com.shinobisondre.stockanalyzer.ui.ChangeText
import com.shinobisondre.stockanalyzer.ui.ErrorBox
import com.shinobisondre.stockanalyzer.ui.F
import com.shinobisondre.stockanalyzer.ui.KeyValue
import com.shinobisondre.stockanalyzer.ui.Load
import com.shinobisondre.stockanalyzer.ui.Loading
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.SectionCard
import com.shinobisondre.stockanalyzer.ui.Sparkline
import com.shinobisondre.stockanalyzer.ui.ToneChip
import com.shinobisondre.stockanalyzer.ui.regimeTone
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import com.shinobisondre.stockanalyzer.ui.theme.changeColor

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = Palette.muted, fontSize = 13.sp)
        }
        actions()
    }
}

@Composable
fun MarketsScreen(vm: MainViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    val market by vm.market.collectAsStateWithLifecycle()
    val cores by vm.cores.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val up by vm.update.collectAsStateWithLifecycle()
    val splash by vm.splash.collectAsStateWithLifecycle()
    val movers = remember(cores, st.holdings, st.watchlist) { vm.moversFromCores(cores) }
    // Home page: look for an app update (throttled) and pop up today's quick win once.
    LaunchedEffect(Unit) { vm.autoUpdate() }
    LaunchedEffect(movers.map { it.symbol }) { vm.maybeSplash(movers) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader("Markets", "Odds-based analysis for your watchlist") {
                IconButton(onClick = { vm.refreshAll() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                val unread = st.alerts.count { !it.read }
                IconButton(onClick = { vm.tab.value = com.shinobisondre.stockanalyzer.ui.Tab.ALERTS }) {
                    if (unread > 0) androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                        Icon(Icons.Filled.Notifications, "Alerts")
                    } else Icon(Icons.Filled.Notifications, "Alerts")
                }
                IconButton(onClick = { vm.tab.value = com.shinobisondre.stockanalyzer.ui.Tab.SETTINGS }) { Icon(Icons.Filled.Settings, "Settings") }
            }
        }
        if (up.available != null) item { UpdateBanner(vm, up, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        if (movers.isNotEmpty()) item {
            QuickWinBanner(movers, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { vm.tab.value = com.shinobisondre.stockanalyzer.ui.Tab.QUICK }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.search(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("Search ticker or company (AAPL, Equinor, EQNR.OL)…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.search("") }) { Icon(Icons.Filled.Clear, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val q = query.trim()
                    if (q.isNotEmpty()) { vm.open(hits.firstOrNull()?.symbol ?: q); vm.search(""); focus.clearFocus() }
                }),
            )
        }
        if (query.isNotBlank()) {
            items(hits, key = { "hit-" + it.symbol }) { h ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.open(h.symbol); vm.search(""); focus.clearFocus() }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(h.symbol, fontWeight = FontWeight.Bold)
                        Text("${h.name} · ${h.type.lowercase()} · ${h.exchange}", color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val inList = h.symbol in st.watchlist
                    TextButton(onClick = { vm.toggleWatch(h.symbol) }) { Text(if (inList) "Watching" else "+ Watch") }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Palette.outline) }
        }
        item { MarketCard(market, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { vm.refreshMarket(true) } }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Watchlist", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("odds ↑ in 2 wks", color = Palette.muted, fontSize = 11.sp)
                IconButton(onClick = { addOpen = true }) { Icon(Icons.Filled.Add, "Add symbols") }
            }
        }
        if (st.watchlist.isEmpty()) item {
            Text("Your watchlist is empty. Search above or tap + to add tickers.", color = Palette.muted, modifier = Modifier.padding(16.dp))
        }
        items(st.watchlist, key = { "w-$it" }) { sym ->
            WatchRow(sym, cores[sym], onClick = { vm.open(sym) },
                onRemove = { vm.toggleWatch(sym) }, onUp = { vm.moveWatch(sym, true) }, onDown = { vm.moveWatch(sym, false) }, onRetry = { vm.load(sym, true) })
            HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.outline.copy(alpha = 0.5f))
        }
        item {
            Text(
                "Probabilities come from a model validated out of sample on 20 years of data. A 60% call is still wrong 40% of the time. Not investment advice.",
                color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp),
            )
        }
    }

    splash?.let { list ->
        AlertDialog(
            onDismissRequest = { vm.dismissSplash() },
            icon = { Icon(BoltIcon, null, tint = Palette.warn) },
            title = { Text(if (list.size == 1) "${list[0].symbol} is up ${F.pct(list[0].changePct, 1).removePrefix("+")} today" else "Quick wins today") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { w ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(w.symbol, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                ChangeText(w.changePct, size = 14)
                            }
                            ToneChip(w.advice?.lean?.title ?: "No history for this size of move", leanTone(w.advice?.lean), Modifier.padding(top = 4.dp))
                            w.advice?.lines?.firstOrNull()?.let { Text(it, fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 4.dp)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.dismissSplash(); vm.tab.value = com.shinobisondre.stockanalyzer.ui.Tab.QUICK }) { Text("See quick wins") } },
            dismissButton = { TextButton(onClick = { vm.dismissSplash() }) { Text("Later") } },
        )
    }

    if (addOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("Add to watchlist") },
            text = {
                Column {
                    Text("Tickers separated by spaces or commas. Oslo Børs: add .OL (e.g. EQNR.OL).", color = Palette.muted, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("TSLA, MSFT, EQNR.OL") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                }
            },
            confirmButton = { TextButton(onClick = { vm.addSymbolsToWatchlist(text); addOpen = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun UpdateBanner(vm: MainViewModel, up: com.shinobisondre.stockanalyzer.ui.UpdateState, modifier: Modifier = Modifier) {
    val rel = up.available ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SectionCard("Update ${rel.versionName}", modifier) {
        when {
            up.installing -> Text("Installing — the app restarts in a moment.", fontSize = 13.sp)
            up.progress != null -> {
                Text("Downloading… ${((up.progress ?: 0f) * 100).toInt()}%", fontSize = 13.sp)
                androidx.compose.material3.LinearProgressIndicator(progress = { up.progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Palette.accent)
            }
            !com.shinobisondre.stockanalyzer.update.Updater.canInstall(ctx) -> {
                Text("Allow StockAnalyzer to install its own updates once — after that new versions install automatically.", fontSize = 13.sp)
                TextButton(onClick = { vm.installUpdate() }) { Text("Allow") }
            }
            up.readyPath != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Downloaded. It installs by itself when you leave the app.", fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.installUpdate() }) { Text("Install now") }
            }
            else -> TextButton(onClick = { vm.installUpdate() }) { Text("Download & install") }
        }
        up.error?.let { Text(it, color = Palette.strongSell, fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketCard(market: com.shinobisondre.stockanalyzer.ui.Load<MarketData>, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    SectionCard("Market mood", modifier) {
        when (market) {
            is Load.Ok -> {
                val m = market.v
                val p = m.point
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (p != null) ToneChip("Regime: ${p.regime.label}", regimeTone(p.regime.name))
                    if (p != null) ToneChip(
                        when (p.fearTier) { FearTier.HIGH -> "Fear (VIX > 25)"; FearTier.ELEVATED -> "Elevated VIX"; FearTier.PULLBACK -> "Index pullback"; FearTier.NONE -> "Calm" },
                        when (p.fearTier) { FearTier.HIGH -> Tone.STRONG_BUY; FearTier.ELEVATED, FearTier.PULLBACK -> Tone.BUY; FearTier.NONE -> Tone.NEUTRAL },
                    )
                    if (p?.indexDeathCross == true) ToneChip("S&P death cross", Tone.SELL)
                    if (m.fearGreed != null) ToneChip("Fear & Greed ${m.fearGreed}" + (m.fearGreedLabel?.let { " · $it" } ?: ""),
                        when { m.fearGreed < 25 -> Tone.STRONG_BUY; m.fearGreed < 45 -> Tone.BUY; m.fearGreed > 75 -> Tone.SELL; else -> Tone.NEUTRAL })
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("S&P 500 (SPY)", color = Palette.muted, fontSize = 12.sp)
                        Text(F.price(m.spy.price, "USD"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        ChangeText(m.spy.changePct, size = 13)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("VIX", color = Palette.muted, fontSize = 12.sp)
                        Text(F.num(m.vix?.price ?: p?.vix, 2), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        m.vix?.changePct?.let { Text(F.pct(it), color = changeColor(-it), fontSize = 13.sp) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("From 1-yr high", color = Palette.muted, fontSize = 12.sp)
                        Text(p?.spyDrawdownPct?.let { "−" + F.num(it, 1) + "%" } ?: "—", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("S&P 500", color = Palette.muted, fontSize = 12.sp)
                    }
                }
                if (p != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(p.regimeNote(), color = Palette.muted, fontSize = 13.sp)
                    if (p.fearTier == FearTier.HIGH && !p.indexDeathCross)
                        Text("Elevated fear with the index trend intact has historically been the best time to buy quality names that pulled back.", color = Palette.strongBuy, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            is Load.Err -> ErrorBox(market.msg, onRetry = onRetry)
            else -> Loading()
        }
    }
}

@Composable
private fun WatchRow(sym: String, load: Load<Core>?, onClick: () -> Unit, onRemove: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRetry: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val core = (load as? Load.Ok)?.v
        Column(Modifier.weight(1.3f)) {
            Text(sym, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(core?.name ?: (if (load is Load.Err) "Couldn't load — tap ⋮ to retry" else "…"), color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (core != null) {
            Sparkline(core.analysis.series.close.takeLast(60), Modifier.width(56.dp).height(26.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(F.price(core.price, core.currency), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                ChangeText(core.changePct, size = 12)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.width(84.dp), horizontalAlignment = Alignment.End) {
                CallBadge(core.analysis.verdict.call)
                Text(core.p10?.let { F.prob(it) } ?: "rules", color = Palette.muted, fontSize = 11.sp)
            }
        } else if (load is Load.Loading || load == null) {
            Text("loading…", color = Palette.muted, fontSize = 12.sp)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "More", tint = Palette.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; onUp() })
                DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; onDown() })
                DropdownMenuItem(text = { Text("Reload") }, onClick = { menu = false; onRetry() })
                DropdownMenuItem(text = { Text("Remove", color = Palette.strongSell) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

