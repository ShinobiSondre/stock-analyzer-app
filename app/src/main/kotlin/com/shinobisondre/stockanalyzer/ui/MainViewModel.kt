package com.shinobisondre.stockanalyzer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shinobisondre.stockanalyzer.StockApp
import com.shinobisondre.stockanalyzer.data.Core
import com.shinobisondre.stockanalyzer.data.Extras
import com.shinobisondre.stockanalyzer.data.Holding
import com.shinobisondre.stockanalyzer.data.MarketData
import com.shinobisondre.stockanalyzer.data.PortfolioView
import com.shinobisondre.stockanalyzer.data.SearchHit
import com.shinobisondre.stockanalyzer.data.Settings
import com.shinobisondre.stockanalyzer.data.TrackView
import com.shinobisondre.stockanalyzer.data.Yahoo
import com.shinobisondre.stockanalyzer.engine.HoldingsText
import com.shinobisondre.stockanalyzer.update.Updater
import com.shinobisondre.stockanalyzer.work.AlertWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ok<T>(val v: T) : Load<T>
    data class Err(val msg: String) : Load<Nothing>
}

fun <T> Load<T>.value(): T? = (this as? Load.Ok<T>)?.v

enum class Tab(val label: String) { MARKETS("Markets"), SCANNER("Scanner"), PORTFOLIO("Portfolio"), ALERTS("Alerts"), SETTINGS("Settings") }

enum class Universe(val label: String, val note: String) {
    CORE("US core 30", "The web app's default watchlist"),
    US_LARGE("US large caps", "60 large US companies across sectors"),
    OSLO("Oslo Børs", "OBX-style names — model calibrated on US stocks, treat as indicative"),
    MINE("My lists", "Your watchlist + portfolio"),
}

object Universes {
    val CORE = listOf("NVDA", "AAPL", "MSFT", "TSLA", "AMZN", "GOOGL", "META", "AMD", "AVGO", "CRM", "PLTR", "ARM", "SMCI", "MSTR", "COIN",
        "SHOP", "XYZ", "SOFI", "HOOD", "RBLX", "ENPH", "FSLR", "NEE", "LLY", "NVO", "MRNA", "ABBV", "JPM", "V", "UNH")
    val US_LARGE = listOf("AAPL", "MSFT", "NVDA", "GOOGL", "AMZN", "META", "AVGO", "TSLA", "BRK-B", "LLY", "JPM", "V", "UNH", "XOM", "MA",
        "JNJ", "PG", "HD", "COST", "ABBV", "MRK", "WMT", "NFLX", "BAC", "CRM", "KO", "CVX", "AMD", "PEP", "ORCL", "TMO", "ADBE", "LIN",
        "MCD", "CSCO", "ACN", "ABT", "WFC", "DHR", "INTU", "TXN", "QCOM", "GE", "CAT", "IBM", "AMGN", "VZ", "PFE", "NOW", "ISRG",
        "SPGI", "UBER", "GS", "BKNG", "RTX", "LOW", "HON", "UNP", "NKE", "DE")
    val OSLO = listOf("EQNR.OL", "DNB.OL", "NHY.OL", "TEL.OL", "MOWI.OL", "ORK.OL", "YAR.OL", "AKRBP.OL", "SALM.OL", "GJF.OL", "STB.OL",
        "KOG.OL", "TOM.OL", "SUBC.OL", "FRO.OL", "BAKKA.OL", "SCATC.OL", "NOD.OL", "TGS.OL", "AKER.OL", "AUTO.OL", "VAR.OL", "HAFNI.OL", "MPCC.OL")
}

data class ScanState(
    val universe: Universe = Universe.CORE,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val results: List<Core> = emptyList(),
    val failed: List<String> = emptyList(),
    val bullishOnly: Boolean = false,
    val at: Long = 0,
)

data class UpdateState(
    val checking: Boolean = false,
    val available: Updater.Release? = null,
    val upToDate: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val sa = app as StockApp
    val repo = sa.repo
    val state = repo.store.state

    val tab = MutableStateFlow(Tab.MARKETS)
    val selected = MutableStateFlow<String?>(null)
    val market = MutableStateFlow<Load<MarketData>>(Load.Idle)
    val cores = MutableStateFlow<Map<String, Load<Core>>>(emptyMap())
    val extras = MutableStateFlow<Map<String, Load<Extras>>>(emptyMap())
    val query = MutableStateFlow("")
    val hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val scan = MutableStateFlow(ScanState())
    val portfolio = MutableStateFlow<Load<PortfolioView>>(Load.Idle)
    val horizon = MutableStateFlow(10)
    val track = MutableStateFlow<Load<TrackView>>(Load.Idle)
    val update = MutableStateFlow(UpdateState())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var searchJob: Job? = null
    private var scanJob: Job? = null
    private val listPermits = Semaphore(5)

    init {
        refreshMarket()
        loadWatchlist()
        if (state.value.settings.autoUpdateCheck) checkUpdate(silent = true)
    }

    fun markOnboarded() { if (!state.value.onboarded) repo.store.updateAsync { it.copy(onboarded = true) } }

    fun say(msg: String) { messages.tryEmit(msg) }

    // ── Market + watchlist ───────────────────────────────────────────────────
    fun refreshMarket(force: Boolean = false) = viewModelScope.launch {
        if (market.value !is Load.Ok) market.value = Load.Loading
        market.value = runCatching { repo.market(force) }.fold({ Load.Ok(it) }, { Load.Err(it.message ?: "Market data unavailable") })
    }

    fun loadWatchlist(force: Boolean = false) {
        for (s in state.value.watchlist) load(s, force, permits = true)
    }

    fun refreshAll() {
        refreshMarket(true)
        loadWatchlist(true)
        selected.value?.let { load(it, true); loadExtras(it, true) }
    }

    fun load(symbol: String, force: Boolean = false, permits: Boolean = false) {
        val cur = cores.value[symbol]
        if (!force && (cur is Load.Ok || cur is Load.Loading)) return
        if (cur !is Load.Ok) cores.update { it + (symbol to Load.Loading) }
        viewModelScope.launch {
            val r = runCatching { if (permits) listPermits.withPermit { repo.analyze(symbol, force) } else repo.analyze(symbol, force) }
            cores.update { it + (symbol to r.fold({ Load.Ok(it) }, { e -> Load.Err(e.message ?: "Failed") })) }
        }
    }

    fun loadExtras(symbol: String, force: Boolean = false) {
        val cur = extras.value[symbol]
        if (!force && (cur is Load.Ok || cur is Load.Loading)) return
        extras.update { it + (symbol to Load.Loading) }
        viewModelScope.launch {
            val core = cores.value[symbol]?.value() ?: runCatching { repo.analyze(symbol) }.getOrNull()
            val r = if (core == null) Result.failure(IllegalStateException("No data")) else runCatching { repo.extras(core) }
            extras.update { it + (symbol to r.fold({ Load.Ok(it) }, { e -> Load.Err(e.message ?: "Failed") })) }
        }
    }

    fun open(symbol: String) {
        val s = symbol.trim().uppercase()
        if (s.isEmpty()) return
        selected.value = s
        load(s)
        loadExtras(s)
    }

    fun close() { selected.value = null }

    fun toggleWatch(symbol: String) = repo.store.updateAsync { st ->
        if (symbol in st.watchlist) st.copy(watchlist = st.watchlist - symbol) else st.copy(watchlist = st.watchlist + symbol)
    }.also { load(symbol) }

    fun moveWatch(symbol: String, up: Boolean) = repo.store.updateAsync { st ->
        val l = st.watchlist.toMutableList(); val i = l.indexOf(symbol); val j = if (up) i - 1 else i + 1
        if (i >= 0 && j in l.indices) { l[i] = l[j]; l[j] = symbol }
        st.copy(watchlist = l)
    }

    fun addSymbolsToWatchlist(text: String) {
        val syms = HoldingsText.symbols(text)
        repo.store.updateAsync { st -> st.copy(watchlist = (st.watchlist + syms).distinct()) }
        syms.forEach { load(it) }
    }

    // ── Search ───────────────────────────────────────────────────────────────
    fun search(q: String) {
        query.value = q
        searchJob?.cancel()
        if (q.isBlank()) { hits.value = emptyList(); return }
        searchJob = viewModelScope.launch {
            delay(300)
            hits.value = runCatching { Yahoo.search(q.trim()).first }.getOrDefault(emptyList())
        }
    }

    // ── Portfolio ────────────────────────────────────────────────────────────
    fun loadPortfolio(force: Boolean = false) {
        if (!force && portfolio.value is Load.Ok && (portfolio.value as Load.Ok).v.horizon == horizon.value) return
        if (portfolio.value !is Load.Ok) portfolio.value = Load.Loading
        viewModelScope.launch {
            portfolio.value = runCatching { repo.portfolio(horizon.value) }.fold({ Load.Ok(it) }, { Load.Err(it.message ?: "Failed") })
        }
    }

    fun setHorizon(d: Int) { horizon.value = d; loadPortfolio(true) }

    fun upsertHolding(symbol: String, shares: Double, avgCost: Double?, replace: Boolean) {
        val sym = symbol.trim().uppercase()
        viewModelScope.launch {
            repo.store.update { st ->
                val existing = st.holdings.firstOrNull { it.symbol == sym }
                val next = when {
                    existing == null -> st.holdings + Holding(sym, shares, avgCost)
                    replace -> st.holdings.map { if (it.symbol == sym) it.copy(shares = shares, avgCost = avgCost) else it }
                    else -> st.holdings.map {
                        if (it.symbol != sym) it else {
                            val total = it.shares + shares
                            val cost = if (it.avgCost != null && avgCost != null) (it.avgCost * it.shares + avgCost * shares) / total else avgCost ?: it.avgCost
                            it.copy(shares = total, avgCost = cost)
                        }
                    }
                }
                st.copy(holdings = next)
            }
            loadPortfolio(true)
        }
    }

    fun removeHolding(symbol: String) = viewModelScope.launch {
        repo.store.update { st -> st.copy(holdings = st.holdings.filter { it.symbol != symbol }) }
        loadPortfolio(true)
    }

    fun importHoldings(text: String, replaceAll: Boolean): String {
        val (ok, bad) = HoldingsText.parse(text)
        if (ok.isEmpty()) return "Nothing recognised. Use lines like: AAPL 10 @ 182.5"
        viewModelScope.launch {
            repo.store.update { st ->
                val base = if (replaceAll) emptyList() else st.holdings.filter { h -> ok.none { it.symbol == h.symbol } }
                st.copy(holdings = base + ok.map { Holding(it.symbol, it.shares, it.avgCost) })
            }
            loadPortfolio(true)
        }
        return "Imported ${ok.size} holding${if (ok.size == 1) "" else "s"}" + if (bad.isNotEmpty()) "; skipped: ${bad.joinToString(", ").take(120)}" else ""
    }

    // ── Scanner ──────────────────────────────────────────────────────────────
    fun setUniverse(u: Universe) { scan.update { it.copy(universe = u) } }
    fun setBullishOnly(b: Boolean) { scan.update { it.copy(bullishOnly = b) } }

    fun runScan() {
        scanJob?.cancel()
        val u = scan.value.universe
        val syms = when (u) {
            Universe.CORE -> Universes.CORE
            Universe.US_LARGE -> Universes.US_LARGE
            Universe.OSLO -> Universes.OSLO
            Universe.MINE -> (state.value.watchlist + state.value.holdings.map { it.symbol }).distinct()
        }
        scan.update { it.copy(running = true, done = 0, total = syms.size, results = emptyList(), failed = emptyList()) }
        scanJob = viewModelScope.launch {
            val res = repo.scan(syms) { d, t -> scan.update { it.copy(done = d, total = t) } }
            val ok = res.mapNotNull { it.getOrNull() }
            ok.forEach { c -> cores.update { m -> m + (c.symbol to Load.Ok(c)) } }
            val failed = syms.filterIndexed { i, _ -> res[i].isFailure }
            scan.update { it.copy(running = false, results = ok.sortedByDescending { c -> c.p10 ?: c.analysis.rules.rawScore / 10 }, failed = failed, at = System.currentTimeMillis()) }
        }
    }

    // ── Alerts / track record ────────────────────────────────────────────────
    fun markAlertsRead() = repo.store.updateAsync { st -> st.copy(alerts = st.alerts.map { it.copy(read = true) }) }
    fun clearAlerts() = repo.store.updateAsync { st -> st.copy(alerts = emptyList()) }

    fun loadTrack() {
        track.value = Load.Loading
        viewModelScope.launch { track.value = runCatching { repo.trackRecord() }.fold({ Load.Ok(it) }, { Load.Err(it.message ?: "Failed") }) }
    }

    fun runWatcherNow() {
        androidx.work.WorkManager.getInstance(getApplication()).enqueue(androidx.work.OneTimeWorkRequestBuilder<AlertWorker>().build())
        say("Checking your portfolio and watchlist in the background…")
    }

    // ── Settings ─────────────────────────────────────────────────────────────
    fun settings(fn: (Settings) -> Settings) = viewModelScope.launch {
        val before = state.value.settings
        val after = repo.store.update { it.copy(settings = fn(it.settings)) }.settings
        if (before.alertsEnabled != after.alertsEnabled || before.alertIntervalMin != after.alertIntervalMin)
            AlertWorker.schedule(getApplication(), after.alertsEnabled, after.alertIntervalMin)
        if (before.baseCurrency != after.baseCurrency) loadPortfolio(true)
    }

    // ── Updates ──────────────────────────────────────────────────────────────
    fun checkUpdate(silent: Boolean = false) {
        update.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            val r = runCatching { Updater.check() }
            update.update { it.copy(checking = false, available = r.getOrNull(), upToDate = r.isSuccess && r.getOrNull() == null,
                error = if (silent) null else r.exceptionOrNull()?.message) }
        }
    }

    fun installUpdate() {
        val rel = update.value.available ?: return
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            Updater.openInstallPermission(ctx)
            say("Allow StockAnalyzer to install updates, then tap Install again.")
            return
        }
        viewModelScope.launch {
            update.update { it.copy(progress = 0f, error = null) }
            val r = runCatching { Updater.download(ctx, rel) { p -> update.update { s -> s.copy(progress = p) } } }
            update.update { it.copy(progress = null, error = r.exceptionOrNull()?.message) }
            r.getOrNull()?.let { Updater.install(ctx, it) }
        }
    }
}
