package com.shinobisondre.stockanalyzer.ui.screens

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.BuildConfig
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.SectionCard
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import com.shinobisondre.stockanalyzer.work.Notifier

@Composable
private fun ToggleRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (body != null) Text(body, color = Palette.muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent))
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    val up by vm.update.collectAsStateWithLifecycle()
    val s = st.settings
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Settings")
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard("App updates", subtitle = "Installed: ${BuildConfig.VERSION_NAME}") {
                when {
                    up.progress != null -> {
                        Text("Downloading update…", fontSize = 14.sp)
                        LinearProgressIndicator(progress = { up.progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Palette.accent)
                    }
                    up.available != null -> {
                        val rel = up.available!!
                        Text("Version ${rel.versionName} is available.", fontSize = 14.sp, color = Palette.accent)
                        if (rel.notes.isNotBlank()) Text(rel.notes.take(400), color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                        Button(onClick = { vm.installUpdate() }) { Text("Download & install") }
                    }
                    up.checking -> Text("Checking…", color = Palette.muted, fontSize = 14.sp)
                    up.upToDate -> Text("You're on the latest version.", color = Palette.muted, fontSize = 14.sp)
                }
                up.error?.let { Text(it, color = Palette.strongSell, fontSize = 12.sp) }
                Row {
                    TextButton(onClick = { vm.checkUpdate() }) { Text("Check now") }
                }
                ToggleRow("Check automatically", "On launch and twice a day in the background", s.autoUpdateCheck) { v -> vm.settings { it.copy(autoUpdateCheck = v) } }
            }

            SectionCard("Portfolio currency", subtitle = "Every holding is converted at live FX rates") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("NOK", "USD", "EUR", "SEK", "DKK", "GBP").forEach { c -> SmallChip(c, s.baseCurrency == c) { vm.settings { it.copy(baseCurrency = c) } } }
                }
            }

            SectionCard("Background watcher", subtitle = "Checks your portfolio and watchlist and notifies you") {
                ToggleRow("Enabled", null, s.alertsEnabled) { v -> vm.settings { it.copy(alertsEnabled = v) } }
                Text("How often", color = Palette.muted, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours").forEach { (m, l) -> SmallChip(l, s.alertIntervalMin == m) { vm.settings { it.copy(alertIntervalMin = m) } } }
                }
                Spacer(Modifier.height(6.dp))
                Text("Big-move alert threshold", color = Palette.muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3.0, 5.0, 8.0).forEach { p -> SmallChip("±${p.toInt()}%", s.bigMovePct == p) { vm.settings { it.copy(bigMovePct = p) } } }
                }
                ToggleRow("Call changes & new setups", "Bullish ↔ hold ↔ bearish on completed daily bars", s.notifyCalls) { v -> vm.settings { it.copy(notifyCalls = v) } }
                ToggleRow("Big moves", "Once per stock per day", s.notifyMoves) { v -> vm.settings { it.copy(notifyMoves = v) } }
                ToggleRow("Tyler 3/4-stacks", null, s.notifyStacks) { v -> vm.settings { it.copy(notifyStacks = v) } }
                ToggleRow("Market regime", "Bull/caution/bear changes and S&P 500 death cross", s.notifyMarket) { v -> vm.settings { it.copy(notifyMarket = v) } }
                if (!Notifier.canPost(ctx)) {
                    Text("Notifications are blocked for this app.", color = Palette.warn, fontSize = 13.sp)
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open notification settings") }
                }
                TextButton(onClick = { vm.runWatcherNow() }) { Text("Run a check now") }
            }

            SectionCard("About") {
                Text("StockAnalyzer for Android — the StockAnalyzer web app rebuilt as a native app for the Galaxy Z Fold: one pane on the cover screen, list + analysis side by side when unfolded, and chart-on-top in Flex mode.", fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("Prices and news: Yahoo Finance and Google News. The probability model is trained and validated in GitHub Actions on 20 years of daily data; see the validation report for every number.", color = Palette.muted, fontSize = 12.sp)
                Row {
                    TextButton(onClick = { runCatching { uri.openUri("https://github.com/ShinobiSondre/stock-analyzer-app") } }) { Text("Source") }
                    TextButton(onClick = { runCatching { uri.openUri("https://github.com/ShinobiSondre/stock-analyzer-app/blob/main/docs/MODEL_REPORT.md") } }) { Text("Model report") }
                    TextButton(onClick = { runCatching { uri.openUri("https://github.com/ShinobiSondre/stock-analyzer-app/blob/main/docs/REVIEW.md") } }) { Text("Bug review") }
                }
                Text("Not investment advice. Every probability shown is a historical frequency; a 60% call is wrong 40% of the time.", color = Palette.muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
