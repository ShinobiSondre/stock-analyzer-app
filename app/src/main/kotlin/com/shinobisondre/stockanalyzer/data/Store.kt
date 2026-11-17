package com.shinobisondre.stockanalyzer.data

import android.content.Context
import com.shinobisondre.stockanalyzer.engine.LoggedCall
import com.shinobisondre.stockanalyzer.engine.MiniJson
import com.shinobisondre.stockanalyzer.engine.MiniJson.list
import com.shinobisondre.stockanalyzer.engine.MiniJson.num
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import com.shinobisondre.stockanalyzer.engine.MiniJson.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class Holding(
    val symbol: String, val shares: Double, val avgCost: Double?, val addedAt: Long = System.currentTimeMillis(),
    /** From imported Nordnet trades: cost per share in NOK incl. fees and the day's exchange rate, and the first buy date. */
    val costNok: Double? = null, val since: String? = null, val isin: String? = null,
)

data class Settings(
    val baseCurrency: String = "NOK",
    val alertsEnabled: Boolean = true,
    val alertIntervalMin: Int = 60,
    val bigMovePct: Double = 5.0,
    val notifyCalls: Boolean = true,
    val notifyMoves: Boolean = true,
    val notifyStacks: Boolean = true,
    val notifyMarket: Boolean = true,
    val autoUpdateCheck: Boolean = true,
    val notifyTyler: Boolean = true,
    val notifyQuickWins: Boolean = true,
)

data class AlertItem(val id: String, val symbol: String, val type: String, val title: String, val message: String, val time: Long, val read: Boolean = false)

/** Per-symbol memory for the background watcher (what it saw last time). */
data class WatchState(
    val group: String? = null,      // "bull" | "hold" | "bear" on the last COMPLETED bar
    val call: String? = null,
    val barDate: String? = null,
    val sellHits: Int = 0,
    val buyHits: Int = 0,
    val setups: String = "",
    val moveAlertDate: String? = null,
)

data class AppState(
    val watchlist: List<String> = listOf("AAPL", "MSFT", "NVDA", "SPY"),
    val holdings: List<Holding> = emptyList(),
    val settings: Settings = Settings(),
    val calls: List<LoggedCall> = emptyList(),
    val alerts: List<AlertItem> = emptyList(),
    val watch: Map<String, WatchState> = emptyMap(),
    val lastRegime: String? = null,
    val lastIndexDeathCross: Boolean? = null,
    val onboarded: Boolean = false,
)

/**
 * The whole app state in one JSON file, written atomically behind a mutex.
 * (The web app had five modules doing unsynchronised read-modify-write on
 * the same predictions.json — concurrent requests could silently drop each
 * other's updates.)
 */
class Store(context: Context) {
    private val file = File(context.filesDir, "state.json")
    private val lock = Mutex()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()
    val value: AppState get() = _state.value

    suspend fun update(fn: (AppState) -> AppState): AppState = lock.withLock {
        val next = fn(_state.value)
        _state.value = next
        withContext(Dispatchers.IO) { write(next) }
        next
    }

    fun updateAsync(fn: (AppState) -> AppState) { io.launch { update(fn) } }

    private fun load(): AppState = try {
        if (file.exists()) fromMap(MiniJson.parse(file.readText()).obj()) else AppState()
    } catch (e: Exception) {
        File(file.parentFile, "state.corrupt.json").let { b -> runCatching { file.copyTo(b, overwrite = true) } }
        AppState()
    }

    private fun write(s: AppState) {
        val tmp = File(file.parentFile, "state.json.tmp")
        tmp.writeText(MiniJson.write(toMap(s)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val MAX_CALLS = 4000
        const val MAX_ALERTS = 300

        fun toMap(s: AppState): Map<String, Any?> = linkedMapOf(
            "v" to 1,
            "watchlist" to s.watchlist,
            "holdings" to s.holdings.map { linkedMapOf("symbol" to it.symbol, "shares" to it.shares, "avgCost" to it.avgCost, "addedAt" to it.addedAt,
                "costNok" to it.costNok, "since" to it.since, "isin" to it.isin) },
            "settings" to s.settings.let {
                linkedMapOf("baseCurrency" to it.baseCurrency, "alertsEnabled" to it.alertsEnabled, "alertIntervalMin" to it.alertIntervalMin,
                    "bigMovePct" to it.bigMovePct, "notifyCalls" to it.notifyCalls, "notifyMoves" to it.notifyMoves, "notifyStacks" to it.notifyStacks,
                    "notifyMarket" to it.notifyMarket, "autoUpdateCheck" to it.autoUpdateCheck, "notifyTyler" to it.notifyTyler, "notifyQuickWins" to it.notifyQuickWins)
            },
            "calls" to s.calls.takeLast(MAX_CALLS).map {
                linkedMapOf("s" to it.symbol, "d" to it.date, "c" to it.close, "k" to it.call, "p5" to it.p5, "p10" to it.p10, "p22" to it.p22, "b" to it.base10, "m" to it.modelVersion)
            },
            "alerts" to s.alerts.take(MAX_ALERTS).map {
                linkedMapOf("id" to it.id, "symbol" to it.symbol, "type" to it.type, "title" to it.title, "message" to it.message, "time" to it.time, "read" to it.read)
            },
            "watch" to s.watch.mapValues { (_, w) ->
                linkedMapOf("group" to w.group, "call" to w.call, "barDate" to w.barDate, "sellHits" to w.sellHits, "buyHits" to w.buyHits, "setups" to w.setups, "moveAlertDate" to w.moveAlertDate)
            },
            "lastRegime" to s.lastRegime,
            "lastIndexDeathCross" to s.lastIndexDeathCross,
            "onboarded" to s.onboarded,
        )

        fun fromMap(m: Map<String, Any?>): AppState {
            val d = AppState()
            val st = m["settings"].obj()
            fun b(o: Map<String, Any?>, k: String, def: Boolean) = o[k] as? Boolean ?: def
            return AppState(
                watchlist = m["watchlist"].list().mapNotNull { it.str() }.ifEmpty { if (m.containsKey("watchlist")) emptyList() else d.watchlist },
                holdings = m["holdings"].list().map { it.obj() }.mapNotNull { h ->
                    val sym = h["symbol"].str() ?: return@mapNotNull null
                    Holding(sym, h["shares"].num() ?: return@mapNotNull null, h["avgCost"].num(), h["addedAt"].num()?.toLong() ?: 0L,
                        h["costNok"].num(), h["since"] as? String, h["isin"] as? String)
                },
                settings = Settings(
                    baseCurrency = st["baseCurrency"].str() ?: d.settings.baseCurrency,
                    alertsEnabled = b(st, "alertsEnabled", d.settings.alertsEnabled),
                    alertIntervalMin = st["alertIntervalMin"].num()?.toInt() ?: d.settings.alertIntervalMin,
                    bigMovePct = st["bigMovePct"].num() ?: d.settings.bigMovePct,
                    notifyCalls = b(st, "notifyCalls", true), notifyMoves = b(st, "notifyMoves", true),
                    notifyStacks = b(st, "notifyStacks", true), notifyMarket = b(st, "notifyMarket", true),
                    autoUpdateCheck = b(st, "autoUpdateCheck", true), notifyTyler = b(st, "notifyTyler", true), notifyQuickWins = b(st, "notifyQuickWins", true),
                ),
                calls = m["calls"].list().map { it.obj() }.mapNotNull { c ->
                    LoggedCall(c["s"].str() ?: return@mapNotNull null, c["d"].str() ?: return@mapNotNull null, c["c"].num() ?: 0.0,
                        c["k"].str() ?: "HOLD", c["p5"].num(), c["p10"].num(), c["p22"].num(), c["b"].num(), c["m"].str())
                },
                alerts = m["alerts"].list().map { it.obj() }.mapNotNull { a ->
                    AlertItem(a["id"].str() ?: return@mapNotNull null, a["symbol"].str() ?: "", a["type"].str() ?: "", a["title"].str() ?: "",
                        a["message"].str() ?: "", a["time"].num()?.toLong() ?: 0L, a["read"] as? Boolean ?: false)
                },
                watch = m["watch"].obj().mapValues { (_, v) ->
                    val w = v.obj()
                    WatchState(w["group"].str(), w["call"].str(), w["barDate"].str(), w["sellHits"].num()?.toInt() ?: 0,
                        w["buyHits"].num()?.toInt() ?: 0, w["setups"].str() ?: "", w["moveAlertDate"].str())
                },
                lastRegime = m["lastRegime"].str(),
                lastIndexDeathCross = m["lastIndexDeathCross"] as? Boolean,
                onboarded = m["onboarded"] as? Boolean ?: false,
            )
        }
    }
}
