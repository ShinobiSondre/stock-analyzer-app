package com.shinobisondre.stockanalyzer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.ui.screens.AlertsScreen
import com.shinobisondre.stockanalyzer.ui.screens.AnalysisScreen
import com.shinobisondre.stockanalyzer.ui.screens.MarketsScreen
import com.shinobisondre.stockanalyzer.ui.screens.PortfolioScreen
import com.shinobisondre.stockanalyzer.ui.screens.ScannerScreen
import com.shinobisondre.stockanalyzer.ui.screens.SettingsScreen
import com.shinobisondre.stockanalyzer.ui.theme.Palette

/** Hinge geometry from Jetpack WindowManager (window coordinates, px). */
data class FoldInfo(
    val present: Boolean = false,
    val vertical: Boolean = false,
    val separating: Boolean = false,
    val halfOpened: Boolean = false,
    val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0,
) {
    /** Book posture or a separating vertical hinge: split panes on it. */
    val splitsVertically get() = present && vertical && (separating || halfOpened)
    /** Tabletop / Flex mode: horizontal hinge, half opened. */
    val tabletop get() = present && !vertical && halfOpened
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.MARKETS -> Icons.Filled.Home
    Tab.QUICK -> com.shinobisondre.stockanalyzer.ui.screens.BoltIcon
    Tab.TYLER -> Icons.Filled.PlayArrow
    Tab.RIPPLE -> Icons.Filled.Share
    Tab.SCORE -> Icons.Filled.CheckCircle
    Tab.PICKS -> Icons.Filled.Star
    Tab.SCANNER -> Icons.Filled.Search
    Tab.PORTFOLIO -> Icons.Filled.AccountBox
    Tab.ALERTS -> Icons.Filled.Notifications
    Tab.SETTINGS -> Icons.Filled.Settings
}

@Composable
fun AppRoot(vm: MainViewModel, fold: FoldInfo) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val unread = st.alerts.count { !it.read }
    val watching by vm.watching.collectAsStateWithLifecycle()
    val videoFull by vm.videoFull.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) {
        // Z Fold cover screen ≈ 400-460 dp wide → compact. Inner screen ≈ 840-900 dp → two panes.
        val wide = maxWidth >= 600.dp
        val w = watching
        when {
            w != null && videoFull -> com.shinobisondre.stockanalyzer.ui.screens.VideoScreen(vm, w, showClose = true)
            wide -> WideLayout(vm, tab, selected, unread, fold, w)
            else -> CompactLayout(vm, tab, selected, unread, fold, w)
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 72.dp))
        // Short animated intro on a fresh start (not when folding/unfolding or coming back).
        var intro by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
        if (intro) IntroSplash { intro = false }
    }
}

@Composable
private fun CompactLayout(vm: MainViewModel, tab: Tab, selected: String?, unread: Int, fold: FoldInfo, watching: String?) {
    Scaffold(
        containerColor = Palette.bg,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        bottomBar = {
            if (selected == null && watching == null) NavigationBar(containerColor = Palette.surface) {
                // Settings lives behind the gear on the home page on the narrow screen.
                Tab.entries.filter { it.inBottomBar }.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { vm.tab.value = t },
                        icon = { TabIcon(t, unread) },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Palette.accent.copy(alpha = 0.18f), selectedIconColor = Palette.accent, selectedTextColor = Palette.accent),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            // Screens slide: a stock or video slides in from the right over the tab it came from.
            val screen = when { watching != null -> "v:$watching"; selected != null -> "s:$selected"; else -> "t:${tab.name}" }
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val deeper = !targetState.startsWith("t:") && initialState.startsWith("t:")
                    val back = targetState.startsWith("t:") && !initialState.startsWith("t:")
                    when {
                        deeper -> (slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(260))) togetherWith fadeOut(tween(180))
                        back -> fadeIn(tween(260)) togetherWith (slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(200)))
                        else -> (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 30 }) togetherWith fadeOut(tween(140))
                    }
                },
                label = "screen",
            ) { key ->
                when {
                    key.startsWith("v:") -> com.shinobisondre.stockanalyzer.ui.screens.VideoScreen(vm, key.removePrefix("v:"), showClose = true)
                    key.startsWith("s:") -> {
                        BackHandler { vm.close() }
                        AnalysisScreen(vm, key.removePrefix("s:"), showBack = true, tabletopTop = tabletopTop(fold))
                    }
                    else -> {
                        val t = Tab.valueOf(key.removePrefix("t:"))
                        BackHandler(enabled = t != Tab.MARKETS) { vm.tab.value = Tab.MARKETS }
                        TabContent(vm, t, twoPane = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun WideLayout(vm: MainViewModel, tab: Tab, selected: String?, unread: Int, fold: FoldInfo, watching: String?) {
    val density = LocalDensity.current
    val railWidth = 80.dp
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val contentWidth = maxWidth - railWidth
        // Put the pane divider exactly on the hinge when the device is in book posture.
        val hinge: Pair<Dp, Dp>? = if (fold.splitsVertically) with(density) {
            val insetLeft = (WindowInsets.safeDrawing.getLeft(this, androidx.compose.ui.unit.LayoutDirection.Ltr)).toDp()
            val start = fold.left.toDp() - insetLeft - railWidth
            val width = (fold.right - fold.left).toDp()
            if (start > 240.dp && start < contentWidth - 240.dp) start to width else null
        } else null
        val listWidth = hinge?.first ?: (contentWidth * 0.42f).coerceIn(320.dp, 480.dp)
        val gap = hinge?.second ?: 0.dp

        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = Palette.surface, modifier = Modifier.width(railWidth).fillMaxHeight(), windowInsets = WindowInsets(0, 0, 0, 0)) {
                Spacer(Modifier.height(8.dp))
                Tab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = t == tab,
                        onClick = { vm.tab.value = t },
                        icon = { TabIcon(t, unread) },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationRailItemDefaults.colors(indicatorColor = Palette.accent.copy(alpha = 0.18f), selectedIconColor = Palette.accent, selectedTextColor = Palette.accent),
                    )
                }
            }
            if (tab == Tab.SETTINGS) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 760.dp).fillMaxWidth()) { TabContent(vm, tab, twoPane = true) }
                }
            } else {
                // Split only when there's something to show on the right (a stock or a video), or when the
                // phone is half-folded like a book (the hinge is the natural divider). Otherwise the tab
                // gets the whole screen and lays its cards out in columns.
                val split = selected != null || watching != null || hinge != null
                val listW by animateDpAsState(if (split) listWidth else contentWidth, tween(380, easing = FastOutSlowInEasing), label = "list")
                val pane = when { watching != null -> "v:$watching"; selected != null -> "s:$selected"; else -> "none" }
                // Keep showing the last stock/video while the pane slides shut.
                val lastPane = remember { arrayOf("none") }
                if (pane != "none") lastPane[0] = pane
                Box(Modifier.width(listW).fillMaxHeight()) {
                    AnimatedContent(tab, transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 30 }) togetherWith fadeOut(tween(140)) }, label = "tab") { t ->
                        TabContent(vm, t, twoPane = true)
                    }
                }
                if (listW < contentWidth - 2.dp) {
                    if (gap > 0.dp) Spacer(Modifier.width(gap).fillMaxHeight()) else Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.outline))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val show = if (pane == "none" && hinge == null) lastPane[0] else pane
                        AnimatedContent(show, transitionSpec = {
                            (fadeIn(tween(280)) + slideInHorizontally(tween(320)) { it / 12 }) togetherWith fadeOut(tween(160))
                        }, label = "pane") { key ->
                            when {
                                key.startsWith("v:") -> com.shinobisondre.stockanalyzer.ui.screens.VideoScreen(vm, key.removePrefix("v:"), showClose = true)
                                key.startsWith("s:") -> {
                                    if (selected != null) BackHandler { vm.close() }
                                    AnalysisScreen(vm, key.removePrefix("s:"), showBack = true, tabletopTop = tabletopTop(fold))
                                }
                                else -> Column(Modifier.fillMaxSize(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                                    EmptyState("Pick a stock", "Tap any stock on the left — its analysis opens here, next to the list.")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun tabletopTop(fold: FoldInfo): Dp? {
    if (!fold.tabletop) return null
    val density = LocalDensity.current
    return with(density) { (fold.top.toDp() - WindowInsets.safeDrawing.getTop(this).toDp()).takeIf { it > 160.dp } }
}

@Composable
private fun TabIcon(t: Tab, unread: Int) {
    if (t == Tab.ALERTS && unread > 0) {
        BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) { Icon(t.icon(), t.label) }
    } else Icon(t.icon(), t.label)
}

@Composable
fun TabContent(vm: MainViewModel, tab: Tab, twoPane: Boolean) {
    when (tab) {
        Tab.MARKETS -> MarketsScreen(vm)
        Tab.QUICK -> com.shinobisondre.stockanalyzer.ui.screens.QuickWinsScreen(vm)
        Tab.RIPPLE -> com.shinobisondre.stockanalyzer.ui.screens.RippleScreen(vm)
        Tab.SCORE -> com.shinobisondre.stockanalyzer.ui.screens.ScorecardScreen(vm)
        Tab.PICKS -> com.shinobisondre.stockanalyzer.ui.screens.PicksScreen(vm)
        Tab.TYLER -> com.shinobisondre.stockanalyzer.ui.screens.TylerScreen(vm)
        Tab.SCANNER -> ScannerScreen(vm)
        Tab.PORTFOLIO -> PortfolioScreen(vm, twoPane)
        Tab.ALERTS -> AlertsScreen(vm)
        Tab.SETTINGS -> SettingsScreen(vm)
    }
}
