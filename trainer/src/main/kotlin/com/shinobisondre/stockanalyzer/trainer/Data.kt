package com.shinobisondre.stockanalyzer.trainer

import com.shinobisondre.stockanalyzer.engine.Candle
import com.shinobisondre.stockanalyzer.engine.MiniJson
import com.shinobisondre.stockanalyzer.engine.MiniJson.doubles
import com.shinobisondre.stockanalyzer.engine.MiniJson.list
import com.shinobisondre.stockanalyzer.engine.MiniJson.num
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object Universe {
    /** Tickers the weights may be fitted on (same list the web app's walk-forward used). */
    val DISCOVERY = """AAPL MSFT NVDA GOOGL AMZN META TSLA AMD AVGO CRM ORCL ADBE INTC CSCO IBM QCOM TXN MU AMAT NFLX PYPL SHOP UBER
JPM BAC WFC C GS MS V MA AXP BLK SCHW
UNH JNJ PFE MRK ABBV LLY TMO ABT BMY AMGN GILD CVS MRNA NVO
WMT COST HD LOW MCD SBUX NKE DIS PG KO PEP TGT F GM
BA CAT GE HON UPS LMT XOM CVX COP SLB DE MMM
T VZ NEE DUK AMT LIN
PLTR COIN SOFI HOOD RBLX ENPH FSLR SMCI MSTR ARM""".split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Never used for fitting. A change only counts if it also shows up here. */
    val HOLDOUT = """ACN ADP AIG ALL BKNG BIIB BDX CAH CB CCL CL CMCSA CME CMG COF CSX CI D DAL DHR EA EBAY EL EMR EOG ETN FDX GD GIS
HAL HPQ HUM ICE INTU ISRG ITW KMB KR LRCX MAR MDT MDLZ MET MO NEM NOC NOW NSC OXY PGR PM PNC REGN ROST RTX SO SPGI SYK TJX
TMUS TSM UNP USB VLO VRTX WM ZTS ASML SAP TM SONY BABA SNOW CRWD DDOG NET ROKU ZM DKNG U AFRM RIVN LCID""".split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Out-of-domain check: Oslo Børs names, scored with the US-trained model. */
    val OSLO = """EQNR.OL DNB.OL NHY.OL TEL.OL MOWI.OL ORK.OL YAR.OL AKRBP.OL SALM.OL GJF.OL STB.OL KOG.OL TOM.OL
SUBC.OL FRO.OL BAKKA.OL SCATC.OL NOD.OL TGS.OL AKER.OL AUTO.OL VAR.OL HAFNI.OL MPCC.OL""".split(Regex("\\s+")).filter { it.isNotBlank() }

    const val SPY = "SPY"
    const val VIX = "^VIX"
}

/** Downloads daily bars from Yahoo's chart API and caches them as CSV. */
class BarStore(private val dir: File) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    init { dir.mkdirs() }

    private fun file(sym: String) = File(dir, sym.replace("^", "_") + ".csv")

    fun load(sym: String): List<Candle>? {
        val f = file(sym)
        if (!f.exists()) return null
        return f.readLines().drop(1).mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 7) null else Candle(p[0], p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p[6].toDouble())
        }
    }

    private fun save(sym: String, c: List<Candle>) {
        file(sym).printWriter().use { w ->
            w.println("date,open,high,low,close,volume,adjclose")
            for (x in c) w.println("${x.date},${x.open},${x.high},${x.low},${x.close},${x.volume},${x.adjClose}")
        }
    }

    fun fetchAll(symbols: List<String>, from: String = "2004-06-01", threads: Int = 4, maxAgeHours: Long = 20): Map<String, String> {
        val failures = java.util.concurrent.ConcurrentHashMap<String, String>()
        val pool = Executors.newFixedThreadPool(threads)
        val done = AtomicInteger()
        for (s in symbols) pool.submit {
            try {
                val f = file(s)
                val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeHours * 3600_000
                if (!fresh) {
                    val c = fetchYahoo(s, from) ?: fetchStooq(s)
                    if (c == null || c.size < 300) failures[s] = "no data (${c?.size ?: 0} bars)" else save(s, c)
                }
            } catch (e: Exception) { failures[s] = e.message ?: e.toString() }
            val d = done.incrementAndGet()
            if (d % 20 == 0) println("  fetched $d/${symbols.size}")
        }
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.HOURS)
        return failures
    }

    private fun get(url: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI(url)).header("User-Agent", ua).header("Accept", "application/json,text/csv,*/*")
            .timeout(Duration.ofSeconds(40)).GET().build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun fetchYahoo(sym: String, from: String): List<Candle>? {
        val p1 = java.time.LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val p2 = Instant.now().epochSecond
        val enc = URLEncoder.encode(sym, "UTF-8")
        for (attempt in 0 until 5) {
            val host = if (attempt % 2 == 0) "query2" else "query1"
            val url = "https://$host.finance.yahoo.com/v8/finance/chart/$enc?period1=$p1&period2=$p2&interval=1d&events=div%7Csplit&includeAdjustedClose=true"
            try {
                val r = get(url)
                if (r.statusCode() == 429 || r.statusCode() >= 500) { Thread.sleep(2000L * (attempt + 1)); continue }
                if (r.statusCode() != 200) return null
                return parseChart(r.body())
            } catch (e: Exception) {
                Thread.sleep(1500L * (attempt + 1))
            }
        }
        return null
    }

    private fun fetchStooq(sym: String): List<Candle>? {
        if (sym.startsWith("^")) return null
        val s = if (sym.contains('.')) sym.lowercase().replace(".ol", ".no") else sym.lowercase() + ".us"
        return try {
            val r = get("https://stooq.com/q/d/l/?s=$s&i=d")
            if (r.statusCode() != 200 || !r.body().startsWith("Date")) return null
            r.body().lines().drop(1).mapNotNull { line ->
                val p = line.split(',')
                if (p.size < 5) null else {
                    val o = p[1].toDoubleOrNull(); val h = p[2].toDoubleOrNull(); val l = p[3].toDoubleOrNull(); val c = p[4].toDoubleOrNull()
                    val v = p.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                    if (o == null || h == null || l == null || c == null) null else Candle(p[0], o, h, l, c, v, c)
                }
            }
        } catch (e: Exception) { null }
    }

    companion object {
        fun parseChart(body: String): List<Candle>? {
            val res = MiniJson.parse(body).obj()["chart"].obj()["result"].list().firstOrNull().obj()
            if (res.isEmpty()) return null
            val meta = res["meta"].obj()
            val gmt = meta["gmtoffset"].num()?.toLong() ?: 0L
            val ts = res["timestamp"].list().map { it.num()?.toLong() }
            val q = res["indicators"].obj()["quote"].list().firstOrNull().obj()
            val adj = res["indicators"].obj()["adjclose"].list().firstOrNull().obj()["adjclose"].doubles()
            val o = q["open"].doubles(); val h = q["high"].doubles(); val l = q["low"].doubles(); val c = q["close"].doubles(); val v = q["volume"].doubles()
            val out = ArrayList<Candle>(ts.size)
            var lastDate = ""
            for (i in ts.indices) {
                val t = ts[i] ?: continue
                if (i >= c.size || c[i].isNaN() || o[i].isNaN() || h[i].isNaN() || l[i].isNaN() || c[i] <= 0) continue
                val date = Instant.ofEpochSecond(t + gmt).atZone(ZoneOffset.UTC).toLocalDate().toString()
                if (date == lastDate) continue
                lastDate = date
                val a = if (i < adj.size && !adj[i].isNaN() && adj[i] > 0) adj[i] else c[i]
                out += Candle(date, o[i], h[i], l[i], c[i], if (i < v.size && !v[i].isNaN()) v[i] else 0.0, a)
            }
            return out
        }
    }
}
