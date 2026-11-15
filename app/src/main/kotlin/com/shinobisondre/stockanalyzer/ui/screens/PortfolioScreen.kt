package com.shinobisondre.stockanalyzer.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.data.Holding
import com.shinobisondre.stockanalyzer.data.HoldingView
import com.shinobisondre.stockanalyzer.data.ImportCandidate
import com.shinobisondre.stockanalyzer.ui.ShotImport
import com.shinobisondre.stockanalyzer.update.Updater
import com.shinobisondre.stockanalyzer.data.PortfolioView
import com.shinobisondre.stockanalyzer.engine.Tone
import com.shinobisondre.stockanalyzer.ui.CallBadge
import com.shinobisondre.stockanalyzer.ui.EmptyState
import com.shinobisondre.stockanalyzer.ui.ErrorBox
import com.shinobisondre.stockanalyzer.ui.F
import com.shinobisondre.stockanalyzer.ui.KeyValue
import com.shinobisondre.stockanalyzer.ui.Load
import com.shinobisondre.stockanalyzer.ui.Loading
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.SectionCard
import com.shinobisondre.stockanalyzer.ui.ToneChip
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import com.shinobisondre.stockanalyzer.ui.theme.changeColor

@Composable
fun PortfolioScreen(vm: MainViewModel, twoPane: Boolean) {
    val st by vm.state.collectAsStateWithLifecycle()
    val pf by vm.portfolio.collectAsStateWithLifecycle()
    val horizon by vm.horizon.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Holding?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val shot by vm.shot.collectAsStateWithLifecycle()
    LaunchedEffect(st.holdings.size) { vm.loadPortfolio() }
    // System photo picker: pick one or more Nordnet screenshots (no storage permission needed).
    val pickShots = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        Updater.holdInstall = false
        vm.importScreenshots(uris)
    }
    val pick = {
        Updater.holdInstall = true
        pickShots.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader("Portfolio", "Values in ${st.settings.baseCurrency} · ${st.holdings.size} holdings") {
                IconButton(onClick = { importing = true }) { Icon(Icons.Filled.Edit, "Import holdings") }
                IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add holding") }
                IconButton(onClick = { vm.loadPortfolio(true) }) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = pick, modifier = Modifier.weight(1f)) { Text("Import Nordnet screenshot") }
                OutlinedButton(onClick = { importing = true }) { Text("Paste") }
            }
            Text("Holdings tab in the Nordnet app → screenshot → import (or share the screenshot straight to StockAnalyzer). Scroll and take more screenshots for long lists — pick them all at once.",
                color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (st.holdings.isEmpty()) {
            item {
                EmptyState("No holdings yet", "Import a screenshot of your Nordnet holdings, add positions one by one, or paste them (e.g. \"GOOGL 10, NVDA 21, MU 13 @ 984\"). Every position is valued in ${st.settings.baseCurrency} and gets its own call.")
            }
        } else {
            item {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5 to "1 week", 10 to "2 weeks", 22 to "1 month").forEach { (d, l) -> SmallChip(l, horizon == d) { vm.setHorizon(d) } }
                }
            }
            when (val p = pf) {
                is Load.Ok -> {
                    item { TotalsCard(p.v, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                    items(p.v.rows, key = { "h-" + it.holding.symbol }) { r ->
                        HoldingRow(r, p.v.base, onOpen = { vm.open(r.holding.symbol) }, onEdit = { editing = r.holding }, onRemove = { vm.removeHolding(r.holding.symbol) })
                        HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.outline.copy(alpha = 0.5f))
                    }
                    if (p.v.totals.sectors.size > 1) item { SectorsCard(p.v, Modifier.padding(12.dp)) }
                }
                is Load.Err -> item { ErrorBox(p.msg) { vm.loadPortfolio(true) } }
                else -> item { Loading(text = "Valuing ${st.holdings.size} holdings…") }
            }
        }
    }

    if (adding) HoldingDialog(null, null, null, onDismiss = { adding = false }) { s, sh, c, rep -> vm.upsertHolding(s, sh, c, rep); adding = false }
    editing?.let { h -> HoldingDialog(h.symbol, h, null, onDismiss = { editing = null }) { s, sh, c, rep -> vm.upsertHolding(s, sh, c, rep); editing = null } }
    if (importing) ImportDialog(onDismiss = { importing = false }) { text, replace -> vm.say(vm.importHoldings(text, replace)); importing = false }
    shot?.let { ScreenshotReview(vm, it, onPickAgain = { vm.dismissShot(); pick() }) }
}

/** Review what was read off the screenshot: fix tickers/shares, untick rows, then import. */
@Composable
private fun ScreenshotReview(vm: MainViewModel, s: ShotImport, onPickAgain: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!s.busy) vm.dismissShot() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 720.dp),
        title = { Text(if (s.busy) "Importing" else if (s.error != null) "Couldn't import" else "Found ${s.candidates.size} holdings") },
        text = {
            when {
                s.busy -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(color = Palette.accent)
                    Spacer(Modifier.height(10.dp))
                    Text(s.stage, color = Palette.muted, fontSize = 14.sp)
                }
                s.error != null -> Text(s.error, fontSize = 14.sp)
                else -> Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Check the tickers and share counts. Nordnet's list shows value and price, not the number of shares, so shares are worked out from value ÷ price at today's exchange rate.",
                        color = Palette.muted, fontSize = 12.sp)
                    s.candidates.forEachIndexed { i, c -> CandidateRow(c, onChange = { fn -> vm.editCandidate(i, fn) }) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = s.replaceAll, onCheckedChange = { vm.setShotReplaceAll(it) })
                        Text("Replace my whole portfolio with these", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (!s.busy && s.error == null) {
                val n = s.candidates.count { it.selected }
                TextButton(enabled = n > 0, onClick = { vm.confirmShot() }) { Text("Import $n") }
            } else if (s.error != null) TextButton(onClick = onPickAgain) { Text("Pick screenshots") }
        },
        dismissButton = { if (!s.busy) TextButton(onClick = { vm.dismissShot() }) { Text("Cancel") } },
    )
}

@Composable
private fun CandidateRow(c: ImportCandidate, onChange: ((ImportCandidate) -> ImportCandidate) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surface2).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = c.selected, onCheckedChange = { v -> onChange { it.copy(selected = v) } })
            Column(Modifier.weight(1f)) {
                Text(c.parsed.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(
                    c.parsed.marketValue?.let { "Value ${F.num(it, 0)} ${c.parsed.valueCurrency ?: ""}".trim() },
                    c.parsed.lastPrice?.let { "Latest ${F.num(it)} ${c.parsed.lastCurrency ?: ""}".trim() },
                    c.parsed.returnPct?.let { "Return ${F.pct(it)}" },
                ).joinToString(" · "), color = Palette.muted, fontSize = 11.sp, maxLines = 2)
                c.resolvedName?.let { Text("→ $it", color = Palette.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(c.symbol, { v -> onChange { it.copy(symbol = v.uppercase().trim(), selected = true) } }, label = { Text("Ticker") }, singleLine = true,
                modifier = Modifier.weight(1.1f), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            OutlinedTextField(c.sharesText, { v -> onChange { it.copy(sharesText = v) } }, label = { Text("Shares") }, singleLine = true,
                modifier = Modifier.weight(0.9f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(c.costText, { v -> onChange { it.copy(costText = v) } }, label = { Text("Avg cost") }, singleLine = true,
                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        if (c.note.isNotBlank()) Text(c.note, color = if (c.symbol.isBlank() || c.sharesText.isBlank()) Palette.warn else Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TotalsCard(v: PortfolioView, modifier: Modifier) {
    val t = v.totals
    SectionCard("Total value", modifier) {
        Text(F.money(t.valueBase, v.base), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Today ${F.money(t.dayChangeBase, v.base)} (${F.pct(t.dayChangePct)})", color = changeColor(t.dayChangeBase), fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        if (t.costedCount > 0) {
            KeyValue("Unrealised P/L", "${F.money(t.pnlBase, v.base)} (${F.pct(t.pnlPct)})", changeColor(t.pnlBase))
            Text("P/L covers the ${t.costedCount} of ${v.rows.size} holdings with an average cost entered (worth ${F.money(t.costedValueBase, v.base)}).", color = Palette.muted, fontSize = 12.sp)
        } else Text("Add an average cost to a holding to see P/L.", color = Palette.muted, fontSize = 12.sp)
        if (t.unpriced.isNotEmpty()) Text("Not valued (no price or FX rate): ${t.unpriced.joinToString(", ")}", color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToneChip(v.stance, when (v.stance) { "Constructive" -> Tone.BUY; "Defensive" -> Tone.SELL; "Concentrated" -> Tone.SELL; else -> Tone.NEUTRAL })
            Spacer(Modifier.width(8.dp))
            Text(v.stanceNote, fontSize = 12.sp, color = Palette.muted)
        }
        Text("Updated ${F.ago(v.at)}", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun HoldingRow(r: HoldingView, base: String, onOpen: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val h = r.holding; val v = r.value; val c = r.core
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.2f)) {
            Text(h.symbol, fontWeight = FontWeight.Bold)
            Text("${F.num(h.shares, if (h.shares % 1.0 == 0.0) 0 else 2)} × ${F.price(c?.price, c?.currency)}", color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            r.error?.let { Text(it, color = Palette.warn, fontSize = 11.sp, maxLines = 2) }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(F.money(v.valueBase, base), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(listOfNotNull(v.weightPct?.let { F.num(it, 1) + "%" }, v.pnlPct?.let { "P/L " + F.pct(it, 1) }).joinToString(" · "), color = changeColor(v.pnlPct), fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(84.dp), horizontalAlignment = Alignment.End) {
            r.forecast?.let { f ->
                CallBadge(f.call)
                Text(f.probability?.let { F.prob(it) } ?: "rules", color = Palette.muted, fontSize = 11.sp)
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "More", tint = Palette.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Remove", color = Palette.strongSell) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun SectorsCard(v: PortfolioView, modifier: Modifier) {
    SectionCard("Sector mix", modifier) {
        v.totals.sectors.forEach { s ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Row {
                    Text(s.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(F.num(s.pct, 1) + "%", fontSize = 13.sp, color = if (s.pct > 40 && s.name != "Unknown") Palette.warn else Palette.text)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Palette.surface2)) {
                    Box(Modifier.fillMaxWidth((s.pct / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(Palette.accent))
                }
            }
        }
        if (v.totals.concentrated.isNotEmpty()) Text("Over 40% in one sector — a single theme drives most of your risk.", color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun parseNum(s: String): Double? = s.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()

@Composable
fun HoldingDialog(symbol: String?, existing: Holding?, price: Double?, onDismiss: () -> Unit, onSave: (String, Double, Double?, Boolean) -> Unit) {
    var sym by remember { mutableStateOf(symbol ?: "") }
    var shares by remember { mutableStateOf(existing?.shares?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "") }
    var cost by remember { mutableStateOf(existing?.avgCost?.toString() ?: "") }
    var replace by remember { mutableStateOf(existing != null) }
    val sharesV = parseNum(shares)
    val costV = if (cost.isBlank()) null else parseNum(cost)
    val ok = sym.isNotBlank() && sharesV != null && sharesV > 0 && (cost.isBlank() || (costV != null && costV >= 0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Edit ${existing.symbol}" else "Add holding") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (symbol == null) OutlinedTextField(sym, { sym = it.uppercase().trim() }, label = { Text("Ticker (e.g. AAPL, EQNR.OL)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                OutlinedTextField(shares, { shares = it }, label = { Text("Shares") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(cost, { cost = it }, label = { Text("Average cost per share (optional, quote currency)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (price != null && existing == null) Text("Current price ${F.num(price)}", color = Palette.muted, fontSize = 12.sp)
                if (existing != null && symbol != null && existing.symbol == symbol) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = replace, onCheckedChange = { replace = it })
                    Text(if (replace) "Replace the existing position" else "Add to the existing position (averages the cost)", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onSave(sym.trim().uppercase(), sharesV!!, costV, replace) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste holdings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One per line or comma-separated: TICKER SHARES [@ AVG COST]. Decimal commas are fine.", color = Palette.muted, fontSize = 13.sp)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().height(160.dp), placeholder = { Text("GOOGL 10\nNVDA 21\nMU 13 @ 984\nKMAR.OL 500") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = replace, onCheckedChange = { replace = it })
                    Text("Replace my whole portfolio", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onImport(text, replace) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
