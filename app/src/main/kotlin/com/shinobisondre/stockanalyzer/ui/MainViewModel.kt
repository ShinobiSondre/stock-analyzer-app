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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ok<T>(val v: T) : Load<T>
    data class Err(val msg: String) : Load<Nothing>
}

fun <T> Load<T>.value(): T? = (this as? Load.Ok<T>)?.v

enum class Tab(val label: String, val inBottomBar: Boolean = true) {
    MARKETS("Home"), QUICK("Quick wins"), RIPPLE("Ripples"), PORTFOLIO("Portfolio"), TYLER("Tyler"),
    // Reached from the home page on the narrow screen; in the side rail when unfolded.
    SCANNER("Scanner", false), ALERTS("Alerts", false), SETTINGS("Settings", false),
}

data class TylerState(
    val videos: List<com.shinobisondre.stockanalyzer.engine.YtVideo> = emptyList(),
    val takes: Map<String, com.shinobisondre.stockanalyzer.engine.TylerTake> = emptyMap(),
    /** videoId → what's happening right now ("Getting the transcript…"). */
    val working: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val at: Long = 0,
) {
    /** Newest first, summaries only. */
    val ordered get() = takes.values.sortedByDescending { it.published }
}

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

data class QuickState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val items: List<com.shinobisondre.stockanalyzer.data.QuickWin> = emptyList(),
    val at: Long = 0,
    val includeMarket: Boolean = true,
    val minMove: Double = 3.0,
)

data class ShotImport(
    val busy: Boolean = true,
    val stage: String = "Reading screenshot…",
    val candidates: List<com.shinobisondre.stockanalyzer.data.ImportCandidate> = emptyList(),
    val error: String? = null,
    val replaceAll: Boolean = false,
)

data class UpdateState(
    val checking: Boolean = false,
    val available: Updater.Release? = null,
    val upToDate: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
    /** Downloaded APK waiting to be installed. */
    val readyPath: String? = null,
    val installing: Boolean = false,
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
    val quick = MutableStateFlow(QuickState())
    val shot = MutableStateFlow<ShotImport?>(null)
    /** Today's "quick win" pop-up (shown at most once a day per stock). */
    val splash = MutableStateFlow<List<com.shinobisondre.stockanalyzer.data.QuickWin>?>(null)
    private var quickJob: Job? = null
    private var shotJob: Job? = null
    val tyler = MutableStateFlow(TylerState())
    private var tylerJob: Job? = null
    /** Ripple tree: the stock in the middle and its loaded tree. */
    val rippleRoot = MutableStateFlow(state.value.holdings.firstOrNull()?.symbol?.takeIf { com.shinobisondre.stockanalyzer.engine.Ripple.nodesFor(it) != null } ?: "NVDA")
    val ripple = MutableStateFlow<Load<com.shinobisondre.stockanalyzer.data.Repository.RippleView>>(Load.Idle)
    private var rippleJob: Job? = null
    /** Tyler video being watched in the app (null = none), and whether it fills the whole screen. */
    val watching = MutableStateFlow<String?>(null)
    val videoFull = MutableStateFlow(false)
    /** Where each video was left off, so switching to/from full screen continues from the same second. */
    val videoPos = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastAutoUpdate = 0L
    private var backgroundInstall: Job? = null

    private var searchJob: Job? = null
    private var scanJob: Job? = null
    private val listPermits = Semaphore(5)

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) { repo.model } // parse model.json off the main thread
        refreshMarket()
        loadWatchlist()
        // Holdings too, so the Markets banner can flag a big mover you own.
        state.value.holdings.forEach { load(it.symbol, permits = true) }
        loadTylerCache()
        autoUpdate()
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
        watching.value = null; videoFull.value = false
        val s = symbol.trim().uppercase()
        if (s.isEmpty()) return
        selected.value = s
        load(s)
        loadExtras(s)
    }

    fun close() { selected.value = null }

    fun watch(videoId: String) { selected.value = null; videoFull.value = false; watching.value = videoId }
    fun stopWatching() { videoFull.value = false; watching.value = null }

    /** YouTube's own player handed us its caption file: if that video was summarised without a transcript, redo it. */
    fun onCaptionsCaptured(videoId: String) {
        val tk = tyler.value.takes[videoId]
        if (tk == null || tk.words == 0) resummarise(videoId)
    }

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
        val (parsed, bad) = HoldingsText.parse(text)
        if (parsed.isEmpty()) return "Nothing recognised. Use lines like: AAPL 10 @ 182.5"
        // The same ticker twice is merged (shares summed, cost share-weighted) — duplicate keys would crash the list.
        val ok = parsed.groupBy { it.symbol }.map { (sym, list) ->
            val shares = list.sumOf { it.shares }
            val costed = list.filter { it.avgCost != null }
            val cost = if (costed.isEmpty()) null else costed.sumOf { it.avgCost!! * it.shares } / costed.sumOf { it.shares }
            HoldingsText.Parsed(sym, shares, cost)
        }
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

    // ── Ripple tree ──────────────────────────────────────────────────────────
    fun loadRipple(root: String = rippleRoot.value, force: Boolean = false) {
        val sym = root.trim().uppercase()
        if (sym.isEmpty()) return
        val cur = ripple.value
        if (!force && sym == rippleRoot.value && cur is Load.Ok && cur.v.root == sym && System.currentTimeMillis() - cur.v.at < 10 * 60_000) return
        rippleRoot.value = sym
        rippleJob?.cancel()
        ripple.value = Load.Loading
        load(sym)
        rippleJob = viewModelScope.launch {
            val r = runCatching { repo.ripple(sym) }
            ripple.value = r.fold({ if (it.children.isEmpty()) Load.Err("No connected stocks found for $sym.") else Load.Ok(it) }, { e -> Load.Err(e.message ?: "Couldn't load $sym") })
        }
    }

    /** From a stock page or a tree node: make [symbol] the middle of the ripple tree. */
    fun openRipple(symbol: String) {
        selected.value = null; watching.value = null
        tab.value = Tab.RIPPLE
        loadRipple(symbol)
    }

    // ── Tyler Hill ───────────────────────────────────────────────────────────
    private fun loadTylerCache() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val c = repo.tylerCache()
            tyler.update { it.copy(videos = it.videos.ifEmpty { c.videos }, takes = c.takes + it.takes) }
        }
    }

    /** Fetch his latest videos and summarise the newest ones that aren't summarised yet (one at a time — free AI is rate-limited). */
    fun loadTyler(force: Boolean = false) {
        val t = tyler.value
        if (t.loading || tylerJob?.isActive == true || (!force && t.at > 0 && System.currentTimeMillis() - t.at < 20 * 60_000)) return
        tyler.update { it.copy(loading = true, error = null) }
        tylerJob = viewModelScope.launch {
            val vids = runCatching { repo.tylerVideos() }
            vids.onFailure { e -> tyler.update { it.copy(loading = false, error = "Couldn't reach YouTube: ${e.message}") } }
            val list = vids.getOrNull() ?: return@launch
            tyler.update { it.copy(videos = list, loading = false, at = System.currentTimeMillis()) }
            // Newest 3: captions, else listen on the phone. Older ones: captions only (tap to listen).
            list.take(6).forEachIndexed { i, v ->
                val have = tyler.value.takes[v.id]
                if (have != null && have.aiMade && have.words > 0) return@forEachIndexed
                if (have != null && i >= 3) return@forEachIndexed
                summariseTyler(v, allowAsr = i < 3)
            }
        }
    }

    private suspend fun summariseTyler(v: com.shinobisondre.stockanalyzer.engine.YtVideo, allowAsr: Boolean = true) {
        tyler.update { it.copy(working = it.working + (v.id to "Starting…")) }
        com.shinobisondre.stockanalyzer.video.Transcripts.retry(v.id)
        val take = runCatching { repo.tylerSummarize(v, allowAsr) { stage -> tyler.update { s -> s.copy(working = s.working + (v.id to stage)) } } }.getOrNull()
        tyler.update { s -> s.copy(working = s.working - v.id, takes = if (take != null) s.takes + (v.id to take) else s.takes) }
    }

    fun resummarise(videoId: String) {
        val v = tyler.value.videos.firstOrNull { it.id == videoId } ?: tyler.value.takes[videoId]?.let {
            com.shinobisondre.stockanalyzer.engine.YtVideo(it.videoId, it.title, it.published)
        } ?: return
        if (videoId in tyler.value.working) return
        viewModelScope.launch { summariseTyler(v) }
    }

    // ── Quick wins ───────────────────────────────────────────────────────────
    fun loadQuick(force: Boolean = false) {
        val q = quick.value
        if (q.running || (!force && q.at > 0 && System.currentTimeMillis() - q.at < 5 * 60_000)) return
        quickJob?.cancel()
        quick.update { it.copy(running = true, done = 0, total = 0) }
        quickJob = viewModelScope.launch {
            val market = if (quick.value.includeMarket) (Universes.CORE + Universes.US_LARGE + Universes.OSLO).distinct() else emptyList()
            val items = runCatching { repo.quickWins(market, quick.value.minMove) { d, t -> quick.update { it.copy(done = d, total = t) } } }.getOrDefault(emptyList())
            quick.update { it.copy(running = false, items = items, at = System.currentTimeMillis()) }
        }
    }

    fun setQuickMarket(include: Boolean) { quick.update { it.copy(includeMarket = include, at = 0) }; loadQuick(true) }

    // ── Screenshot import ────────────────────────────────────────────────────
    fun importScreenshots(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        shot.value = ShotImport(stage = "Reading ${if (uris.size == 1) "screenshot" else "${uris.size} screenshots"}…")
        shotJob?.cancel()
        shotJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val parsed = mutableListOf<com.shinobisondre.stockanalyzer.engine.ParsedHolding>()
            for (u in uris) {
                val lines = runCatching { com.shinobisondre.stockanalyzer.data.Ocr.lines(ctx, u) }.getOrElse {
                    shot.value = ShotImport(busy = false, error = "Couldn't read the image: ${it.message}"); return@launch
                }
                for (h in com.shinobisondre.stockanalyzer.engine.BrokerScreenshot.parse(lines)) if (parsed.none { it.name.equals(h.name, true) }) parsed += h
            }
            if (parsed.isEmpty()) {
                shot.value = ShotImport(busy = false, error = "No holdings found. Use the Holdings list in the Nordnet app (name, value and latest price visible) — scroll and add more screenshots for long portfolios.")
                return@launch
            }
            shot.value = ShotImport(stage = "Matching ${parsed.size} holdings to tickers…")
            val base = state.value.settings.baseCurrency
            val cands = parsed.map { p -> async { repo.resolveImport(p, base) } }.map { it.await() }
            shot.value = ShotImport(busy = false, stage = "review", candidates = cands)
        }
    }

    fun editCandidate(i: Int, fn: (com.shinobisondre.stockanalyzer.data.ImportCandidate) -> com.shinobisondre.stockanalyzer.data.ImportCandidate) {
        shot.update { s -> s?.copy(candidates = s.candidates.mapIndexed { k, c -> if (k == i) fn(c) else c }) }
    }

    fun setShotReplaceAll(v: Boolean) { shot.update { it?.copy(replaceAll = v) } }
    fun dismissShot() { shotJob?.cancel(); shot.value = null }

    fun confirmShot() {
        val s = shot.value ?: return
        val chosen = s.candidates.filter { it.selected }.mapNotNull { c ->
            val sym = c.symbol.trim().uppercase()
            val sh = c.sharesText.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()
            val cost = c.costText.trim().replace(" ", "").let { t -> if (t.isEmpty()) null else t.replace(',', '.').toDoubleOrNull() }
            if (sym.isEmpty() || sh == null || sh <= 0) null else Holding(sym, sh, cost)
        }.distinctBy { it.symbol }
        if (chosen.isEmpty()) { say("Nothing selected to import"); return }
        viewModelScope.launch {
            repo.store.update { st ->
                val keep = if (s.replaceAll) emptyList() else st.holdings.filter { h -> chosen.none { it.symbol == h.symbol } }
                st.copy(holdings = keep + chosen)
            }
            shot.value = null
            chosen.forEach { load(it.symbol, permits = true) }
            loadPortfolio(true)
            say("Imported ${chosen.size} holding${if (chosen.size == 1) "" else "s"} from the screenshot")
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

    /**
     * Home page: check GitHub for a newer build and download it in the background.
     * It installs itself when you leave the app (see [scheduleBackgroundInstall]),
     * or right away from the banner's "Install now".
     */
    fun autoUpdate() {
        if (!state.value.settings.autoUpdateCheck) return
        val now = System.currentTimeMillis()
        if (now - lastAutoUpdate < 10 * 60_000 || update.value.progress != null || update.value.installing) return
        lastAutoUpdate = now
        viewModelScope.launch {
            val rel = runCatching { Updater.check() }.getOrNull() ?: return@launch
            update.update { it.copy(available = rel, upToDate = false) }
            download(rel)
        }
    }

    private suspend fun download(rel: Updater.Release): java.io.File? {
        val ctx = getApplication<Application>()
        Updater.downloaded(ctx, rel)?.let { f -> update.update { it.copy(readyPath = f.path) }; return f }
        update.update { it.copy(progress = 0f, error = null) }
        val r = runCatching { Updater.download(ctx, rel) { p -> update.update { s -> s.copy(progress = p) } } }
        update.update { it.copy(progress = null, error = r.exceptionOrNull()?.message, readyPath = r.getOrNull()?.path) }
        return r.getOrNull()
    }

    fun installUpdate() {
        val rel = update.value.available ?: return
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            Updater.openInstallPermission(ctx)
            say("Allow StockAnalyzer to install its own updates — after that they install automatically.")
            return
        }
        viewModelScope.launch {
            val f = download(rel) ?: return@launch
            update.update { it.copy(installing = true) }
            val r = runCatching { withContext(kotlinx.coroutines.Dispatchers.IO) { Updater.installSession(ctx, f, rel.versionName) } }
            if (r.isFailure) {
                update.update { it.copy(installing = false) }
                runCatching { Updater.install(ctx, f) } // fall back to the system installer screen
            }
        }
    }

    /** App left (screen off, home button, another app): apply a downloaded update ~15 s later, unless the user comes back. */
    fun scheduleBackgroundInstall() {
        val rel = update.value.available ?: return
        val path = update.value.readyPath ?: return
        val ctx = getApplication<Application>()
        if (!state.value.settings.autoUpdateCheck || !Updater.canInstall(ctx) || Updater.holdInstall) return
        backgroundInstall?.cancel()
        backgroundInstall = viewModelScope.launch {
            delay(15_000)
            if (Updater.foreground || Updater.holdInstall) return@launch
            update.update { it.copy(installing = true) }
            runCatching { withContext(kotlinx.coroutines.Dispatchers.IO) { Updater.installSession(ctx, java.io.File(path), rel.versionName) } }
                .onFailure { update.update { s -> s.copy(installing = false) } }
        }
    }

    fun cancelBackgroundInstall() { backgroundInstall?.cancel(); backgroundInstall = null }

    // ── Quick-win banner / pop-up (from data already loaded for the watchlist and holdings) ──
    fun moversFromCores(map: Map<String, Load<Core>>): List<com.shinobisondre.stockanalyzer.data.QuickWin> {
        val st = state.value
        val owned = st.holdings.map { it.symbol }.toSet(); val watched = st.watchlist.toSet()
        return map.mapNotNull { (sym, l) ->
            val c = (l as? Load.Ok)?.v ?: return@mapNotNull null
            if ((c.changePct ?: 0.0) < 3.0 || (sym !in owned && sym !in watched)) return@mapNotNull null
            repo.quickFromChart(sym, c.chart, sym in owned, sym in watched)
        }.sortedWith(compareByDescending<com.shinobisondre.stockanalyzer.data.QuickWin> { it.owned }.thenByDescending { it.changePct })
    }

    fun maybeSplash(movers: List<com.shinobisondre.stockanalyzer.data.QuickWin>) {
        val worth = movers.filter { (it.owned && it.changePct >= 3.0) || (it.watched && it.changePct >= 5.0) }
        if (worth.isEmpty() || splash.value != null) return
        val prefs = getApplication<Application>().getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
        val day = java.time.LocalDate.now().toString()
        val seen = prefs.getString("splash", "")!!.split(',').filter { it.startsWith("$day:") }.map { it.substringAfter(':') }.toSet()
        val fresh = worth.filter { it.symbol !in seen }
        if (fresh.isEmpty()) return
        prefs.edit().putString("splash", (seen + fresh.map { it.symbol }).joinToString(",") { "$day:$it" }).apply()
        splash.value = fresh.take(3)
    }

    fun dismissSplash() { splash.value = null }
}
