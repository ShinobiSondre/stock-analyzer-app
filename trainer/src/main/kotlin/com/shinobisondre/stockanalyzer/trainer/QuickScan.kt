package com.shinobisondre.stockanalyzer.trainer

import com.shinobisondre.stockanalyzer.engine.MiniJson
import com.shinobisondre.stockanalyzer.engine.MiniJson.list
import com.shinobisondre.stockanalyzer.engine.MiniJson.num
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import com.shinobisondre.stockanalyzer.engine.MiniJson.str
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

/**
 * Runs every 15 minutes in GitHub Actions during market hours: finds today's +3% movers in
 * ~200 US and Oslo stocks and writes quickwins.json. The phone downloads that one small file
 * instead of fetching 150+ charts itself, so Quick wins alerts cost almost no battery.
 * Holdings are never sent anywhere: the phone matches them against the list locally.
 */
object QuickScan {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

    private fun scan(sym: String): Map<String, Any?>? {
        val enc = URLEncoder.encode(sym, "UTF-8")
        for (attempt in 0 until 3) {
            val host = if (attempt % 2 == 0) "query2" else "query1"
            try {
                val req = HttpRequest.newBuilder(URI("https://$host.finance.yahoo.com/v8/finance/chart/$enc?range=1y&interval=1d"))
                    .header("User-Agent", UA).timeout(Duration.ofSeconds(25)).GET().build()
                val r = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (r.statusCode() == 429 || r.statusCode() >= 500) { Thread.sleep(1500L * (attempt + 1)); continue }
                if (r.statusCode() != 200) return null
                val res = MiniJson.parse(r.body()).obj()["chart"].obj()["result"].list().firstOrNull().obj()
                val meta = res["meta"].obj()
                val candles = BarStore.parseChart(r.body()) ?: return null
                if (candles.size < 30) return null
                val price = meta["regularMarketPrice"].num() ?: candles.last().close
                val gmt = meta["gmtoffset"].num()?.toLong() ?: 0L
                val mt = meta["regularMarketTime"].num()?.toLong()
                val marketDate = mt?.let { Instant.ofEpochSecond(it + gmt).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                val todayBar = marketDate == null || candles.last().date == marketDate
                val prev = if (todayBar) candles[candles.size - 2].close else candles.last().close
                if (prev <= 0) return null
                val chg = (price / prev - 1) * 100
                if (chg < 3.0) return null
                val last = candles.last()
                val reg = meta["currentTradingPeriod"].obj()["regular"].obj()
                val start = reg["start"].num()?.toLong(); val end = reg["end"].num()?.toLong()
                val now = Instant.now().epochSecond
                val open = start != null && end != null && now in start until end
                val frac = if (open && end!! > start!!) ((now - start).toDouble() / (end - start)).coerceIn(0.1, 1.0) else 1.0
                val prev20 = candles.dropLast(if (todayBar) 1 else 0).takeLast(20).map { it.volume }.filter { it > 0 }
                val vol = (meta["regularMarketVolume"].num() ?: last.volume)
                val rvol = if (prev20.size >= 10 && vol > 0) vol / (prev20.average() * frac) else null
                val hi = maxOf(meta["regularMarketDayHigh"].num() ?: last.high, price)
                val lo = minOf(meta["regularMarketDayLow"].num() ?: last.low, price)
                val clv = if (hi > lo) ((price - lo) / (hi - lo)).coerceIn(0.0, 1.0) else null
                val above = if (candles.size >= 200) price > candles.takeLast(200).map { it.close }.average() else null
                return linkedMapOf(
                    "symbol" to sym, "name" to (meta["longName"].str() ?: meta["shortName"].str() ?: sym),
                    "changePct" to chg, "price" to price, "currency" to (meta["currency"].str() ?: "USD"),
                    "rvol" to rvol, "clv" to clv, "above200" to above, "sessionOpen" to open, "close" to end,
                )
            } catch (e: Exception) { Thread.sleep(1000L * (attempt + 1)) }
        }
        return null
    }

    fun run(out: File) {
        out.mkdirs()
        val symbols = (Universe.DISCOVERY + Universe.HOLDOUT + Universe.OSLO + listOf("PLTR", "SMCI", "ARM", "MSTR", "COIN", "HOOD", "SOFI", "HIMS",
            "RKLB", "IONQ", "OKLO", "APP", "VRT", "ANET", "DELL", "MRVL", "MBLY", "RDDT", "TTD", "SNDK", "WDC", "STX", "KMAR.OL", "NAS.OL")).distinct()
        val pool = Executors.newFixedThreadPool(6)
        val futures = symbols.map { s -> pool.submit<Map<String, Any?>?> { scan(s) } }
        val items = futures.mapNotNull { runCatching { it.get(60, TimeUnit.SECONDS) }.getOrNull() }
            .sortedByDescending { (it["changePct"] as Double) }
        pool.shutdown()
        val doc = linkedMapOf("generatedAt" to Instant.now().toString(), "epoch" to Instant.now().epochSecond, "scanned" to symbols.size, "items" to items)
        File(out, "quickwins.json").writeText(MiniJson.write(doc))
        println("QuickScan: ${items.size} movers ≥ +3% out of ${symbols.size} → ${items.take(10).joinToString { "${it["symbol"]} %+.1f%%".format(it["changePct"]) }}")
    }
}
