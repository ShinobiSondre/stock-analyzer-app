package com.shinobisondre.stockanalyzer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.data.Core
import com.shinobisondre.stockanalyzer.engine.SetupKind
import com.shinobisondre.stockanalyzer.engine.Tone
import com.shinobisondre.stockanalyzer.ui.CallBadge
import com.shinobisondre.stockanalyzer.ui.ChangeText
import com.shinobisondre.stockanalyzer.ui.EmptyState
import com.shinobisondre.stockanalyzer.ui.F
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.ProbBar
import com.shinobisondre.stockanalyzer.ui.ToneChip
import com.shinobisondre.stockanalyzer.ui.Universe
import com.shinobisondre.stockanalyzer.ui.theme.Palette

@Composable
fun ScannerScreen(vm: MainViewModel) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val shown = if (scan.bullishOnly) scan.results.filter { it.analysis.verdict.call.bullish } else scan.results

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenHeader("Scanner", "Rank a list by the model's 2-week odds") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Universe.entries.forEach { u -> SmallChip(u.label, scan.universe == u) { vm.setUniverse(u) } }
            }
            Text(scan.universe.note, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.runScan() }, enabled = !scan.running) { Text(if (scan.running) "Scanning…" else "Run scan") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.setBullishOnly(!scan.bullishOnly) }) { Text(if (scan.bullishOnly) "Showing bullish only" else "Show bullish only") }
            }
            if (scan.running) {
                LinearProgressIndicator(progress = { if (scan.total > 0) scan.done.toFloat() / scan.total else 0f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Palette.accent)
                Text("${scan.done} / ${scan.total}", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (scan.at > 0 && !scan.running) {
                val bull = scan.results.count { it.analysis.verdict.call.bullish }
                Text("${scan.results.size} analysed ${F.ago(scan.at)} · $bull bullish" + if (scan.failed.isNotEmpty()) " · failed: ${scan.failed.joinToString(", ")}" else "",
                    color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        if (scan.results.isEmpty() && !scan.running) item {
            EmptyState("Nothing scanned yet", "Pick a list and run the scan. Each stock gets the same full analysis as the detail screen, sorted by its probability of being higher in two weeks.")
        }
        items(shown, key = { "scan-" + it.symbol }) { c ->
            ScanRow(c) { vm.open(c.symbol) }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.outline.copy(alpha = 0.5f))
        }
        if (shown.isNotEmpty()) item {
            Text("Bullish calls are rare by design (top ~20% of odds, never closer than 1–4 points to the base rate). Most days most stocks have no edge — HOLD is the honest answer.",
                color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanRow(c: Core, onClick: () -> Unit) {
    val a = c.analysis
    val f = a.forecast(10)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.symbol, fontWeight = FontWeight.Bold)
                Text(c.name, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(F.price(c.price, c.currency), fontSize = 13.sp)
                ChangeText(c.changePct, size = 12)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(92.dp)) {
                CallBadge(a.verdict.call)
                Text(f?.probability?.let { "${F.prob(it)} · ${F.edge(it, f?.baseRate)}" } ?: "rules ${F.num(a.rules.rawScore, 2)}", fontSize = 11.sp, color = Palette.muted, maxLines = 1)
            }
        }
        val p = f?.probability
        if (p != null) ProbBar(p, f?.baseRate, a.verdict.call.tone, Modifier.padding(top = 6.dp), height = 5.dp)
        val chips = a.rules.setups.filter { it.kind != SetupKind.FEAR_SUPPRESSED }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { chips.forEach { ToneChip(it.title, it.tone) } }
        }
        if (!c.isUsListing) Text("US-calibrated model — indicative for this listing", color = Palette.muted, fontSize = 10.sp)
        if (a.tyler.verdict.tone != Tone.NEUTRAL) Text("Tyler: ${a.tyler.verdict.label}", color = Palette.muted, fontSize = 11.sp)
    }
}
