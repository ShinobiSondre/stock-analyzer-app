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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
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
                    val groups = Link.entries.mapNotNull { l ->
                        val kids = view.children.filter { it.node.link == l }.sortedByDescending { it.stat?.score ?: -9.0 }
                        if (kids.isEmpty()) null else l to kids
                    }
                    // Wide screen: an org-chart waterfall with one column per branch. Narrow: a vertical tree.
                    val columns = groups.size.coerceAtLeast(1)
                    val wide = width >= 720.dp && (width - 24.dp) / columns >= 190.dp
                    item {
                        Text(
                            if (view.fromYahoo) "No hand-made map for ${view.root} yet — these are stocks Yahoo says people watch alongside it, ranked by how closely they actually moved with it."
                            else "Companies connected to ${view.root.removeSuffix(".OL")}, strongest movers first in each branch. Tap one for its analysis, or \"its ripples\" to follow the chain.",
                            color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
                        )
                    }
                    item(key = "root-${view.root}") {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (wide) Alignment.CenterHorizontally else Alignment.Start) {
                            RootCard(vm, view, cores[view.root],
                                (if (wide) Modifier.widthIn(max = 560.dp) else Modifier).padding(start = 12.dp, end = 12.dp, top = 8.dp).appear())
                            // The trunk: from the middle card down into the tree.
                            if (!wide) Box(Modifier.padding(start = TRUNK_X - 1.dp).width(2.dp).height(10.dp).background(LINE))
                        }
                    }
                    if (wide) {
                        item(key = "wide-${view.root}") { WideTree(vm, view, groups, owned) }
                    } else {
                        var row = 0
                        groups.forEachIndexed { gi, (link, kids) ->
                            val lastGroup = gi == groups.lastIndex
                            val rowIndex = row++
                            item(key = "g-${view.root}-${link.name}") {
                                TreeRow(depth = 0, lastAt = listOf(lastGroup), modifier = Modifier.appear(rowIndex)) {
                                    GroupPill(link, Modifier.padding(top = 8.dp, bottom = 4.dp))
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
}

private val LINE get() = Palette.accent.copy(alpha = 0.45f)
/** x of the tree's main line, measured from the screen edge (row padding + half a step). */
private val TRUNK_X = 14.dp + 12.dp

/** A branch title as a soft pill: what the connection is, in plain words. */
@Composable
private fun GroupPill(link: Link, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.accent.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(link.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Palette.accent)
        Text(link.plain, fontSize = 12.sp, color = Palette.muted)
    }
}

/**
 * Wide screens: the middle stock on top, a line splitting into one column per branch,
 * and each branch's companies hanging below it — drawn in as the tree appears.
 */
@Composable
private fun WideTree(vm: MainViewModel, view: Repository.RippleView, groups: List<Pair<Link, List<Repository.RippleChild>>>, owned: Set<String>) {
    val draw = remember(view.root) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(view.root) { draw.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val gapDp = 14.dp
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val n = groups.size
            val gap = gapDp.toPx()
            val colW = (size.width - gap * (n - 1)) / n
            val centers = (0 until n).map { it * (colW + gap) + colW / 2 }
            val midX = size.width / 2
            val midY = size.height * 0.45f
            val w = 2.dp.toPx()
            val p = draw.value
            // Trunk, then the bar spreading out both ways, then a drop into every branch.
            drawLine(LINE, Offset(midX, 0f), Offset(midX, midY * (p * 3).coerceAtMost(1f)), w, StrokeCap.Round)
            val spread = ((p - 0.33f) * 3).coerceIn(0f, 1f)
            if (spread > 0f && n > 1) drawLine(LINE, Offset(midX - (midX - centers.first()) * spread, midY), Offset(midX + (centers.last() - midX) * spread, midY), w, StrokeCap.Round)
            val drop = ((p - 0.66f) * 3).coerceIn(0f, 1f)
            if (drop > 0f) centers.forEach { x -> drawLine(LINE, Offset(x, midY), Offset(x, midY + (size.height - midY) * drop), w, StrokeCap.Round) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gapDp)) {
            groups.forEachIndexed { gi, (link, kids) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    GroupPill(link, Modifier.fillMaxWidth().appear(gi))
                    kids.forEachIndexed { ki, kid ->
                        Box(Modifier.width(2.dp).height(12.dp).background(LINE).appear(gi + ki + 1))
                        Box(Modifier.appear(gi + ki + 1)) { NodeCard(vm, view.root, kid, kid.node.symbol in owned) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootCard(vm: MainViewModel, v: Repository.RippleView, core: Load<com.shinobisondre.stockanalyzer.data.Core>?, modifier: Modifier) {
    val c = (core as? Load.Ok)?.v
    SectionCard(v.root.removeSuffix(".OL"), modifier, subtitle = v.rootName) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 2.dp).size(10.dp).pulse().clip(CircleShape).background(toneColor(c?.analysis?.verdict?.call?.tone ?: com.shinobisondre.stockanalyzer.engine.Tone.NEUTRAL)))
            Spacer(Modifier.width(14.dp))
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
 * A row of the vertical tree with its connector lines: for each ancestor level a vertical
 * line (unless that branch already ended), and an elbow that stops short of the content
 * with a small dot, so titles never touch the lines.
 */
@Composable
private fun TreeRow(depth: Int, lastAt: List<Boolean>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val step: Dp = 24.dp
    val anchor: Dp = 26.dp // level with the first line of text in a pill or card
    val lineColor = LINE
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 14.dp, end = 12.dp)) {
        Box(Modifier.width(step * (depth + 1)).fillMaxHeight().drawBehind {
            val w = 2.dp.toPx()
            val stepPx = step.toPx()
            val midY = anchor.toPx()
            for (level in 0..depth) {
                val x = level * stepPx + stepPx / 2
                val last = lastAt.getOrElse(level) { false }
                if (level < depth) {
                    if (!last) drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), w)
                } else {
                    drawLine(lineColor, Offset(x, 0f), Offset(x, if (last) midY else size.height), w, StrokeCap.Round)
                    val endX = size.width - 4.dp.toPx()
                    drawLine(lineColor, Offset(x, midY), Offset(endX, midY), w, StrokeCap.Round)
                    drawCircle(Palette.accent, 3.dp.toPx(), Offset(endX, midY))
                }
            }
        })
        Box(Modifier.weight(1f).padding(start = 10.dp)) { content() }
    }
}
