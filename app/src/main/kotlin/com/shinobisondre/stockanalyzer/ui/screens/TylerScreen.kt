package com.shinobisondre.stockanalyzer.ui.screens

import com.shinobisondre.stockanalyzer.ui.AdaptiveGrid
import com.shinobisondre.stockanalyzer.ui.cell
import com.shinobisondre.stockanalyzer.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
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
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.engine.TylerCall
import com.shinobisondre.stockanalyzer.engine.TylerTake
import com.shinobisondre.stockanalyzer.engine.TylerTalk
import com.shinobisondre.stockanalyzer.ui.EmptyState
import com.shinobisondre.stockanalyzer.ui.ErrorBox
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.Pill
import com.shinobisondre.stockanalyzer.ui.SectionCard
import com.shinobisondre.stockanalyzer.ui.ToneChip
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import com.shinobisondre.stockanalyzer.ui.theme.toneColor

private fun stanceLabel(s: String) = when (s) { "bullish" -> "Bullish"; "bearish" -> "Bearish"; "mixed" -> "Mixed"; else -> "Neutral" }
private fun arrow(view: String) = when (view) { "bullish" -> "↑"; "bearish" -> "↓"; else -> "→" }

/** "2026-11-14T15:00:12+00:00" → "14 Nov" (+ "· 2 d ago"). */
fun ytDate(iso: String): String = runCatching {
    val d = java.time.LocalDate.parse(iso.take(10))
    val days = java.time.temporal.ChronoUnit.DAYS.between(d, java.time.LocalDate.now())
    val pretty = d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH))
    when (days) { 0L -> "$pretty · today"; 1L -> "$pretty · yesterday"; in 2..13 -> "$pretty · $days d ago"; else -> pretty }
}.getOrDefault(iso.take(10))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TylerScreen(vm: MainViewModel) {
    val t by vm.tyler.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadTyler() }
    val owned = st.holdings.map { it.symbol }.toSet()
    val takes = t.ordered
    val latestAi = takes.firstOrNull { it.aiMade }
    var diag by remember { mutableStateOf(false) }

    AdaptiveGrid(contentPadding = PaddingValues(bottom = 24.dp)) {
        full {
            ScreenHeader("Tyler Hill", "What he thinks will happen · Tyler Hill Stocks on YouTube") {
                IconButton(onClick = { diag = true }) { Icon(Icons.Filled.Info, "Diagnostics") }
                IconButton(onClick = { vm.loadTyler(true) }, enabled = !t.loading) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
        }
        t.error?.let { e -> full { ErrorBox(e) { vm.loadTyler(true) } } }
        if (t.loading && takes.isEmpty()) full {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Palette.accent)
                Text("Fetching his latest videos…", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }

        // ── His current view, in one card ──
        latestAi?.let { lt ->
            cell {
                SectionCard("His latest view", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), subtitle = ytDate(lt.published)) {
                    ToneChip(stanceLabel(lt.stance), lt.tone)
                    Spacer(Modifier.height(6.dp))
                    Text(lt.headline, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (lt.market.isNotBlank()) Text(lt.market, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    if (lt.moves.isNotBlank()) Text("He's doing: ${lt.moves}", fontSize = 13.sp, color = Palette.accent, modifier = Modifier.padding(top = 4.dp))
                    if (lt.watch.isNotBlank()) Text("Waiting for: ${lt.watch}", fontSize = 13.sp, color = Palette.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // ── Per stock: his most recent call on each ticker ──
        val perTicker = takes.flatMap { tk -> tk.calls.filter { it.ticker.isNotBlank() }.map { it.ticker to (tk to it) } }
            .distinctBy { it.first }.map { it.second }
            .sortedWith(compareByDescending<Pair<TylerTake, TylerCall>> { it.second.ticker in owned }.thenByDescending { it.first.published })
        if (perTicker.isNotEmpty()) cell {
            SectionCard("Stocks he's talked about", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), subtitle = "His most recent call on each — yours first") {
                perTicker.take(14).forEach { (tk, c) ->
                    Row(Modifier.fillMaxWidth().clickable { vm.open(c.ticker) }.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.width(78.dp)) {
                            Text("${c.ticker} ${arrow(c.view)}", fontWeight = FontWeight.Bold, color = toneColor(c.tone), fontSize = 14.sp)
                            if (c.ticker in owned) Text("you own", fontSize = 10.sp, color = Palette.accent)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(c.what.ifBlank { "Mentioned" }, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            val extra = listOf(c.levels, c.timeframe, c.action).filter { it.isNotBlank() }.joinToString(" · ")
                            Text((if (extra.isNotBlank()) "$extra · " else "") + ytDate(tk.published).substringBefore(" ·"), fontSize = 11.sp, color = Palette.muted, maxLines = 2)
                        }
                    }
                }
            }
        }

        // ── Video by video ──
        val known = t.takes.keys
        val pending = t.videos.take(6).filter { it.id !in known || it.id in t.working }
        items(pending, key = { "tv-pending-" + it.id }) { v ->
            SectionCard(v.title, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), subtitle = ytDate(v.published)) {
                VideoThumb(v.id) { vm.watch(v.id) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (v.id in t.working) CircularProgressIndicator(Modifier.padding(end = 10.dp).width(18.dp).height(18.dp), color = Palette.accent, strokeWidth = 2.dp)
                    Text(t.working[v.id] ?: "Waiting to be summarised…", color = Palette.muted, fontSize = 13.sp)
                }
            }
        }
        items(takes.filter { it.videoId !in t.working }, key = { "tv-" + it.videoId }) { tk ->
            TakeCard(tk, owned, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), onTicker = { vm.open(it) }, onRetry = { vm.resummarise(tk.videoId) },
                onWatch = { vm.watch(tk.videoId) })
        }
        if (!t.loading && takes.isEmpty() && pending.isEmpty() && t.error == null && t.at > 0) full {
            EmptyState("No videos found", "Couldn't find recent uploads on the Tyler Hill Stocks channel. Pull to refresh in a bit.")
        }
        full {
            Text("Summaries are written by a free AI model (Llama 3.3 70B via OVHcloud, no account) from YouTube's captions — or, when YouTube won't share them, from listening to the video on your phone with Whisper. Either can mishear tickers and numbers, so check the video before acting. The views are Tyler's, not investment advice.",
                color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
        }
    }
    if (diag) DiagnosticsDialog { diag = false }
}

/** Video thumbnail with a play button; tap to watch inside the app. */
@Composable
fun VideoThumb(videoId: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AsyncImage(model = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg", contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(34.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TakeCard(
    tk: TylerTake, owned: Set<String>, modifier: Modifier, onTicker: (String) -> Unit, onRetry: () -> Unit,
    onWatch: (() -> Unit)?, startOpen: Boolean = false, showThumb: Boolean = true,
) {
    var open by rememberSaveable(tk.videoId) { mutableStateOf(startOpen) }
    val uri = LocalUriHandler.current
    SectionCard(tk.title, modifier, subtitle = ytDate(tk.published)) {
        if (showThumb && onWatch != null) { VideoThumb(tk.videoId, onClick = onWatch); Spacer(Modifier.height(10.dp)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tk.aiMade) ToneChip(stanceLabel(tk.stance), tk.tone)
            else Pill(if (tk.source == "description") "No transcript" else "Key quotes only", Palette.warn)
            if (tk.aiMade && tk.words == 0) Pill("From title & description only", Palette.warn)
            tk.calls.filter { it.ticker.isNotBlank() }.take(8).forEach { c ->
                val chip = "${c.ticker} ${arrow(c.view)}" + if (c.ticker in owned) " ★" else ""
                ToneChip(chip, c.tone, Modifier.clickable { onTicker(c.ticker) })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (tk.aiMade) {
            Text(tk.headline, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (tk.market.isNotBlank()) Text(tk.market, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        } else {
            Text("The free AI services didn't answer, so here are the lines where he talks about what comes next.", fontSize = 12.sp, color = Palette.muted)
        }
        if (open) {
            if (tk.calls.isNotEmpty() && tk.aiMade) {
                Spacer(Modifier.height(8.dp))
                tk.calls.forEach { c ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${c.ticker.ifBlank { c.name }}${if (c.ticker.isNotBlank() && c.name.isNotBlank()) " · ${c.name}" else ""} ${arrow(c.view)}",
                            fontWeight = FontWeight.Bold, color = toneColor(c.tone), fontSize = 13.sp)
                        if (c.what.isNotBlank()) Text(c.what, fontSize = 13.sp)
                        listOf("Levels" to c.levels, "Timeframe" to c.timeframe, "He's" to c.action).filter { it.second.isNotBlank() }.forEach { (k, v) ->
                            Text("$k: $v", fontSize = 12.sp, color = Palette.muted)
                        }
                    }
                }
            }
            if (tk.moves.isNotBlank()) Text("His own moves: ${tk.moves}", fontSize = 13.sp, color = Palette.accent, modifier = Modifier.padding(top = 6.dp))
            if (tk.watch.isNotBlank()) Text("Waiting for: ${tk.watch}", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            if (tk.caveats.isNotBlank()) Text("Caveats: ${tk.caveats}", fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 4.dp))
            if (tk.keyLines.isNotEmpty()) {
                Text("In his words", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                tk.keyLines.forEach { Text("“$it”", fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            Text(listOfNotNull(
                tk.source.removePrefix("ai:").takeIf { tk.aiMade }?.let { "Summary: $it" },
                if (tk.words > 0) "%,d-word transcript".format(tk.words) else "no transcript",
            ).joinToString(" · "), fontSize = 11.sp, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
        }
        Row {
            TextButton(onClick = { open = !open }) { Text(if (open) "Less" else "Details") }
            if (onWatch != null) TextButton(onClick = onWatch) { Text("Watch") }
            else TextButton(onClick = { runCatching { uri.openUri("https://www.youtube.com/watch?v=${tk.videoId}") } }) { Text("YouTube") }
            if (tk.words == 0) TextButton(onClick = onRetry) { Text("Listen & summarise") }
            else if (!tk.aiMade) TextButton(onClick = onRetry) { Text("Try AI again") }
        }
    }
}

/** Analysis screen: Tyler's most recent call on this stock, if he has made one. */
@Composable
fun TylerOnStock(vm: MainViewModel, symbol: String, modifier: Modifier = Modifier) {
    val t by vm.tyler.collectAsStateWithLifecycle()
    val hit = remember(t.takes, symbol) { TylerTalk.latestOn(t.takes.values.toList(), symbol) } ?: return
    val (tk, c) = hit
    SectionCard("Tyler Hill's take", modifier.clickable { vm.tab.value = com.shinobisondre.stockanalyzer.ui.Tab.TYLER }, subtitle = "${ytDate(tk.published)} · ${tk.title}") {
        ToneChip(when (c.view) { "bullish" -> "Bullish"; "bearish" -> "Bearish"; else -> "Neutral" } + (if (c.action.isNotBlank()) " · ${c.action}" else ""), c.tone)
        if (c.what.isNotBlank()) Text(c.what, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        val extra = listOf(c.levels, c.timeframe).filter { it.isNotBlank() }.joinToString(" · ")
        if (extra.isNotBlank()) Text(extra, fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 2.dp))
        if (!tk.aiMade) Text("Mentioned in the video — open the Tyler tab for his exact words.", fontSize = 12.sp, color = Palette.muted)
    }
}

/** What captions, streams, tokens and speech recognition did — copy it and send it if something fails. */
@Composable
fun DiagnosticsDialog(onClose: () -> Unit) {
    val lines by com.shinobisondre.stockanalyzer.video.Diag.lines.collectAsStateWithLifecycle()
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Diagnostics (${lines.size})") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (lines.isEmpty()) Text("Nothing logged yet. Open a video or tap refresh, then look again.", fontSize = 13.sp, color = Palette.muted)
                lines.takeLast(150).reversed().forEach { Text(it, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clip.setText(androidx.compose.ui.text.AnnotatedString("StockAnalyzer ${com.shinobisondre.stockanalyzer.BuildConfig.VERSION_NAME}\n" + com.shinobisondre.stockanalyzer.video.Diag.text()))
            }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
