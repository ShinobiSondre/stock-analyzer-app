package com.shinobisondre.stockanalyzer.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

data class NewsDigest(
    val items: List<NewsItem>,
    val positive: Int,
    val negative: Int,
    val neutral: Int,
    val overall: String,
    val whyMoving: String?,
)

/**
 * Headlines from Yahoo + Google News, with a word-list sentiment read.
 * Sentiment is shown as CONTEXT ONLY: the web app's own learning loop scored
 * headline sentiment at 26% directional accuracy, so it no longer votes.
 */
object News {
    private val POS = listOf("surge", "surges", "surged", "soar", "soars", "soared", "rally", "rallies", "rallied", "jumps", "jumped",
        "climbs", "climbed", "rises", "rose", "gains", "gained", "rebound", "rebounds", "outperform", "outperforms", "beat", "beats",
        "tops estimates", "above expectations", "exceeds", "raises guidance", "raised guidance", "record profit", "record revenue",
        "upgrade", "upgrades", "upgraded", "price target raised", "raises price target", "overweight", "bullish", "buy rating",
        "buyback", "breakthrough", "partnership", "approved", "approval", "strong demand", "boost", "boosted")
    private val NEG = listOf("plunge", "plunges", "plunged", "crash", "crashes", "crashed", "slump", "slumps", "slumped", "tumbles",
        "tumbled", "sinks", "sank", "falls", "fell", "drops", "dropped", "slides", "slid", "declines", "declined", "underperform",
        "selloff", "sell-off", "miss", "misses", "missed", "below expectations", "cuts guidance", "lowered guidance", "disappointing",
        "disappoints", "warns", "warning", "downgrade", "downgrades", "downgraded", "target lowered", "cuts price target", "underweight",
        "bearish", "sell rating", "lawsuit", "investigation", "probe", "layoff", "layoffs", "bankruptcy", "recall", "recession",
        "sanctions", "tariff", "tariffs", "crisis", "weak demand", "plummets", "plummeting")
    private fun compile(words: List<String>) = words.map { Regex("\\b" + Regex.escape(it) + "\\b", RegexOption.IGNORE_CASE) }
    private val POS_RE = compile(POS)
    private val NEG_RE = compile(NEG)

    fun sentiment(text: String): Int = POS_RE.count { it.containsMatchIn(text) } - NEG_RE.count { it.containsMatchIn(text) }

    suspend fun google(query: String, max: Int = 8): List<NewsItem> {
        val url = "https://news.google.com/rss/search?q=${URLEncoder.encode(query, "UTF-8")}&hl=en-US&gl=US&ceid=US:en"
        val xml = Http.get(url, accept = "application/rss+xml,application/xml,text/xml,*/*")
        return parseRss(xml).take(max)
    }

    private fun parseRss(xml: String): List<NewsItem> {
        val out = mutableListOf<NewsItem>()
        val p = Xml.newPullParser()
        p.setInput(xml.reader())
        var inItem = false
        var title = ""; var link = ""; var date = ""; var source = ""
        var tag = ""
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item") { inItem = true; title = ""; link = ""; date = ""; source = "" }
                }
                XmlPullParser.TEXT -> if (inItem) when (tag) {
                    "title" -> title += p.text
                    "link" -> link += p.text
                    "pubDate" -> date += p.text
                    "source" -> source += p.text
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item") {
                        inItem = false
                        val t = runCatching { fmt.parse(date.trim())?.time?.div(1000) }.getOrNull()
                        // Google appends " - Publisher" to titles.
                        val clean = if (source.isNotBlank() && title.endsWith(" - ${source.trim()}")) title.removeSuffix(" - ${source.trim()}") else title
                        if (clean.isNotBlank()) out += NewsItem(clean.trim(), source.trim().ifEmpty { "Google News" }, link.trim(), t)
                    }
                    tag = ""
                }
            }
            p.next()
        }
        return out
    }

    /**
     * Merge, de-duplicate and score headlines; explain today's move if a
     * matching headline exists (word-boundary matching — the web app's
     * substring match let "ai" hit "said" and "MU" hit "much").
     */
    fun digest(symbol: String, companyName: String?, yahoo: List<NewsItem>, google: List<NewsItem>, changePct: Double?): NewsDigest {
        val seen = HashSet<String>()
        val merged = (yahoo + google)
            .filter { seen.add(it.title.lowercase().take(60)) }
            .map { it.copy(sentiment = sentiment(it.title)) }
            .sortedByDescending { it.timeSec ?: 0 }
            .take(12)
        val pos = merged.count { it.sentiment > 0 }; val neg = merged.count { it.sentiment < 0 }
        val avg = if (merged.isEmpty()) 0.0 else merged.sumOf { it.sentiment }.toDouble() / merged.size
        val overall = when { avg > 0.4 -> "Positive"; avg < -0.4 -> "Negative"; else -> "Neutral" }
        return NewsDigest(merged, pos, neg, merged.size - pos - neg, overall, whyMoving(symbol, companyName, merged, changePct))
    }

    private fun whyMoving(symbol: String, company: String?, items: List<NewsItem>, changePct: Double?): String? {
        if (changePct == null || abs(changePct) < 2) return null
        val now = System.currentTimeMillis() / 1000
        val dir = if (changePct > 0) 1 else -1
        val symRe = Regex("\\b" + Regex.escape(symbol.substringBefore('.')) + "\\b", RegexOption.IGNORE_CASE)
        val coRe = company?.split(' ', ',')?.firstOrNull()?.takeIf { it.length > 2 }?.let { Regex("\\b" + Regex.escape(it) + "\\b", RegexOption.IGNORE_CASE) }
        val best = items.filter { it.timeSec != null && now - it.timeSec < 36 * 3600 }
            .map { n ->
                var s = 0
                if (symRe.containsMatchIn(n.title)) s += 3
                if (coRe?.containsMatchIn(n.title) == true) s += 2
                val sent = sentiment(n.title)
                if (sent != 0) s += if (sent * dir > 0) 2 else -2
                n to s
            }
            .filter { it.second >= 3 }
            .maxByOrNull { it.second }?.first
        val arrow = if (dir > 0) "up" else "down"
        return if (best != null) "$symbol is $arrow ${"%.1f".format(abs(changePct))}% today. Likely driver: \"${best.title}\" (${best.publisher})."
        else "$symbol is $arrow ${"%.1f".format(abs(changePct))}% today with no single clear headline — likely market or sector flow."
    }
}
