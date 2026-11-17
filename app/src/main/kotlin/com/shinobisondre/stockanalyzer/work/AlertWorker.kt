package com.shinobisondre.stockanalyzer.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shinobisondre.stockanalyzer.MainActivity
import com.shinobisondre.stockanalyzer.R
import com.shinobisondre.stockanalyzer.StockApp
import com.shinobisondre.stockanalyzer.data.AlertItem
import com.shinobisondre.stockanalyzer.data.WatchState
import com.shinobisondre.stockanalyzer.engine.Analyzer
import com.shinobisondre.stockanalyzer.engine.Call
import com.shinobisondre.stockanalyzer.engine.SetupKind
import com.shinobisondre.stockanalyzer.update.Updater
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object Notifier {
    const val CH_ALERTS = "alerts"
    const val CH_UPDATES = "updates"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Stock alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Call changes, big moves, 4-stack signals and market regime changes for your portfolio and watchlist"
        })
        nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW))
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun postIntent(ctx: Context, channel: String, id: Int, title: String, text: String, pi: PendingIntent) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) { }
    }

    fun post(ctx: Context, channel: String, id: Int, title: String, text: String, symbol: String? = null, tab: String? = null) {
        if (!canPost(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            symbol?.let { putExtra(MainActivity.EXTRA_SYMBOL, it) }
            tab?.let { putExtra(MainActivity.EXTRA_TAB, it) }
            if (channel == CH_UPDATES) putExtra(MainActivity.EXTRA_UPDATE, true)
        }
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) { }
    }
}

/**
 * Background watcher for the portfolio + watchlist. Fixes vs livePoller.js:
 *  • judged on COMPLETED daily bars only — intraday wiggles flipped calls
 *    back and forth (17 of the 44 stored flip alerts reversed within 24 h);
 *  • alerts on a change of direction group (bullish / hold / bearish) or an
 *    upgrade to STRONG BUY, not on every label step;
 *  • uses the same market context as the analysis screen (the poller left
 *    out the fear signal and the index death-cross gate).
 */
class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as StockApp
        val repo = app.repo
        val store = repo.store
        val st = store.value
        if (!st.settings.alertsEnabled) return Result.success()
        val newAlerts = mutableListOf<AlertItem>()
        val watch = st.watch.toMutableMap()
        val settings = st.settings

        // ── Market regime ──
        val market = runCatching { repo.market(force = true) }.getOrNull()
        val pt = market?.point
        var lastRegime = st.lastRegime
        var lastIdc = st.lastIndexDeathCross
        if (pt != null) {
            val reg = pt.regime.name
            if (settings.notifyMarket && lastRegime != null && lastRegime != reg)
                newAlerts += alert("MARKET", "regime", "Market regime: ${pretty(lastRegime)} → ${pt.regime.label}", pt.regimeNote())
            if (settings.notifyMarket && lastIdc != null && lastIdc != pt.indexDeathCross)
                newAlerts += alert("MARKET", "regime",
                    if (pt.indexDeathCross) "S&P 500 death cross" else "S&P 500 death cross cleared",
                    if (pt.indexDeathCross) "The 50-day fell below the 200-day. Fear-spike buys are switched off until it recovers — they had no edge in that tape (2008, 2022)."
                    else "The 50-day is back above the 200-day. Fear-spike setups count again.")
            lastRegime = reg; lastIdc = pt.indexDeathCross
        }

        // ── Symbols ──
        // Also keep watching stocks with an open "sell now" signal, so the "buy back" can come even after you sold.
        val symbols = (st.holdings.map { it.symbol } + st.watchlist + st.watch.filterValues { it.sellSignalPrice != null }.keys).distinct().take(45)
        val owned = st.holdings.map { it.symbol }.toSet()
        for (sym in symbols) {
            val core = runCatching { repo.analyze(sym, force = true) }.getOrNull() ?: continue
            val completed = core.chart.completedCandles()
            val a = if (completed.size == core.chart.candles.size) core.analysis
            else Analyzer.analyze(sym, completed, market?.series, repo.model) ?: continue
            val prev = watch[sym] ?: WatchState()
            val call = a.verdict.call
            val group = when { call.bullish -> "bull"; call.bearish -> "bear"; else -> "hold" }
            var next = prev

            if (prev.barDate != a.lastDate) {
                // Call change between completed sessions.
                val upgradeToStrong = call == Call.STRONG_BUY && prev.call != null && prev.call != Call.STRONG_BUY.text
                if (settings.notifyCalls && prev.group != null && (prev.group != group || upgradeToStrong)) {
                    val p = a.verdict.probability?.let { " — ${(it * 100).roundToInt()}% odds up over ${a.verdict.horizonDays} sessions" } ?: ""
                    newAlerts += alert(sym, "call", "$sym: ${prev.call ?: "?"} → ${call.text}$p", a.verdict.description.take(300))
                }
                // Tyler 4-stack crossing 3+.
                val t = a.tyler
                if (settings.notifyStacks && t.sellHits >= 3 && t.sellHits > prev.sellHits)
                    newAlerts += alert(sym, "stack", "$sym: Tyler ${t.verdict.label} (${t.sellHits}/4 sell stack)", t.verdict.message)
                if (settings.notifyStacks && t.buyHits >= 3 && t.buyHits > prev.buyHits)
                    newAlerts += alert(sym, "stack", "$sym: Tyler ${t.verdict.label} (${t.buyHits}/4 buy stack)", t.verdict.message)
                // New measured setups.
                val setupKeys = a.rules.setups.filter { it.kind in setOf(SetupKind.LEADER_PULLBACK, SetupKind.FEAR_SPIKE) }.joinToString(",") { it.kind.name }
                for (s in a.rules.setups) {
                    if (s.kind !in setOf(SetupKind.LEADER_PULLBACK, SetupKind.FEAR_SPIKE)) continue
                    if (settings.notifyCalls && !prev.setups.contains(s.kind.name) && prev.barDate != null)
                        newAlerts += alert(sym, "setup", "$sym: ${s.title}", "${s.detail} ${s.evidence}")
                }
                next = next.copy(group = group, call = call.text, barDate = a.lastDate, sellHits = t.sellHits, buyHits = t.buyHits, setups = setupKeys)

                // Sell now → (later) buy back, for stocks you own.
                if (settings.notifySwing) {
                    val soldAt = prev.sellSignalPrice
                    val age = prev.sellSignalDate?.let { runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(it), java.time.LocalDate.now()) }.getOrNull() }
                    if (soldAt == null || (age != null && age > 90)) {
                        if (sym in owned) com.shinobisondre.stockanalyzer.engine.Swing.sellNow(a)?.let { why ->
                            newAlerts += alert(sym, "swing", "Sell $sym now?", "$why We'll tell you when it looks good to buy back. (The app's best read, not a guarantee.)")
                            next = next.copy(sellSignalPrice = a.lastClose, sellSignalDate = a.lastDate)
                        } ?: run { if (soldAt != null) next = next.copy(sellSignalPrice = null, sellSignalDate = null) }
                    } else com.shinobisondre.stockanalyzer.engine.Swing.buyBack(a, soldAt)?.let { why ->
                        newAlerts += alert(sym, "swing", "Buy $sym back?", why)
                        next = next.copy(sellSignalPrice = null, sellSignalDate = null)
                    }
                }
            }

            // Big intraday move (once per day).
            val chg = core.changePct
            val today = core.chart.last?.date
            if (settings.notifyMoves && chg != null && abs(chg) >= settings.bigMovePct && prev.moveAlertDate != today) {
                val dir = if (chg > 0) "up" else "down"
                // For big up-days, add the sell-by-the-close lean from 20 years of similar days.
                val qw = if (chg > 0) repo.quickFromChart(sym, core.chart, st.holdings.any { it.symbol == sym }, sym in st.watchlist) else null
                val call = qw?.let { com.shinobisondre.stockanalyzer.engine.DayAfter.plain(it.advice, it.owned, it.costPct, if (it.sessionOpen) it.closeLocal else null) }
                val body = "Price ${"%.2f".format(core.price ?: 0.0)} ${core.currency}. " +
                    (call?.let { "${it.action}. Because: ${it.because}" } ?: "Open the app for what's driving it.")
                newAlerts += alert(sym, "move", "$sym $dir ${"%.1f".format(abs(chg))}% today", body)
                next = next.copy(moveAlertDate = today)
            }
            watch[sym] = next
        }

        store.update { s ->
            s.copy(
                watch = watch.filterKeys { it in symbols },
                alerts = (newAlerts + s.alerts).take(300),
                lastRegime = lastRegime ?: s.lastRegime,
                lastIndexDeathCross = lastIdc ?: s.lastIndexDeathCross,
            )
        }
        newAlerts.forEachIndexed { i, al ->
            Notifier.post(applicationContext, Notifier.CH_ALERTS, (al.id.hashCode() and 0x7fffffff) + i, al.title, al.message, al.symbol.takeIf { it != "MARKET" })
        }

        // Breaking news on the stocks you own: new headlines that sound clearly good or bad.
        if (settings.notifyNews) runCatching { newsCheck(st.holdings.map { it.symbol }) }

        // Quick wins from the server scan: one small download, matched with your stocks on the phone.
        if (settings.notifyQuickWins) runCatching { quickWinCheck(repo) }

        // New Tyler Hill video → summarise it and say what he expects (checked every ~3 hours).
        if (settings.notifyTyler) runCatching { tylerCheck(repo) }

        // Daily update check.
        if (settings.autoUpdateCheck) runCatching { Updater.backgroundCheck(applicationContext) }
        return Result.success()
    }

    private suspend fun newsCheck(symbols: List<String>) {
        val prefs = applicationContext.getSharedPreferences("news", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet())!!.toMutableSet()
        val now = System.currentTimeMillis() / 1000
        var id = 9300
        for (sym in symbols.take(15)) {
            val items = runCatching { com.shinobisondre.stockanalyzer.data.Yahoo.search(sym, news = 6, quotes = 0).second }.getOrDefault(emptyList())
            val fresh = items.filter { (it.timeSec ?: 0) > now - 3 * 3600 && it.link !in seen }
                .map { it to com.shinobisondre.stockanalyzer.data.News.sentiment(it.title) }.filter { it.second != 0 }
            fresh.firstOrNull()?.let { (n, s) ->
                Notifier.post(applicationContext, Notifier.CH_ALERTS, id++, "News on ${sym.removeSuffix(".OL")}: sounds ${if (s > 0) "good" else "bad"}",
                    "“${n.title}” (${n.publisher}). Prices often react within minutes — open the app to see how it's moving.", sym)
            }
            fresh.forEach { seen += it.first.link }
        }
        prefs.edit().putStringSet("seen", seen.toList().takeLast(400).toSet()).apply()
    }

    private suspend fun quickWinCheck(repo: com.shinobisondre.stockanalyzer.data.Repository) {
        val items = repo.serverQuickWins() ?: return
        val prefs = applicationContext.getSharedPreferences("quickwins", Context.MODE_PRIVATE)
        val day = java.time.LocalDate.now().toString()
        val seen = prefs.getString("seen", "")!!.split(',').filter { it.startsWith("$day:") }.map { it.substringAfter(':') }.toSet()
        fun call(w: com.shinobisondre.stockanalyzer.data.QuickWin) =
            com.shinobisondre.stockanalyzer.engine.DayAfter.plain(w.advice, w.owned, w.costPct, if (w.sessionOpen) w.closeLocal else null)
        // Yours and your watchlist always; other stocks only when they're worth buying.
        val fresh = items.filter { it.symbol !in seen && (it.owned || it.watched || call(it).tone == com.shinobisondre.stockanalyzer.engine.Tone.BUY) }
            .sortedWith(compareByDescending<com.shinobisondre.stockanalyzer.data.QuickWin> { it.owned }.thenByDescending { it.changePct }).take(4)
        if (fresh.isEmpty()) return
        prefs.edit().putString("seen", (seen + fresh.map { it.symbol }).joinToString(",") { "$day:$it" }).apply()
        val title = "⚡ " + fresh.joinToString(", ") { "${it.symbol.removeSuffix(".OL")} +${"%.1f".format(it.changePct)}%" }
        val body = fresh.joinToString("\n") { w -> "${w.symbol.removeSuffix(".OL")}: ${call(w).action}" }
        Notifier.post(applicationContext, Notifier.CH_ALERTS, 9200, title, body, tab = "QUICK")
    }

    private suspend fun tylerCheck(repo: com.shinobisondre.stockanalyzer.data.Repository) {
        val prefs = applicationContext.getSharedPreferences("tyler", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastCheck", 0) < 3 * 3600_000L) return
        prefs.edit().putLong("lastCheck", now).apply()
        val newest = repo.tylerVideos().firstOrNull() ?: return
        val seen = prefs.getString("lastSeen", null)
        prefs.edit().putString("lastSeen", newest.id).apply()
        if (seen == null || seen == newest.id) return // first run: just remember where we are
        val take = repo.tylerCache().takes[newest.id] ?: repo.tylerSummarize(newest) { }
        val body = if (take.aiMade) listOf(take.headline, take.calls.filter { it.ticker.isNotBlank() }.take(5).joinToString("  ") {
            it.ticker + when (it.view) { "bullish" -> " ↑"; "bearish" -> " ↓"; else -> "" }
        }).filter { it.isNotBlank() }.joinToString("\n") else "New video: ${newest.title}"
        Notifier.post(applicationContext, Notifier.CH_ALERTS, 9100, "Tyler Hill: ${newest.title}".take(90), body, tab = "TYLER")
    }

    private fun alert(symbol: String, type: String, title: String, message: String) =
        AlertItem(UUID.randomUUID().toString(), symbol, type, title, message, System.currentTimeMillis())

    private fun pretty(regime: String) = regime.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    companion object {
        private const val NAME = "watcher"

        fun schedule(ctx: Context, enabled: Boolean, intervalMin: Int) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<AlertWorker>(intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
