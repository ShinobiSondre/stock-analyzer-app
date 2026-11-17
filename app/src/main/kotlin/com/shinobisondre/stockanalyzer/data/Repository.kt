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
import com.shinobisondre.stockanalyzer.engine.LedgerPosition
import com.shinobisondre.stockanalyzer.engine.Link
import com.shinobisondre.stockanalyzer.engine.NewsHeadline
import com.shinobisondre.stockanalyzer.engine.Pick
import com.shinobisondre.stockanalyzer.engine.PickExtras
import com.shinobisondre.stockanalyzer.engine.Picks
import com.shinobisondre.stockanalyzer.engine.NordnetNotes
import com.shinobisondre.stockanalyzer.engine.Trade
import com.shinobisondre.stockanalyzer.engine.Ripple
import com.shinobisondre.stockanalyzer.engine.RippleNode
import com.shinobisondre.stockanalyzer.engine.RippleStat
import com.shinobisondre.stockanalyzer.engine.TylerTake
import com.shinobisondre.stockanalyzer.engine.TylerTalk
import com.shinobisondre.stockanalyzer.engine.YtVideo
import com.shinobisondre.stockanalyzer.engine.MiniJson.list
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
    /** From imported trades. */
    val costNok: Double? = null,
    val since: String? = null,
    val isin: String? = null,
)

/** Imported Nordnet trades and which ticker each security (ISIN) maps to. */
class TradeBook(val trades: List<Trade>, val symbols: Map<String, String>) {
    fun forSymbol(symbol: String): List<Trade> = trades.filter { symbols[it.isin] == symbol }
    val ledger by lazy { NordnetNotes.ledger(trades) }
}

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
                val nokToBase = if (h.costNok != null) fxRate("NOK", base) else null
                val input = PositionInput(h.symbol, h.shares, h.avgCost, c?.price, c?.chart?.prevClose, c?.currency, rate, sector,
                    costPerShareBase = h.costNok?.let { cn -> nokToBase?.let { cn * it } })
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

    // ── Imported trades ──────────────────────────────────────────────────────
    private val tradesFile = java.io.File(context.filesDir, "trades.json")

    fun tradeBook(): TradeBook? = runCatching {
        val m = MiniJson.parse(tradesFile.readText()).obj()
        TradeBook(m["trades"].list().map { Trade.fromMap(it.obj()) }, m["symbols"].obj().mapValues { it.value.str() ?: "" }.filterValues { it.isNotBlank() })
    }.getOrNull()

    suspend fun saveTrades(book: TradeBook) = withContext(Dispatchers.IO) {
        val tmp = java.io.File(tradesFile.parentFile, "trades.json.tmp")
        tmp.writeText(MiniJson.write(linkedMapOf("trades" to book.trades.map { it.toMap() }, "symbols" to book.symbols)))
        tmp.renameTo(tradesFile)
    }

    /** Ticker for a position from the trades: Nordnet's code + market, checked against Yahoo; else a name search. */
    suspend fun resolveLedger(p: LedgerPosition): ImportCandidate {
        val guess = NordnetNotes.yahooTicker(p)
        var sym: String? = null; var name: String? = null
        if (guess != null) runCatching { chart(guess, 30 * 60_000L, "5d") }.getOrNull()?.let { c ->
            if (Money.normalize(c.meta.currency).first == Money.normalize(p.currency).first) { sym = guess; name = c.meta.name }
        }
        if (sym == null) for (q in BrokerScreenshot.searchQueries(p.name) + p.isin) {
            val hit = runCatching { Yahoo.search(q).first }.getOrDefault(emptyList()).firstOrNull { it.type in setOf("EQUITY", "ETF", "MUTUALFUND") }
            if (hit != null) { sym = hit.symbol; name = hit.name; break }
        }
        val buys = p.trades.count { it.buy }; val sells = p.trades.size - buys
        val since = p.firstBuy?.let { java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)) }
        val note = listOfNotNull(
            since?.let { "Held since $it" },
            "$buys buy${if (buys == 1) "" else "s"}" + if (sells > 0) ", $sells sell${if (sells == 1) "" else "s"}" else "",
            p.avgCostNok?.let { "cost ${F2.n(it)} kr/share incl. fees" },
        ).joinToString(" · ")
        val parsed = ParsedHolding(p.name, p.quantity, p.avgCost, p.currency, null, null, null, p.currency, null, "")
        return ImportCandidate(parsed, sym ?: "", name, F2.qty(p.quantity), p.avgCost?.let { F2.plain(it) } ?: "",
            if (sym == null) "No ticker found — type it in. $note" else note, selected = sym != null, costNok = p.avgCostNok, since = p.firstBuy, isin = p.isin)
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

    /**
     * Today's movers from the server scan (GitHub Actions, every 15 min in market hours) — one small
     * download instead of 150+ charts. Null when the file is missing or older than [maxAgeMin].
     */
    suspend fun serverQuickWins(maxAgeMin: Long = 40): List<QuickWin>? = runCatching {
        val body = Http.get("https://raw.githubusercontent.com/${com.shinobisondre.stockanalyzer.BuildConfig.UPDATE_REPO}/quickwins/quickwins.json?t=${System.currentTimeMillis() / 300_000}")
        val doc = MiniJson.parse(body).obj()
        val epoch = doc["epoch"].num()?.toLong() ?: return@runCatching null
        if (System.currentTimeMillis() / 1000 - epoch > maxAgeMin * 60) return@runCatching null
        val st = store.value
        val owned = st.holdings.map { it.symbol }.toSet(); val watched = st.watchlist.toSet()
        val now = System.currentTimeMillis() / 1000
        doc["items"].list().mapNotNull { e ->
            val m = e.obj()
            val sym = m["symbol"].str() ?: return@mapNotNull null
            val chg = m["changePct"].num() ?: return@mapNotNull null
            val rvol = m["rvol"].num(); val clv = m["clv"].num(); val above = m["above200"] as? Boolean
            val open = m["sessionOpen"] == true
            val end = m["close"].num()?.toLong()
            val cost = costPct(sym, st.settings.baseCurrency)
            val advice = dayAfter?.let { DayAfter.advise(it.table(sym), chg, rvol, clv, above, cost) }
            val closeLocal = end?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5) }
            QuickWin(sym, m["name"].str() ?: sym, chg, m["price"].num(), m["currency"].str() ?: "USD", rvol, clv, above, null, advice,
                sym in owned, sym in watched, open && end != null && now < end, end?.let { (it - now) / 60 }?.takeIf { open && it > 0 }, closeLocal, cost, emptyList(), null)
        }
    }.getOrNull()

    suspend fun quickWins(market: List<String>, minMove: Double, onProgress: (Int, Int) -> Unit): List<QuickWin> = coroutineScope {
        val st = store.value
        val owned = st.holdings.map { it.symbol }.toSet()
        val watched = st.watchlist.toSet()
        // The server already scanned the market: only its movers need a closer look here.
        val server = if (market.isNotEmpty()) serverQuickWins() else null
        val candidates = server?.map { it.symbol } ?: market
        val gainers = runCatching { Yahoo.dayGainers() }.getOrDefault(emptyList())
        val all = (owned + watched + gainers + candidates).distinct()
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

    // ── Picks ────────────────────────────────────────────────────────────────
    class PickView(val pick: Pick, val price: Double?, val currency: String, val changePct: Double?, val owned: Boolean)

    /** Full read for one stock: chart + model, company facts, analysts, earnings date and the latest headlines. */
    suspend fun pickFor(symbol: String): PickView? = coroutineScope {
        val core = runCatching { analyze(symbol) }.getOrNull() ?: return@coroutineScope null
        val sum = async { runCatching { net.withPermit { Yahoo.summary(symbol) } }.getOrNull() }
        val news = async { runCatching { net.withPermit { Yahoo.search(symbol, news = 8, quotes = 0).second } }.getOrDefault(emptyList()) }
        val s = sum.await()
        val now = System.currentTimeMillis() / 1000
        val heads = news.await().map { NewsHeadline(it.title, News.sentiment(it.title).coerceIn(-1, 1), Picks.ageHours(it.timeSec, now)) }
        val cat = s?.catalysts
        val p = Picks.evaluate(symbol, core.name, s?.fundamentals, core.analysis, heads,
            PickExtras(cat?.daysToEarnings, cat?.upgrades30d ?: 0, cat?.downgrades30d ?: 0))
        PickView(p, core.price, core.currency, core.changePct, store.value.holdings.any { it.symbol == symbol })
    }

    suspend fun picks(symbols: List<String>, onProgress: (Int, Int) -> Unit): List<PickView> = coroutineScope {
        var done = 0
        val lock = Mutex()
        symbols.map { s -> async { val r = runCatching { pickFor(s) }.getOrNull(); lock.withLock { done++; onProgress(done, symbols.size) }; r } }
            .mapNotNull { it.await() }.sortedByDescending { it.pick.score }
    }

    // ── Ripple tree ──────────────────────────────────────────────────────────
    class RippleChild(val node: RippleNode, val name: String, val stat: RippleStat?, val changePct: Double?, val price: Double?, val currency: String)
    class RippleView(val root: String, val rootName: String, val rootChange: Double?, val rootPrice: Double?, val rootCurrency: String,
                     val children: List<RippleChild>, val fromYahoo: Boolean, val at: Long = System.currentTimeMillis())

    /** Who is connected to [root] and how closely each has actually moved with it (last ~2 years). */
    suspend fun ripple(root: String): RippleView = coroutineScope {
        val sym = root.trim().uppercase()
        val mapped = Ripple.nodesFor(sym)
        val nodes = (mapped ?: Yahoo.similar(sym).take(10).map { RippleNode(it, Link.PEERS) }).filter { it.symbol != sym }.distinctBy { it.symbol }
        val leader = chart(sym)
        val kids = nodes.map { n ->
            async {
                val c = runCatching { chart(n.symbol) }.getOrNull() ?: return@async null
                RippleChild(n, c.meta.name, Ripple.stats(n.symbol, leader.candles, c.candles), c.changePct, c.price, c.meta.currency ?: "USD")
            }
        }.mapNotNull { it.await() }
        RippleView(sym, leader.meta.name, leader.changePct, leader.price, leader.meta.currency ?: "USD", kids, fromYahoo = mapped == null)
    }

    // ── Tyler Hill video summaries ──────────────────────────────────────────
    private val tylerFile = java.io.File(context.filesDir, "tyler.json")
    private val tylerLock = Mutex()

    class TylerCache(val videos: List<YtVideo>, val takes: Map<String, TylerTake>, val fetchedAt: Long)

    private fun videoMap(v: YtVideo) = linkedMapOf("id" to v.id, "title" to v.title, "published" to v.published, "description" to v.description.take(2000), "short" to v.short)
    private fun videoFrom(m: Map<String, Any?>) = YtVideo(m["id"].str() ?: "", m["title"].str() ?: "", m["published"].str() ?: "", m["description"].str() ?: "", m["short"] == true)

    fun tylerCache(): TylerCache = runCatching {
        val m = MiniJson.parse(tylerFile.readText()).obj()
        TylerCache(m["videos"].list().map { videoFrom(it.obj()) }.filter { it.id.isNotBlank() },
            m["takes"].obj().mapValues { TylerTake.fromMap(it.value.obj()) }, m["fetchedAt"].num()?.toLong() ?: 0L)
    }.getOrDefault(TylerCache(emptyList(), emptyMap(), 0L))

    private suspend fun saveTyler(fn: (TylerCache) -> TylerCache): TylerCache = tylerLock.withLock {
        val next = fn(tylerCache())
        withContext(Dispatchers.IO) {
            val tmp = java.io.File(tylerFile.parentFile, "tyler.json.tmp")
            // Keep the 30 newest summaries.
            val keep = next.takes.values.sortedByDescending { it.published }.take(30).associateBy { it.videoId }
            tmp.writeText(MiniJson.write(linkedMapOf("videos" to next.videos.map { videoMap(it) }, "takes" to keep.mapValues { it.value.toMap() }, "fetchedAt" to next.fetchedAt)))
            tmp.renameTo(tylerFile)
        }
        next
    }

    /** Latest uploads (long videos only — Shorts rarely say what he expects). */
    suspend fun tylerVideos(): List<YtVideo> {
        val vids = YouTube.latest().filter { !it.short }
        saveTyler { TylerCache(vids, it.takes, System.currentTimeMillis()) }
        return vids
    }

    /** Transcript → AI summary of what Tyler thinks will happen (key quotes only if no AI answers). */
    suspend fun tylerSummarize(v: YtVideo, allowAsr: Boolean = true, onStage: (String) -> Unit): TylerTake {
        onStage("Getting the transcript…")
        // Exactly FaithScroll's route: video page + YouTube's Android player for the caption list, then the
        // caption file (plain link, else the website link with a proof-of-origin token).
        val transcript = runCatching {
            com.shinobisondre.stockanalyzer.video.StreamResolver.ensureInit()
            com.shinobisondre.stockanalyzer.video.Transcripts.original(v.id)
        }.getOrNull()
        val info = runCatching { com.shinobisondre.stockanalyzer.video.WatchPage.info(v.id) }.getOrNull()
        var cues = transcript?.cues
        com.shinobisondre.stockanalyzer.video.Diag.log("tyler", "${v.id}: YouTube captions ${if (cues.isNullOrEmpty()) "not available" else "${cues.size} lines"}")
        if (cues.isNullOrEmpty() && allowAsr) {
            // YouTube wouldn't hand over captions: listen to the video on the phone (Whisper, like FaithScroll).
            cues = runCatching { com.shinobisondre.stockanalyzer.video.Asr.transcribeVideo(context, v.id, onStage) }
                .onFailure { com.shinobisondre.stockanalyzer.video.Diag.err("asr", "${v.id} failed", it) }.getOrNull()
        }
        val text = cues?.map { it.text }?.let { TylerTalk.clean(it) }?.takeIf { it.split(' ').size > 40 }
        val video = v.copy(
            description = info?.description?.takeIf { it.isNotBlank() } ?: v.description,
            title = v.title.ifBlank { info?.title ?: "" },
        )
        val keys = text?.let { TylerTalk.keyLines(it) } ?: emptyList()
        val words = text?.split(' ')?.size ?: 0
        onStage(if (text != null) "Summarising ${"%,d".format(words)} words…" else "No transcript — summarising the title and description…")
        val now = System.currentTimeMillis()
        var take: TylerTake? = null
        for (attempt in 0..1) {
            val prompt = if (attempt == 0 || text == null) TylerTalk.userPrompt(video, text)
            else TylerTalk.userPrompt(video, text.split(' ').take(3500).joinToString(" ")) // shorter retry
            val r = runCatching { Ai.chat(TylerTalk.system(), prompt) }
            val (answer, provider) = r.getOrNull() ?: continue
            take = TylerTalk.parseAnswer(answer, video, keys, "ai:$provider" + (if (text == null) " (no transcript)" else ""), words, now)
            if (take != null) break
        }
        val result = take ?: TylerTalk.quotesOnly(video, text, now)
        saveTyler { c -> TylerCache(c.videos, c.takes + (v.id to result), c.fetchedAt) }
        return result
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
