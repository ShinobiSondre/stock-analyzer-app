package com.shinobisondre.stockanalyzer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.data.AlertItem
import com.shinobisondre.stockanalyzer.data.TrackView
import com.shinobisondre.stockanalyzer.engine.Call
import com.shinobisondre.stockanalyzer.engine.ModelBundle
import com.shinobisondre.stockanalyzer.engine.Tone
import com.shinobisondre.stockanalyzer.ui.CalibrationChart
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
import com.shinobisondre.stockanalyzer.ui.theme.toneColor
import kotlin.math.roundToInt

@Composable
fun AlertsScreen(vm: MainViewModel) {
    var section by rememberSaveable { mutableStateOf("Alerts") }
    val st by vm.state.collectAsStateWithLifecycle()
    val track by vm.track.collectAsStateWithLifecycle()
    LaunchedEffect(section) {
        if (section == "Alerts") vm.markAlertsRead()
        if (section == "Track record") vm.loadTrack()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader("Alerts & accuracy") {}
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Alerts", "Track record", "Model").forEach { s -> SmallChip(s, section == s) { section = s } }
            }
        }
        when (section) {
            "Alerts" -> {
                item {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.runWatcherNow() }) { Text("Check now") }
                        TextButton(onClick = { vm.clearAlerts() }, enabled = st.alerts.isNotEmpty()) { Text("Clear all") }
                        Spacer(Modifier.weight(1f))
                        Text(if (st.settings.alertsEnabled) "every ${st.settings.alertIntervalMin} min" else "background checks off", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
                    }
                }
                if (st.alerts.isEmpty()) item {
                    EmptyState("No alerts yet", "The watcher checks your portfolio and watchlist in the background: call changes (judged on completed daily bars, so no intraday flip-flopping), big moves, Tyler 3/4-stacks, new measured setups and market regime changes.")
                }
                items(st.alerts, key = { it.id }) { a ->
                    AlertRow(a) { if (a.symbol != "MARKET") vm.open(a.symbol) }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.outline.copy(alpha = 0.5f))
                }
            }
            "Track record" -> item {
                when (val t = track) {
                    is Load.Ok -> TrackSection(t.v, vm)
                    is Load.Err -> ErrorBox(t.msg) { vm.loadTrack() }
                    else -> Loading(text = "Grading logged calls against what happened…")
                }
            }
            else -> item { ModelSection(vm.repo.model) }
        }
    }
}

@Composable
private fun AlertRow(a: AlertItem, onClick: () -> Unit) {
    val color = when (a.type) { "call" -> Palette.accent; "move" -> Palette.warn; "stack" -> Palette.sma200; "setup" -> Palette.strongBuy; "regime" -> Palette.sma50; else -> Palette.muted }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, fontWeight = if (a.read) FontWeight.Normal else FontWeight.Bold, fontSize = 14.sp)
            if (a.message.isNotBlank()) Text(a.message, color = Palette.muted, fontSize = 12.sp, maxLines = 4)
            Text(F.ago(a.time), color = Palette.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TrackSection(t: TrackView, vm: MainViewModel) {
    val s = t.summary
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard("This app's live record", subtitle = "Every call it showed you, graded 10 trading days later") {
            KeyValue("Calls logged", "${s.logged} on ${t.symbolCount} symbols")
            KeyValue("Matured (10 sessions passed)", s.matured10.toString())
            if (s.matured10 == 0) {
                Text("Nothing has matured yet. Calls are logged once per symbol per trading day whenever you open a stock, scan, or the watcher runs; they're graded after 5, 10 and 22 sessions.", color = Palette.muted, fontSize = 13.sp)
            } else {
                KeyValue("All calls: higher after 10 d", "${(s.all10.upRate * 100).roundToInt()}% (avg ${F.pct(s.all10.meanRetPct, 2)})")
                val br = s.brier10; val bb = s.brierBase10
                if (br != null && bb != null) {
                    KeyValue("Brier score (model vs base rate)", "${F.num(br, 4)} vs ${F.num(bb, 4)}", if (br < bb) Palette.up else Palette.down)
                }
                Spacer(Modifier.padding(4.dp))
                Call.entries.forEach { c ->
                    val b = s.byCall[c.text] ?: return@forEach
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToneChip(c.text, c.tone, Modifier.width(110.dp))
                        Text("n=${b.n}", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Text("${(b.upRate * 100).roundToInt()}% up", fontSize = 13.sp, modifier = Modifier.padding(end = 10.dp))
                        Text(F.pct(b.meanRetPct, 2), color = changeColor(b.meanRetPct), fontSize = 13.sp)
                    }
                }
                Text("Small samples are noisy: 20 calls at a true 60% hit rate will land anywhere from 40% to 80%.", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (t.recent.isNotEmpty()) SectionCard("Recent calls") {
            t.recent.take(40).forEach { g ->
                Row(Modifier.fillMaxWidth().clickable { vm.open(g.logged.symbol) }.padding(vertical = 3.dp)) {
                    Text(g.logged.date.substring(5), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(g.logged.symbol, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.2f))
                    Text(g.logged.call, fontSize = 11.sp, color = toneColor(Call.of(g.logged.call)?.tone ?: Tone.NEUTRAL), modifier = Modifier.weight(1.4f), maxLines = 1)
                    Text(F.prob(g.logged.p10), fontSize = 12.sp, modifier = Modifier.weight(0.8f))
                    Text(g.ret10?.let { F.pct(it * 100, 1) } ?: "pending", fontSize = 12.sp, color = changeColor(g.ret10), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ModelSection(model: ModelBundle?) {
    val uri = LocalUriHandler.current
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (model == null) {
            SectionCard("No model loaded") { Text("The app is running on the classic rules engine only.", color = Palette.muted) }
            return@Column
        }
        SectionCard("Probability model", subtitle = "Version ${model.version}") {
            KeyValue("Trained", model.trainedAt.take(10))
            KeyValue("Data", "${model.dataFrom} → ${model.dataTo}")
            KeyValue("Fitted on / held out", "${model.trainTickers} / ${model.holdoutTickers} US tickers")
            Text(model.notes, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = { runCatching { uri.openUri("https://github.com/ShinobiSondre/stock-analyzer-app/blob/main/docs/MODEL_REPORT.md") } }) { Text("Full validation report") }
        }
        model.horizons.forEach { h ->
            SectionCard("${h.days} trading days", subtitle = "Predictor: ${h.kind} · base rate ${F.prob(h.baseRate)}") {
                val m = h.metrics
                KeyValue("AUC (out of sample, 0.5 = coin flip)", F.num(m["auc"], 3))
                KeyValue("AUC on never-seen tickers", F.num(m["aucHoldout"], 3))
                KeyValue("Brier skill vs base rate", F.pct(m["skill"]?.times(100), 2), changeColor(m["skill"]))
                KeyValue("80% price band coverage", F.pct(m["intervalCoverage"]?.times(100), 1, false))
                Spacer(Modifier.padding(4.dp))
                Text("What past calls did (out of sample)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                h.stats.forEach { (k, st) ->
                    val c = Call.of(k)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToneChip(c?.text ?: k, c?.tone ?: Tone.NEUTRAL, Modifier.width(110.dp))
                        Text("n=${"%,d".format(st.n)}", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Text("${(st.upRate * 100).roundToInt()}% up", fontSize = 13.sp, modifier = Modifier.padding(end = 10.dp))
                        Text(F.pct(st.meanRetPct, 2), color = changeColor(st.meanRetPct), fontSize = 13.sp)
                    }
                }
                if (h.calibration.isNotEmpty()) {
                    Text("Calibration: dots on the dashed line = probabilities mean what they say", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    CalibrationChart(h.calibration)
                }
            }
        }
        SectionCard("Honest limits") {
            listOf(
                "Short-horizon direction of single stocks is close to unpredictable. The edge here is a few percentage points on a minority of days — real, but small.",
                "The base rate itself moves: in 2008 and 2022 fewer than half of stocks rose over two weeks. A BUY in a bear market means \"better than the average stock\".",
                "Tickers were chosen in 2026, so failed companies are under-represented (survivorship). Absolute odds may be slightly optimistic.",
                "News, analyst ratings and catalysts are shown as context only — they have no point-in-time history to validate against.",
            ).forEach { Text("• $it", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}
