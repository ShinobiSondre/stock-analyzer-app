package com.shinobisondre.stockanalyzer.data

import android.content.Context
import com.shinobisondre.stockanalyzer.engine.Analysis
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

class Repository(private val context: Context, val store: Store) {

    val model: ModelBundle? by lazy {
        runCatching {
            context.assets.open("model.json").bufferedReader().use { ModelBundle.fromJson(it.readText()) }
        }.getOrNull()?.takeIf { it.compatible }
    }

    private val charts = ConcurrentHashMap<String, Pair<Long, ChartResult>>()
    private val fx = ConcurrentHashMap<String, Pair<Long, Double>>()
    private val marketLock = Mutex()
    @Volatile private var market: MarketData? = null
    private val net = Semaphore(6)

    suspend fun chart(symbol: String, maxAgeMs: Long = 4 * 60_000L, range: String = "2y"): ChartResult {
        val key = "$symbol|$range"
        charts[key]?.let { (t, c) -> if (System.currentTimeMillis() - t < maxAgeMs) return c }
        val c = net.withPermit { Yahoo.chart(symbol, range) }
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
