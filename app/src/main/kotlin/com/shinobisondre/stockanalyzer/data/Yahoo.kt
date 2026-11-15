package com.shinobisondre.stockanalyzer.data

import com.shinobisondre.stockanalyzer.engine.Candle
import com.shinobisondre.stockanalyzer.engine.Fundamentals
import com.shinobisondre.stockanalyzer.engine.MiniJson
import com.shinobisondre.stockanalyzer.engine.MiniJson.doubles
import com.shinobisondre.stockanalyzer.engine.MiniJson.list
import com.shinobisondre.stockanalyzer.engine.MiniJson.num
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import com.shinobisondre.stockanalyzer.engine.MiniJson.str
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset

data class ChartMeta(
    val symbol: String,
    val currency: String?,
    val exchange: String?,
    val instrumentType: String?,
    val longName: String?,
    val shortName: String?,
    val price: Double?,
    val marketTime: Long?,
    val gmtOffset: Long,
    val dayHigh: Double?,
    val dayLow: Double?,
    val volume: Double?,
    val high52: Double?,
    val low52: Double?,
    val regularStart: Long?,
    val regularEnd: Long?,
) {
    val name: String get() = longName ?: shortName ?: symbol
    /** True while the regular session is open — the last bar is still forming. */
    fun sessionOpen(nowSec: Long = System.currentTimeMillis() / 1000): Boolean =
        regularStart != null && regularEnd != null && nowSec in regularStart until regularEnd
}

class ChartResult(val symbol: String, val candles: List<Candle>, val meta: ChartMeta) {
    val last: Candle? get() = candles.lastOrNull()
    val price: Double? get() = meta.price ?: last?.close

    /** Previous session's close: the bar before the last one when the last bar is today's. */
    val prevClose: Double? get() {
        if (candles.size < 2) return null
        val lastDate = candles.last().date
        val mDate = meta.marketTime?.let { Instant.ofEpochSecond(it + meta.gmtOffset).atZone(ZoneOffset.UTC).toLocalDate().toString() }
        return if (mDate == null || mDate == lastDate) candles[candles.size - 2].close else candles.last().close
    }
    val change: Double? get() { val p = price; val pc = prevClose; return if (p != null && pc != null) p - pc else null }
    val changePct: Double? get() { val p = price; val pc = prevClose; return if (p != null && pc != null && pc > 0) (p - pc) / pc * 100 else null }

    /** Candles with the still-forming bar removed (used for alerts, so intraday wiggles don't flip calls). */
    fun completedCandles(): List<Candle> = if (meta.sessionOpen() && candles.size > 1) candles.dropLast(1) else candles
}

data class SearchHit(val symbol: String, val name: String, val type: String, val exchange: String)
data class NewsItem(val title: String, val publisher: String, val link: String, val timeSec: Long?, val sentiment: Int = 0)

data class QuoteExtra(
    val marketState: String?,
    val preMarketPrice: Double?, val preMarketChangePct: Double?,
    val postMarketPrice: Double?, val postMarketChangePct: Double?,
    val marketCap: Double?, val trailingPE: Double?, val forwardPE: Double?,
)

data class Catalysts(
    val nextEarnings: String?,
    val daysToEarnings: Int?,
    val exDividend: String?,
    val recStrongBuy: Int, val recBuy: Int, val recHold: Int, val recSell: Int, val recStrongSell: Int,
    val pulseDelta: Int,
    val upgrades30d: Int,
    val downgrades30d: Int,
    val recent: List<String>,
    val insiderNetPct: Double?,
)

class SummaryData(val fundamentals: Fundamentals, val catalysts: Catalysts?)

/**
 * Yahoo Finance over plain HTTPS. Charts and search work without a session;
 * quotes and quoteSummary need the cookie + crumb handshake (with the EU
 * consent fallback). When that fails the app still works — it just shows
 * no fundamentals.
 */
object Yahoo {
    private val crumbLock = Mutex()
    @Volatile private var crumb: String? = null
    @Volatile private var crumbFailedAt = 0L

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun chart(symbol: String, range: String = "2y", interval: String = "1d"): ChartResult {
        var last: Exception? = null
        for (host in listOf("query2", "query1")) {
            try {
                val body = Http.get("https://$host.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?range=$range&interval=$interval&includePrePost=false&events=div%7Csplit")
                return parseChart(symbol, body)
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("No data for $symbol")
    }

    fun parseChart(symbol: String, body: String): ChartResult {
        val root = MiniJson.parse(body).obj()["chart"].obj()
        root["error"].obj()["description"].str()?.let { throw IllegalStateException(it) }
        val res = root["result"].list().firstOrNull().obj()
        if (res.isEmpty()) throw IllegalStateException("No data found for $symbol")
        val m = res["meta"].obj()
        val gmt = m["gmtoffset"].num()?.toLong() ?: 0L
        val ts = res["timestamp"].list().map { it.num()?.toLong() }
        val q = res["indicators"].obj()["quote"].list().firstOrNull().obj()
        val adj = res["indicators"].obj()["adjclose"].list().firstOrNull().obj()["adjclose"].doubles()
        val o = q["open"].doubles(); val h = q["high"].doubles(); val l = q["low"].doubles(); val c = q["close"].doubles(); val v = q["volume"].doubles()
        val out = ArrayList<Candle>(ts.size)
        for (i in ts.indices) {
            val t = ts[i] ?: continue
            if (i >= c.size || c[i].isNaN() || c[i] <= 0) continue
            val op = o.getOrNull(i)?.takeIf { !it.isNaN() } ?: c[i]
            val hi = h.getOrNull(i)?.takeIf { !it.isNaN() } ?: maxOf(op, c[i])
            val lo = l.getOrNull(i)?.takeIf { !it.isNaN() } ?: minOf(op, c[i])
            val date = Instant.ofEpochSecond(t + gmt).atZone(ZoneOffset.UTC).toLocalDate().toString()
            val cd = Candle(date, op, hi, lo, c[i], v.getOrNull(i)?.takeIf { !it.isNaN() } ?: 0.0, adj.getOrNull(i)?.takeIf { !it.isNaN() } ?: c[i])
            if (out.isNotEmpty() && out.last().date == date) out[out.size - 1] = cd else out.add(cd)
        }
        val reg = m["currentTradingPeriod"].obj()["regular"].obj()
        val meta = ChartMeta(
            symbol = m["symbol"].str() ?: symbol,
            currency = m["currency"].str(),
            exchange = m["fullExchangeName"].str() ?: m["exchangeName"].str(),
            instrumentType = m["instrumentType"].str(),
            longName = m["longName"].str(),
            shortName = m["shortName"].str(),
            price = m["regularMarketPrice"].num(),
            marketTime = m["regularMarketTime"].num()?.toLong(),
            gmtOffset = gmt,
            dayHigh = m["regularMarketDayHigh"].num(),
            dayLow = m["regularMarketDayLow"].num(),
            volume = m["regularMarketVolume"].num(),
            high52 = m["fiftyTwoWeekHigh"].num(),
            low52 = m["fiftyTwoWeekLow"].num(),
            regularStart = reg["start"].num()?.toLong(),
            regularEnd = reg["end"].num()?.toLong(),
        )
        return ChartResult(symbol.uppercase(), out, meta)
    }

    suspend fun search(q: String, news: Int = 0, quotes: Int = 8): Pair<List<SearchHit>, List<NewsItem>> {
        val body = Http.get("https://query2.finance.yahoo.com/v1/finance/search?q=${enc(q)}&quotesCount=$quotes&newsCount=$news&enableFuzzyQuery=false")
        val root = MiniJson.parse(body).obj()
        val hits = root["quotes"].list().map { it.obj() }
            .filter { it["quoteType"].str() in setOf("EQUITY", "ETF", "INDEX", "MUTUALFUND", "CRYPTOCURRENCY") && it["symbol"].str() != null }
            .map {
                val type = it["quoteType"].str() ?: ""
                val name = if (type == "MUTUALFUND") it["longname"].str() ?: it["shortname"].str() else it["shortname"].str() ?: it["longname"].str()
                SearchHit(it["symbol"].str()!!, name ?: it["symbol"].str()!!, type, it["exchDisp"].str() ?: it["exchange"].str() ?: "")
            }
        val items = root["news"].list().map { it.obj() }.mapNotNull {
            val title = it["title"].str() ?: return@mapNotNull null
            NewsItem(title, it["publisher"].str() ?: "Yahoo", it["link"].str() ?: "", it["providerPublishTime"].num()?.toLong())
        }
        return hits to items
    }

    // ── Session (cookie + crumb) ─────────────────────────────────────────────
    suspend fun crumb(): String? = crumbLock.withLock {
        crumb?.let { return it }
        if (System.currentTimeMillis() - crumbFailedAt < 10 * 60_000) return null
        val c = runCatching { basicCrumb() }.getOrNull() ?: runCatching { consentCrumb() }.getOrNull()
        if (c == null) crumbFailedAt = System.currentTimeMillis() else crumb = c
        c
    }

    private suspend fun fetchCrumb(): String? {
        val body = Http.get("https://query2.finance.yahoo.com/v1/test/getcrumb", accept = "text/plain,*/*", allowError = true).trim()
        return body.takeIf { it.isNotEmpty() && it.length < 40 && !it.contains('<') && !it.contains('{') && !it.contains(' ') }
    }

    private suspend fun basicCrumb(): String? {
        runCatching { Http.get("https://fc.yahoo.com/", accept = "text/html,*/*", allowError = true) }
        return fetchCrumb()
    }

    /** EU flow: accept Yahoo's GDPR consent form, then ask for the crumb again. */
    private suspend fun consentCrumb(): String? {
        val html = Http.get("https://guce.yahoo.com/consent", accept = "text/html,*/*", allowError = true)
        fun input(name: String) = Regex("""name="$name"\s+value="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""value="([^"]+)"\s+name="$name"""").find(html)?.groupValues?.get(1)
        val csrf = input("csrfToken") ?: return fetchCrumb()
        val session = input("sessionId") ?: return fetchCrumb()
        Http.postForm("https://consent.yahoo.com/v2/collectConsent?sessionId=$session", listOf(
            "agree" to "agree", "agree" to "agree", "consentUUID" to "default", "sessionId" to session,
            "csrfToken" to csrf, "originalDoneUrl" to "https://finance.yahoo.com/", "namespace" to "yahoo",
        ))
        runCatching { Http.get("https://guce.yahoo.com/copyConsent?sessionId=$session", accept = "text/html,*/*", allowError = true) }
        return fetchCrumb()
    }

    private fun Any?.raw(): Double? = when (this) {
        is Map<*, *> -> (this["raw"] as? Number)?.toDouble()
        is Number -> this.toDouble()
        else -> null
    }

    suspend fun quotes(symbols: List<String>): Map<String, QuoteExtra> {
        if (symbols.isEmpty()) return emptyMap()
        val c = crumb() ?: return emptyMap()
        val body = Http.get("https://query2.finance.yahoo.com/v7/finance/quote?symbols=${enc(symbols.joinToString(","))}&crumb=${enc(c)}", allowError = true)
        val res = runCatching { MiniJson.parse(body).obj()["quoteResponse"].obj()["result"].list() }.getOrDefault(emptyList())
        return res.map { it.obj() }.mapNotNull { q ->
            val s = q["symbol"].str() ?: return@mapNotNull null
            s to QuoteExtra(
                q["marketState"].str(), q["preMarketPrice"].num(), q["preMarketChangePercent"].num(),
                q["postMarketPrice"].num(), q["postMarketChangePercent"].num(), q["marketCap"].num(), q["trailingPE"].num(), q["forwardPE"].num(),
            )
        }.toMap()
    }

    suspend fun summary(symbol: String): SummaryData? {
        val c = crumb() ?: return null
        val modules = "summaryDetail,defaultKeyStatistics,financialData,assetProfile,price,calendarEvents,recommendationTrend,upgradeDowngradeHistory,netSharePurchaseActivity"
        val body = Http.get("https://query2.finance.yahoo.com/v10/finance/quoteSummary/${enc(symbol)}?modules=$modules&crumb=${enc(c)}", allowError = true)
        val r = runCatching { MiniJson.parse(body).obj()["quoteSummary"].obj()["result"].list().firstOrNull().obj() }.getOrNull()
        if (r.isNullOrEmpty()) return null
        val sd = r["summaryDetail"].obj(); val ks = r["defaultKeyStatistics"].obj(); val fd = r["financialData"].obj()
        val ap = r["assetProfile"].obj(); val pr = r["price"].obj()
        val f = Fundamentals(
            price = fd["currentPrice"].raw() ?: pr["regularMarketPrice"].raw(),
            targetMeanPrice = fd["targetMeanPrice"].raw(),
            recommendationKey = fd["recommendationKey"].str(),
            recommendationMean = fd["recommendationMean"].raw(),
            numberOfAnalysts = fd["numberOfAnalystOpinions"].raw()?.toInt(),
            trailingPE = sd["trailingPE"].raw(),
            forwardPE = sd["forwardPE"].raw() ?: ks["forwardPE"].raw(),
            pegRatio = ks["pegRatio"].raw(),
            priceToBook = ks["priceToBook"].raw(),
            revenueGrowth = fd["revenueGrowth"].raw(),
            earningsGrowth = fd["earningsGrowth"].raw(),
            profitMargins = fd["profitMargins"].raw() ?: ks["profitMargins"].raw(),
            returnOnEquity = fd["returnOnEquity"].raw(),
            debtToEquity = fd["debtToEquity"].raw(),
            freeCashflow = fd["freeCashflow"].raw(),
            marketCap = sd["marketCap"].raw() ?: pr["marketCap"].raw(),
            avgVolume = sd["averageVolume"].raw(),
            fiftyTwoWeekLow = sd["fiftyTwoWeekLow"].raw(),
            fiftyTwoWeekHigh = sd["fiftyTwoWeekHigh"].raw(),
            fiftyDayAverage = sd["fiftyDayAverage"].raw(),
            twoHundredDayAverage = sd["twoHundredDayAverage"].raw(),
            beta = sd["beta"].raw(),
            dividendYield = sd["dividendYield"].raw(),
            sector = ap["sector"].str(),
            industry = ap["industry"].str(),
            description = ap["longBusinessSummary"].str(),
        )
        return SummaryData(f, catalysts(r))
    }

    private fun catalysts(r: Map<String, Any?>): Catalysts? {
        val now = System.currentTimeMillis() / 1000
        val cal = r["calendarEvents"].obj()
        val earn = cal["earnings"].obj()["earningsDate"].list().mapNotNull { it.raw()?.toLong() }.firstOrNull { it > now - 86400 }
        val exDiv = cal["exDividendDate"].raw()?.toLong()
        val trend = r["recommendationTrend"].obj()["trend"].list().map { it.obj() }
        val cur = trend.firstOrNull { it["period"].str() == "0m" } ?: trend.firstOrNull() ?: emptyMap()
        val prev = trend.firstOrNull { it["period"].str() == "-1m" } ?: emptyMap()
        fun n(m: Map<String, Any?>, k: String) = m[k].raw()?.toInt() ?: 0
        fun pulse(m: Map<String, Any?>) = n(m, "strongBuy") + n(m, "buy") - n(m, "sell") - n(m, "strongSell")
        val hist = r["upgradeDowngradeHistory"].obj()["history"].list().map { it.obj() }
            .filter { (it["epochGradeDate"].raw()?.toLong() ?: 0) > now - 30 * 86400 }
        val up = hist.count { it["action"].str() == "up" }
        val down = hist.count { it["action"].str() == "down" }
        val recent = hist.take(5).map {
            val d = it["epochGradeDate"].raw()?.toLong()?.let { s -> Instant.ofEpochSecond(s).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: ""
            "$d ${it["firm"].str() ?: ""}: ${it["fromGrade"].str()?.takeIf { s -> s.isNotBlank() } ?: "—"} → ${it["toGrade"].str() ?: "—"}"
        }
        val ins = r["netSharePurchaseActivity"].obj()["netPercentInsiderShares"].raw()
        fun iso(s: Long?) = s?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
        if (trend.isEmpty() && earn == null && hist.isEmpty()) return null
        return Catalysts(iso(earn), earn?.let { ((it - now) / 86400).toInt() }, iso(exDiv),
            n(cur, "strongBuy"), n(cur, "buy"), n(cur, "hold"), n(cur, "sell"), n(cur, "strongSell"),
            pulse(cur) - pulse(prev), up, down, recent, ins?.let { it * 100 })
    }
}
