package com.shinobisondre.stockanalyzer.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.shinobisondre.stockanalyzer.BuildConfig
import com.shinobisondre.stockanalyzer.StockApp
import com.shinobisondre.stockanalyzer.data.Http
import com.shinobisondre.stockanalyzer.engine.GuessKind
import com.shinobisondre.stockanalyzer.engine.MiniJson
import com.shinobisondre.stockanalyzer.work.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume

/**
 * Instant alerts, the Holmstead way: the phone gives our small server (a Cloudflare Worker,
 * see /server) its Firebase push token, the symbols you own and watch (no amounts or prices
 * paid), your alert settings and this week's predicted lows/highs. The server checks prices,
 * headlines and the Quick wins scan every minute and pushes a notification — even when the
 * app is closed, without the phone polling.
 */
object Push {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("push", Context.MODE_PRIVATE)

    /** Firebase is set up in this build (google-services.json was present) and a server URL is known. */
    fun available(ctx: Context): Boolean = BuildConfig.PUSH_URL.isNotBlank() && FirebaseApp.getApps(ctx).isNotEmpty()

    /** Registered with the server in the last 2 days — the phone's own checks can skip what the server covers. */
    fun active(ctx: Context): Boolean = available(ctx) &&
        System.currentTimeMillis() - prefs(ctx).getLong("okAt", 0) < 2 * 86_400_000L

    fun status(ctx: Context): String = when {
        !available(ctx) -> "Not set up in this version"
        active(ctx) -> "On — the server checks every minute and notifies you even when the app is closed"
        prefs(ctx).getString("error", null) != null -> "Couldn't reach the server: ${prefs(ctx).getString("error", null)}"
        else -> "Connecting…"
    }

    private fun ids(ctx: Context): Pair<String, String> {
        val p = prefs(ctx)
        val id = p.getString("id", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
        val secret = p.getString("secret", null) ?: (java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID()).replace("-", "")
            .also { p.edit().putString("secret", it).apply() }
        return id to secret
    }

    private suspend fun token(): String? = suspendCancellableCoroutine { c ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { t -> c.resume(if (t.isSuccessful) t.result else null) }
    }

    private suspend fun post(path: String, body: Map<String, Any?>): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(BuildConfig.PUSH_URL.trimEnd('/') + path)
            .post(MiniJson.write(body).toRequestBody("application/json".toMediaType())).build()
        Http.client.newCall(req).execute().use { r -> if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}"); true }
    }

    fun registerAsync(ctx: Context, newToken: String? = null) { io.launch { runCatching { register(ctx, newToken) } } }

    /** Sends the current symbols/settings/ranges. Cheap: skipped when nothing changed in the last 6 hours. */
    suspend fun register(ctx: Context, newToken: String? = null) {
        if (!available(ctx)) return
        val app = ctx.applicationContext as StockApp
        val st = app.store.value
        val s = st.settings
        val (id, secret) = ids(ctx)
        val tok = newToken ?: token() ?: return
        val owned = st.holdings.map { it.symbol }.toSet()
        val symbols = (st.holdings.map { it.symbol } + st.watchlist + st.watch.filterValues { it.sellSignalPrice != null }.keys)
            .distinct().take(30).map { mapOf("s" to it, "own" to (it in owned)) }
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val ranges = app.repo.guesses().filter { it.kind == GuessKind.RANGE && it.days == 5 && it.madeAt > weekAgo }
            .groupBy { it.symbol }.mapValues { (_, v) -> v.maxBy { it.madeAt } }
            .filterKeys { k -> symbols.any { it["s"] == k } }
            .mapValues { (_, g) -> mapOf("lo" to g.likelyLow, "hi" to g.likelyHigh, "wlo" to g.wideLow, "key" to g.key) }
        val alertsOn = s.alertsEnabled
        val body = linkedMapOf<String, Any?>(
            "id" to id, "secret" to secret, "token" to tok, "symbols" to symbols,
            "prefs" to mapOf("moves" to (alertsOn && s.notifyMoves), "bigMovePct" to s.bigMovePct, "news" to (alertsOn && s.notifyNews),
                "quick" to (alertsOn && s.notifyQuickWins), "swing" to (alertsOn && s.notifySwing)),
            "ranges" to ranges,
        )
        val hash = MiniJson.write(body).hashCode()
        val p = prefs(ctx)
        if (p.getInt("hash", 0) == hash && System.currentTimeMillis() - p.getLong("okAt", 0) < 6 * 3_600_000L) return
        try {
            post("/register", body)
            p.edit().putInt("hash", hash).putLong("okAt", System.currentTimeMillis()).remove("error").apply()
        } catch (e: Exception) {
            p.edit().putString("error", e.message ?: "failed").apply()
            throw e
        }
    }

    /** Asks the server to send a test notification in ~7 seconds (leave the app to see it). */
    suspend fun test(ctx: Context): Boolean = runCatching {
        prefs(ctx).edit().putInt("hash", 0).apply()
        register(ctx)
        val (id, secret) = ids(ctx)
        post("/test", mapOf("id" to id, "secret" to secret))
    }.getOrDefault(false)
}

/** Receives the server's pushes. In the background Android shows them itself; in the app we show them. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { Push.registerAsync(applicationContext, token) }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        val title = d["title"] ?: msg.notification?.title ?: return
        val body = d["body"] ?: msg.notification?.body ?: ""
        Notifier.post(applicationContext, Notifier.CH_ALERTS, (title.hashCode() and 0x7fffffff), title, body, d["symbol"], d["tab"])
    }
}
