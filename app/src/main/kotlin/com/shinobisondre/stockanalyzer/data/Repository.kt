package com.shinobisondre.stockanalyzer.data

import android.content.Context
import com.shinobisondre.stockanalyzer.engine.Analysis
import com.shinobisondre.stockanalyzer.engine.BrokerScreenshot
import com.shinobisondre.stockanalyzer.engine.DayAfter
import com.shinobisondre.stockanalyzer.engine.DayAfterBook
import com.shinobisondre.stockanalyzer.engine.EodAdvice
import com.shinobisondre.stockanalyzer.engine.ParsedHolding
import com.shinobisondre.stockanalyzer.engine.Analyzer
import com.shinobisondre.stockanalyzer.engine.GradedCall
import com.shinobisondre.stockanalyzer.engine.HorizonForecast
import com.shinobisondre.stockanalyzer.engine.LoggedCall
import com.shinobisondre.stockanalyzer.engine.MarketPoint
import com.shinobisondre.stockanalyzer.engine.MarketSeries
import com.shinobisondre.stockanalyzer.engine.MiniJson
import com.shinobisondre.stockanalyzer.engine.MiniJson.num
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import com.shinobisondre.stockanalyzer.engine.MiniJson.str
import com.shinobisondre.stockanalyzer.engine.ModelBundle
import com.shinobisondre.stockanalyzer.engine.Money
import com.shinobisondre.stockanalyzer.engine.Portfolio
import com.shinobisondre.stockanalyzer.engine.PortfolioTotals
import com.shinobisondre.stockanalyzer.engine.PositionInput
import com.shinobisondre.stockanalyzer.engine.PositionValue
import com.shinobisondre.stockanalyzer.engine.Quality
import com.shinobisondre.stockanalyzer.engine.QualityScore
import com.shinobisondre.stockanalyzer.engine.Series
import com.shinobisondre.stockanalyzer.engine.TrackRecord
import com.shinobisondre.stockanalyzer.engine.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class MarketData(
    val series: MarketSeries,
    val point: MarketPoint?,
    val spy: ChartResult,
    val vix: ChartResult?,
    val fearGreed: Int?,
    val fearGreedLabel: String?,
    val at: Long,
)

/** Chart + engine output for one symbol. */
class Core(val symbol: String, val chart: ChartResult, val analysis: Analysis) {
    val name get() = chart.meta.name
    val price get() = chart.price
    val changePct get() = chart.changePct
    val change get() = chart.change
    val currency get() = chart.meta.currency ?: "USD"
    val p10 get() = analysis.forecast(10)?.probability
    val isUsListing get() = !symbol.contains('.') || symbol.endsWith(".US")
}

class Extras(val summary: SummaryData?, val quality: QualityScore?, val quote: QuoteExtra?, val news: NewsDigest?)

class HoldingView(val holding: Holding, val core: Core?, val value: PositionValue, val forecast: HorizonForecast?, val error: String?)

class PortfolioView(
    val base: String,
    val horizon: Int,
    val rows: List<HoldingView>,
    val totals: PortfolioTotals,
    val stance: String,
    val stanceNote: String,
    val at: Long,
)

class TrackView(val summary: TrackSummary, val recent: List<GradedCall>, val symbolCount: Int)

/** One row of a screenshot import, editable by the user before it is saved. */
data class ImportCandidate(
    val parsed: ParsedHolding,
    val symbol: String,
    val resolvedName: String?,
    val sharesText: String,
    val costText: String,
    val note: String,
    val selected: Boolean,
)

class QuickWin(
    val symbol: String,
    val name: String,
    val changePct: Double,
    val price: Double?,
    val currency: String,
    val rvol: Double?,
    val clv: Double?,
    val above200: Boolean?,
    val fromHighPct: Double?,
    val advice: EodAdvice?,
    val owned: Boolean,
    val watched: Boolean,
    val sessionOpen: Boolean,
    val minutesToClose: Long?,
    val closeLocal: String?,
    val costPct: Double,
    val intraday: List<Double>,
    val vwap: Double?,
)

/** Plain number formatting for notes (no Compose here). */
object F2 {
    fun n(v: Double?): String = if (v == null) "—" else if (kotlin.math.abs(v) >= 1000) "%,.0f".format(java.util.Locale.US, v) else "%.2f".format(java.util.Locale.US, v)
    fun plain(v: Double): String = "%.2f".format(java.util.Locale.US, v)
    fun qty(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.4f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
}

class Repository(private val context: Context, val store: Store) {

    val model: ModelBundle? by lazy {
        runCatching {
            context.assets.open("model.json").bufferedReader().use { ModelBundle.fromJson(it.readText()) }
        }.getOrNull()?.takeIf { it.compatible }
    }

    val dayAfter: DayAfterBook? by lazy {
        runCatching { context.assets.open("dayafter.json").bufferedReader().use { DayAfterBook.fromJson(it.readText()) } }.getOrNull()
    }

    private val charts = ConcurrentHashMap<String, Pair<Long, ChartResult>>()
    private val fx = ConcurrentHashMap<String, Pair<Long, Double>>()
    private val marketLock = Mutex()
    @Volatile private var market: MarketData? = null
    private val net = Semaphore(6)

    suspend fun chart(symbol: String, maxAgeMs: Long = 4 * 60_000L, range: String = "2y", interval: String = "1d"): ChartResult {
        val key = "$symbol|$range|$interval"
        charts[key]?.let { (t, c) -> if (System.currentTimeMillis() - t < maxAgeMs) return c }
        val c = net.withPermit { Yahoo.chart(symbol, range, interval) }
        charts[key] = System.currentTimeMillis() to c
        return c
    }

    suspend fun market(force: Boolean = false): MarketData = marketLock.withLock {
        market?.let { if (!force && System.currentTimeMillis() - it.at < 10 * 60_000L) return it }
        coroutineScope {
            val spy = async { chart("SPY", if (force) 0L else 10 * 60_000L) }
            val vix = async { runCatching { chart("^VIX", if (force) 0L else 10 * 60_000L) }.getOrNull() }
            val fg = async { runCatching { fearGreed() }.getOrNull() }
            val s = spy.await(); val v = vix.await()
            val series = withContext(Dispatchers.Default) { MarketSeries(s.candles, v?.candles ?: emptyList()) }
            MarketData(series, series.latest(), s, v, fg.await()?.first, fg.await()?.second, System.currentTimeMillis()).also { market = it }
        }
    }

    private suspend fun fearGreed(): Pair<Int, String>? {
        val body = Http.get("https://production.dataviz.cnn.io/index/fearandgreed/graphdata")
        val fg = MiniJson.parse(body).obj()["fear_and_greed"].obj()
        val score = fg["score"].num()?.toInt() ?: return null
        return score to (fg["rating"].str()?.replaceFirstChar { it.uppercase() } ?: "")
    }

    /** Full engine run for one symbol. Also logs the call (completed bars only) for the track record. */
    suspend fun analyze(symbol: String, force: Boolean = false): Core {
        val sym = symbol.trim().uppercase()
        val (c, m) = coroutineScope {
            val c = async { chart(sym, if (force) 0L else 4 * 60_000L) }
            val m = async { runCatching { market() }.getOrNull() }
            c.await() to m.await()
        }
        if (c.candles.size < 30) throw IllegalStateException("Not enough price history for $sym (${c.candles.size} days)")
        val a = withContext(Dispatchers.Default) { Analyzer.analyze(sym, c.candles, m?.series, model) }
            ?: throw IllegalStateException("Analysis failed for $sym")
        val core = Core(sym, c, a)
        logCall(core, m)
        return core
    }

    private suspend fun logCall(core: Core, m: MarketData?) {
        if (model == null) return
        val completed = core.chart.completedCandles()
        val a = if (completed.size == core.chart.candles.size) core.analysis
        else withContext(Dispatchers.Default) { Analyzer.analyze(core.symbol, completed, m?.series, model) } ?: return
        val p10 = a.forecast(10)?.probability ?: return
        val entry = LoggedCall(core.symbol, a.lastDate, a.lastClose, a.verdict.call.text, a.forecast(5)?.probability, p10,
            a.forecast(22)?.probability, a.forecast(10)?.baseRate, a.modelVersion)
        store.updateAsync { s ->
            if (s.calls.any { it.symbol == entry.symbol && it.date == entry.date }) s
            else s.copy(calls = (s.calls + entry).takeLast(Store.MAX_CALLS))
        }
    }

    suspend fun extras(core: Core): Extras = coroutineScope {
        val sum = async { runCatching { net.withPermit { Yahoo.summary(core.symbol) } }.getOrNull() }
        val quote = async { runCatching { Yahoo.quotes(listOf(core.symbol))[core.symbol] }.getOrNull() }
        val yNews = async { runCatching { Yahoo.search(core.symbol, news = 10, quotes = 0).second }.getOrDefault(emptyList()) }
        val gNews = async {
            runCatching {
                val q = if (core.isUsListing) "${core.symbol} stock" else core.name.split(' ').take(2).joinToString(" ") + " aksje"
                News.google(q)
            }.getOrDefault(emptyList())
        }
        val s = sum.await()
        val quality = s?.fundamentals?.let { f -> Quality.score(f.copy(price = f.price ?: core.price)) }
        val news = News.digest(core.symbol, core.name, yNews.await(), gNews.await(), core.changePct)
        Extras(s, quality, quote.await(), news)
    }

    suspend fun fxRate(fromRaw: String?, to: String): Double? {
        val (from, _) = Money.normalize(fromRaw)
        if (from == to) return 1.0
        val key = "$from$to"
        fx[key]?.let { (t, v) -> if (System.currentTimeMillis() - t < 30 * 60_000L) return v }
        val rate = runCatching { chart("$from$to=X", 30 * 60_000L, "5d").price }.getOrNull()
            ?: runCatching { chart("$to$from=X", 30 * 60_000L, "5d").price?.let { 1 / it } }.getOrNull()
        if (rate != null && rate > 0) fx[key] = System.currentTimeMillis() to rate
        return rate
    }

    suspend fun scan(symbols: List<String>, onProgress: (Int, Int) -> Unit): List<Result<Core>> = coroutineScope {
        var done = 0
        val lock = Mutex()
        symbols.map { s ->
            async {
                val r = runCatching { analyze(s) }
                lock.withLock { done++; onProgress(done, symbols.size) }
                r
            }
        }.map { it.await() }
    }

    suspend fun portfolio(horizon: Int): PortfolioView = coroutineScope {
        val st = store.value
        val base = st.settings.baseCurrency
        val rows = st.holdings.map { h ->
            async {
                val core = runCatching { analyze(h.symbol) }
                val c = core.getOrNull()
                val rate = c?.let { fxRate(it.currency, base) }
                val sector = runCatching { if (c != null) sectorOf(c.symbol) else null }.getOrNull()
                val input = PositionInput(h.symbol, h.shares, h.avgCost, c?.price, c?.chart?.prevClose, c?.currency, rate, sector)
                HoldingView(h, c, Portfolio.value(input), c?.analysis?.forecast(horizon),
                    core.exceptionOrNull()?.message ?: if (c != null && rate == null) "No ${Money.normalize(c.currency).first}→$base rate" else null)
            }
        }.map { it.await() }
        val totals = Portfolio.totals(rows.map { it.value })
        var bull = 0.0; var bear = 0.0
        for (r in rows) {
            val v = r.value.valueBase ?: continue
            val call = r.forecast?.call ?: continue
            if (call.bullish) bull += v else if (call.bearish) bear += v
        }
        val tv = totals.valueBase
        val (stance, note) = when {
            tv <= 0 -> "—" to "No live valuations yet."
            bear / tv > 0.5 -> "Defensive" to "Over half of the portfolio's value has below-average odds at this horizon."
            bull / tv > 0.5 -> "Constructive" to "Over half of the portfolio's value has above-average odds at this horizon."
            totals.concentrated.isNotEmpty() -> "Concentrated" to "More than 40% of value sits in ${totals.concentrated.joinToString { it.name }}."
            else -> "Balanced" to "No urgent calls — most holdings show no meaningful edge either way."
        }
        PortfolioView(base, horizon, rows, totals, stance, note, System.currentTimeMillis())
    }

    private val sectors = ConcurrentHashMap<String, String>()
    private suspend fun sectorOf(symbol: String): String? {
        sectors[symbol]?.let { return it }
        val s = Yahoo.summary(symbol)?.fundamentals?.sector ?: return null
        sectors[symbol] = s
        return s
    }

    // ── Screenshot import ────────────────────────────────────────────────────
    private fun suffixScore(sym: String, ccy: String?): Int = when (ccy?.uppercase()) {
        "USD" -> if (!sym.contains('.')) 0 else 2
        "NOK" -> if (sym.endsWith(".OL")) 0 else 2
        "SEK" -> if (sym.endsWith(".ST")) 0 else 2
        "DKK" -> if (sym.endsWith(".CO")) 0 else 2
        "EUR" -> if (listOf(".DE", ".HE", ".PA", ".AS", ".MI", ".MC", ".F", ".BR", ".LS").any { sym.endsWith(it) }) 0 else 2
        "GBP", "GBX" -> if (sym.endsWith(".L")) 0 else 2
        else -> 1
    }

    /**
     * Finds the ticker for a holding read off a screenshot. Candidates are ranked by
     * listing (a USD price → US listing, NOK → Oslo …) and confirmed by checking
     * that the live price is close to the "Latest" price in the screenshot.
     */
    suspend fun resolveImport(p: ParsedHolding, base: String): ImportCandidate {
        val prefCcy = p.lastCurrency
        var hits = emptyList<SearchHit>()
        for (q in BrokerScreenshot.searchQueries(p.name)) {
            hits = runCatching { Yahoo.search(q).first }.getOrDefault(emptyList()).filter { it.type in setOf("EQUITY", "ETF", "MUTUALFUND") }
            if (hits.isNotEmpty()) break
        }
        val ranked = hits.withIndex().sortedWith(compareBy({ suffixScore(it.value.symbol, prefCcy) }, { it.index })).map { it.value }
        var chosen: SearchHit? = null
        var found: ChartResult? = null
        for (h in ranked.take(4)) {
            val c = runCatching { chart(h.symbol, 30 * 60_000L, "5d") }.getOrNull() ?: continue
            val price = c.price ?: continue
            val lp = p.lastPrice
            val sameCcy = prefCcy == null || Money.normalize(c.meta.currency).first == Money.normalize(prefCcy).first
            val ratio = if (lp != null && lp > 0) price * Money.normalize(c.meta.currency).second / (lp * Money.normalize(prefCcy).second) else 1.0
            if (lp == null || (sameCcy && ratio in 0.85..1.18)) { chosen = h; found = c; break }
            if (chosen == null) { chosen = h; found = c } // keep the best-ranked as a fallback
        }
        // "kr" is read as NOK; a Swedish or Danish Nordnet account means SEK / DKK.
        val valueCcy = if (p.valueCurrency == "NOK" && base in setOf("SEK", "DKK")) base else p.valueCurrency ?: base
        val lastCcy = p.lastCurrency ?: found?.meta?.currency
        val fx = if (lastCcy == null) null else fxRate(lastCcy, Money.normalize(valueCcy).first)
        var shares = p.shares(fx)
        var how = when {
            p.quantity != null -> "quantity read from the screenshot"
            shares != null && fx != null -> "${F2.n(p.marketValue)} ${Money.normalize(valueCcy).first} ÷ (${F2.n(p.lastPrice)} $lastCcy" + (if (fx != 1.0) " × ${F2.n(fx)}" else "") + ")"
            else -> ""
        }
        val live = found
        if (shares == null && p.marketValue != null && live?.price != null) {
            val c = live
            val fx2 = fxRate(c.meta.currency, Money.normalize(valueCcy).first)
            if (fx2 != null) {
                val synthetic = p.copy(lastPrice = c.price!! * Money.normalize(c.meta.currency).second, lastCurrency = Money.normalize(c.meta.currency).first)
                shares = synthetic.shares(fx2)
                how = "value ÷ live price"
            }
        }
        val cost = p.impliedAvgCost()
        val costHow = if (p.avgCost == null && cost != null) " · avg cost from return ${F2.n(p.returnPct)}%" else ""
        val note = when {
            chosen == null -> "No ticker found — type it in."
            shares == null -> "Couldn't work out the number of shares — type it in."
            else -> "$how$costHow"
        }
        return ImportCandidate(p, chosen?.symbol ?: "", chosen?.name, shares?.let { F2.qty(it) } ?: "", cost?.let { F2.plain(it) } ?: "", note,
            selected = chosen != null && shares != null && shares > 0)
    }

    // ── Quick wins ───────────────────────────────────────────────────────────
    private fun costPct(symbol: String, base: String): Double = when {
        symbol.endsWith(".OL") && base == "NOK" -> 0.1   // commission only
        else -> 0.6                                       // FX spread both ways + commission
    }

    /** Big-move read for one symbol from its daily chart (and intraday 5-minute bars when [intraday]). */
    suspend fun quickWin(symbol: String, owned: Boolean, watched: Boolean, intraday: Boolean): QuickWin? {
        val c = runCatching { chart(symbol) }.getOrNull() ?: return null
        val q = quickFromChart(symbol, c, owned, watched) ?: return null
        if (!intraday) return q
        return runCatching {
            val d = chart(symbol, 3 * 60_000L, "1d", "5m")
            val pv = d.candles.sumOf { (it.high + it.low + it.close) / 3 * it.volume }; val v = d.candles.sumOf { it.volume }
            QuickWin(q.symbol, q.name, q.changePct, q.price, q.currency, q.rvol, q.clv, q.above200, q.fromHighPct, q.advice, q.owned, q.watched,
                q.sessionOpen, q.minutesToClose, q.closeLocal, q.costPct, d.candles.map { it.close }, if (v > 0) pv / v else null)
        }.getOrDefault(q)
    }

    /** Same read from an already-downloaded daily chart (no network) — used by the Markets banner. */
    fun quickFromChart(symbol: String, c: ChartResult, owned: Boolean, watched: Boolean): QuickWin? {
        val chg = c.changePct ?: return null
        val cs = c.candles
        if (cs.size < 25) return null
        val last = cs.last()
        val price = c.price ?: last.close
        val open = c.meta.sessionOpen()
        val now = System.currentTimeMillis() / 1000
        val prev20 = cs.dropLast(1).takeLast(20).map { it.volume }.filter { it > 0 }
        val avgVol = if (prev20.size >= 10) prev20.average() else null
        val rs = c.meta.regularStart; val re = c.meta.regularEnd
        val frac = if (open && rs != null && re != null && re > rs) ((now - rs).toDouble() / (re - rs)).coerceIn(0.1, 1.0) else 1.0
        val rvol = if (avgVol != null && avgVol > 0 && last.volume > 0) last.volume / (avgVol * frac) else null
        val hi = maxOf(last.high, price); val lo = minOf(last.low, price)
        val clv = DayAfter.clv(price, lo, hi)
        val above = if (cs.size >= 200) price > cs.takeLast(200).map { it.close }.average() else null
        val cost = costPct(symbol, store.value.settings.baseCurrency)
        val advice = dayAfter?.let { DayAfter.advise(it.table(symbol), chg, rvol, clv, above, cost) }
        val mins = if (open && re != null) (re - now) / 60 else null
        val closeLocal = re?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5) }
        return QuickWin(symbol, c.meta.name, chg, price, c.meta.currency ?: "USD", rvol, clv, above, if (hi > 0) (price - hi) / hi * 100 else null,
            advice, owned, watched, open, mins, closeLocal, cost, emptyList(), null)
    }

    suspend fun quickWins(market: List<String>, minMove: Double, onProgress: (Int, Int) -> Unit): List<QuickWin> = coroutineScope {
        val st = store.value
        val owned = st.holdings.map { it.symbol }.toSet()
        val watched = st.watchlist.toSet()
        val gainers = runCatching { Yahoo.dayGainers() }.getOrDefault(emptyList())
        val all = (owned + watched + gainers + market).distinct()
        var done = 0
        val lock = Mutex()
        // Pass 1: daily charts only (cheap) to find what is actually up.
        val firstPass = all.map { s ->
            async {
                val q = quickWin(s, s in owned, s in watched, intraday = false)
                lock.withLock { done++; onProgress(done, all.size) }
                q
            }
        }.mapNotNull { it.await() }.filter { it.changePct >= minMove }
        // Pass 2: intraday detail for the movers that matter most.
        val top = firstPass.sortedWith(compareByDescending<QuickWin> { it.owned || it.watched }.thenByDescending { it.changePct }).take(16)
        val detailed = top.map { q -> async { quickWin(q.symbol, q.owned, q.watched, intraday = true) ?: q } }.map { it.await() }.associateBy { it.symbol }
        firstPass.map { detailed[it.symbol] ?: it }
            .sortedWith(compareByDescending<QuickWin> { it.owned }.thenByDescending { it.watched }.thenByDescending { it.changePct })
    }

    suspend fun trackRecord(): TrackView {
        val calls = store.value.calls
        val bySym = calls.groupBy { it.symbol }
        val today = java.time.LocalDate.now().toString()
        val symbols = bySym.keys.filter { s -> bySym[s]!!.any { it.date < java.time.LocalDate.now().minusDays(7).toString() } }.take(60)
        val graded = coroutineScope {
            symbols.map { s ->
                async {
                    val series = runCatching { Series(chart(s, 30 * 60_000L).completedCandles()) }.getOrNull()
                    bySym[s]!!.map { c -> if (series != null) TrackRecord.grade(c, series) else GradedCall(c, null, null, null) }
                }
            }.flatMap { it.await() }
        } + bySym.filterKeys { it !in symbols }.values.flatten().map { GradedCall(it, null, null, null) }
        val recent = graded.sortedByDescending { it.logged.date }.filter { it.logged.date < today || it.ret5 != null }.take(80)
        return TrackView(TrackRecord.summarize(graded), recent, bySym.size)
    }
}
