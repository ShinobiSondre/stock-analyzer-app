package com.shinobisondre.stockanalyzer.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.data.Repository
import com.shinobisondre.stockanalyzer.engine.Link
import com.shinobisondre.stockanalyzer.engine.Ripple
import com.shinobisondre.stockanalyzer.engine.Strength
import com.shinobisondre.stockanalyzer.ui.CallBadge
import com.shinobisondre.stockanalyzer.ui.ChangeText
import com.shinobisondre.stockanalyzer.ui.ErrorBox
import com.shinobisondre.stockanalyzer.ui.F
import com.shinobisondre.stockanalyzer.ui.Load
import com.shinobisondre.stockanalyzer.ui.Loading
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.SectionCard
import com.shinobisondre.stockanalyzer.ui.appear
import com.shinobisondre.stockanalyzer.ui.pressScale
import com.shinobisondre.stockanalyzer.ui.pulse
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import com.shinobisondre.stockanalyzer.ui.theme.toneColor

/**
 * "If NVIDIA keeps doing well, what else tends to go up?" A tree with the stock in
 * the middle, its suppliers, customers, riders and rivals as branches, and how
 * tightly each one has actually moved with it. Tap a branch's "its ripples" to
 * make that stock the new middle — the waterfall keeps going.
 */
@Composable
fun RippleScreen(vm: MainViewModel) {
    val root by vm.rippleRoot.collectAsStateWithLifecycle()
    val r by vm.ripple.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val cores by vm.cores.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadRipple() }
    val owned = st.holdings.map { it.symbol }.toSet()
    val picks = (st.holdings.map { it.symbol } + Ripple.POPULAR).distinct()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader("Ripples", "If one stock does well, what tends to rise with it?") {
                IconButton(onClick = { vm.loadRipple(root, force = true) }) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                picks.forEach { s -> SmallChip(s.removeSuffix(".OL") + if (s in owned) " ★" else "", s == root) { vm.loadRipple(s) } }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it.uppercase() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("Any ticker (e.g. PLTR, EQNR.OL)") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (query.isNotBlank()) { vm.loadRipple(query.trim()); query = ""; focus.clearFocus() } }),
            )
        }
        when (val v = r) {
            is Load.Ok -> {
                val view = v.v
                item(key = "root-${view.root}") { RootCard(vm, view, cores[view.root], Modifier.padding(horizontal = 12.dp, vertical = 6.dp).appear()) }
                item {
                    Text(
                        if (view.fromYahoo) "No hand-made map for ${view.root} yet — these are stocks Yahoo says people watch alongside it, ranked by how closely they actually moved with it."
                        else "Companies connected to ${view.root.removeSuffix(".OL")}, strongest movers first in each branch. Tap one for its analysis, or \"its ripples\" to follow the chain.",
                        color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                // The tree: groups in a fixed order, each sorted by how much of the leader's move carries over.
                val groups = Link.entries.mapNotNull { l ->
                    val kids = view.children.filter { it.node.link == l }.sortedByDescending { it.stat?.score ?: -9.0 }
                    if (kids.isEmpty()) null else l to kids
                }
                var row = 0
                groups.forEachIndexed { gi, (link, kids) ->
                    val lastGroup = gi == groups.lastIndex
                    val rowIndex = row++
                    item(key = "g-${view.root}-${link.name}") {
                        TreeRow(depth = 0, lastAt = listOf(lastGroup), modifier = Modifier.appear(rowIndex)) {
                            Column(Modifier.padding(vertical = 10.dp)) {
                                Text(link.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Palette.accent)
                                Text(link.plain, fontSize = 12.sp, color = Palette.muted)
                            }
                        }
                    }
                    kids.forEachIndexed { ki, kid ->
                        val idx = row++
                        item(key = "k-${view.root}-${link.name}-${kid.node.symbol}") {
                            TreeRow(depth = 1, lastAt = listOf(lastGroup, ki == kids.lastIndex), modifier = Modifier.appear(idx)) {
                                NodeCard(vm, view.root, kid, kid.node.symbol in owned)
                            }
                        }
                    }
                }
                item {
                    Text("Moving together over the last 2 years doesn't guarantee they will again — it shows who usually gets dragged along. Not investment advice.",
                        color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
                }
            }
            is Load.Err -> item { ErrorBox(v.msg) { vm.loadRipple(root, force = true) } }
            else -> item { Loading(text = "Measuring what moves with ${root.removeSuffix(".OL")}…") }
        }
    }
}

@Composable
private fun RootCard(vm: MainViewModel, v: Repository.RippleView, core: Load<com.shinobisondre.stockanalyzer.data.Core>?, modifier: Modifier) {
    val c = (core as? Load.Ok)?.v
    SectionCard(v.root.removeSuffix(".OL"), modifier, subtitle = v.rootName) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).pulse().clip(CircleShape).background(toneColor(c?.analysis?.verdict?.call?.tone ?: com.shinobisondre.stockanalyzer.engine.Tone.NEUTRAL)))
            Spacer(Modifier.width(10.dp))
            Text(F.price(v.rootPrice, v.rootCurrency), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            ChangeText(v.rootChange, size = 15)
            Spacer(Modifier.weight(1f))
            c?.let { CallBadge(it.analysis.verdict.call) }
        }
        val p = c?.p10
        Text(
            when {
                p != null -> "Is it looking good? The model gives it ${F.prob(p)} odds of being higher in 2 weeks (normal: ${F.prob(c?.analysis?.forecast(10)?.baseRate)})."
                else -> "Tap it to see whether it's looking good right now."
            },
            fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
        )
        Row {
            TextButton(onClick = { vm.open(v.root) }) { Text("Open ${v.root.removeSuffix(".OL")}") }
        }
    }
}

@Composable
private fun NodeCard(vm: MainViewModel, root: String, kid: Repository.RippleChild, owned: Boolean) {
    val s = kid.stat
    val src = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).pressScale(src).clip(RoundedCornerShape(14.dp)).background(Palette.surface)
            .clickable(interactionSource = src, indication = androidx.compose.material3.ripple()) { vm.open(kid.node.symbol) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kid.node.symbol.removeSuffix(".OL") + if (owned) "  ★ you own" else "", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(kid.name, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                ChangeText(kid.changePct, size = 13)
                Text("today", color = Palette.muted, fontSize = 10.sp)
            }
        }
        if (s != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StrengthBars(s.strength)
                Spacer(Modifier.width(8.dp))
                Text(s.strength.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = toneColor(s.strength.tone))
            }
            Text(Ripple.explain(root, s), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            s.next5AfterBigPct?.let { n ->
                Text("The week after those best days it moved ${F.pct(n, 1)} on average (${s.bigDays} days — small sample).", fontSize = 11.sp, color = Palette.muted, modifier = Modifier.padding(top = 2.dp))
            }
        } else Text("Not enough price history to measure.", fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
        if (Ripple.nodesFor(kid.node.symbol) != null) TextButton(onClick = { vm.loadRipple(kid.node.symbol) }, contentPadding = PaddingValues(0.dp)) {
            Text("Its ripples →", fontSize = 13.sp)
        }
    }
}

/** 1–4 filled bars, filling up one after another. */
@Composable
private fun StrengthBars(s: Strength) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        (1..4).forEach { i ->
            val on = i <= s.bars && s != Strength.OPPOSITE
            val h by animateFloatAsState(if (on) 1f else 0.35f, tween(500, delayMillis = i * 90, easing = FastOutSlowInEasing), label = "bar$i")
            Box(Modifier.width(6.dp).height((6 + 10 * h).dp).clip(RoundedCornerShape(2.dp)).background(if (on) toneColor(s.tone) else Palette.surface2))
        }
    }
}

/**
 * A row of the tree with its connector lines: for each ancestor level a vertical line
 * (unless that branch already ended), and an elbow into this row.
 */
@Composable
private fun TreeRow(depth: Int, lastAt: List<Boolean>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val step: Dp = 22.dp
    val lineColor = Palette.accent.copy(alpha = 0.45f)
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 14.dp, end = 12.dp)) {
        Box(Modifier.width(step * (depth + 1)).fillMaxHeight().drawBehind {
            val w = 2.dp.toPx()
            val stepPx = step.toPx()
            for (level in 0..depth) {
                val x = level * stepPx + stepPx / 2
                val isThis = level == depth
                val last = lastAt.getOrElse(level) { false }
                if (!isThis) {
                    // Ancestor branch still running below?
                    if (!last) drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), w)
                } else {
                    val midY = if (depth == 0) 28.dp.toPx() else 30.dp.toPx()
                    drawLine(lineColor, Offset(x, 0f), Offset(x, if (last) midY else size.height), w, StrokeCap.Round)
                    drawLine(lineColor, Offset(x, midY), Offset(size.width, midY), w, StrokeCap.Round)
                    drawCircle(lineColor, 3.5.dp.toPx(), Offset(size.width - 2.dp.toPx(), midY))
                }
            }
        })
        Box(Modifier.weight(1f)) { content() }
    }
}
