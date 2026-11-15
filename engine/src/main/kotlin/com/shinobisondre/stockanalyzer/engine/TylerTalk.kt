package com.shinobisondre.stockanalyzer.engine

import com.shinobisondre.stockanalyzer.engine.MiniJson.list
import com.shinobisondre.stockanalyzer.engine.MiniJson.num
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import com.shinobisondre.stockanalyzer.engine.MiniJson.str

/** One stock Tyler talks about and what he expects from it. */
data class TylerCall(
    val ticker: String,
    val name: String,
    val view: String,          // bullish | bearish | neutral
    val what: String,          // what he thinks will happen
    val levels: String,        // prices/zones he names
    val timeframe: String,
    val action: String,        // what he is doing himself: buying | adding | holding | trimming | selling | watching | ""
) {
    val tone: Tone get() = when (view.lowercase()) { "bullish" -> Tone.BUY; "bearish" -> Tone.SELL; else -> Tone.NEUTRAL }

    fun toMap(): Map<String, Any?> = linkedMapOf("ticker" to ticker, "name" to name, "view" to view, "what" to what, "levels" to levels, "timeframe" to timeframe, "action" to action)
}

/** What Tyler thinks will happen, from one video. */
data class TylerTake(
    val videoId: String,
    val title: String,
    val published: String,
    val headline: String,
    val stance: String,        // bullish | bearish | mixed | neutral
    val market: String,
    val calls: List<TylerCall>,
    val moves: String,
    val watch: String,
    val caveats: String,
    val keyLines: List<String>,
    /** "ai:<model>", "quotes" (no AI available — key quotes only) or "description" (no transcript). */
    val source: String,
    val words: Int,
    val createdAt: Long,
) {
    val tone: Tone get() = when (stance.lowercase()) { "bullish" -> Tone.BUY; "bearish" -> Tone.SELL; else -> Tone.NEUTRAL }
    val aiMade: Boolean get() = source.startsWith("ai:")

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "videoId" to videoId, "title" to title, "published" to published, "headline" to headline, "stance" to stance, "market" to market,
        "calls" to calls.map { it.toMap() }, "moves" to moves, "watch" to watch, "caveats" to caveats, "keyLines" to keyLines,
        "source" to source, "words" to words, "createdAt" to createdAt,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = TylerTake(
            m["videoId"].str() ?: "", m["title"].str() ?: "", m["published"].str() ?: "", m["headline"].str() ?: "", m["stance"].str() ?: "neutral",
            m["market"].str() ?: "", m["calls"].list().map { TylerTalk.callFrom(it.obj()) }.filter { it.ticker.isNotBlank() || it.name.isNotBlank() },
            m["moves"].str() ?: "", m["watch"].str() ?: "", m["caveats"].str() ?: "", m["keyLines"].list().mapNotNull { it.str() },
            m["source"].str() ?: "quotes", m["words"].num()?.toInt() ?: 0, m["createdAt"].num()?.toLong() ?: 0L,
        )
    }
}

object TylerTalk {
    const val CHANNEL_ID = "UC-FwZq0rYfc2SzYkpoNBJGg"
    const val HANDLE = "@TylerHillStocks"
    private const val MAX_WORDS = 9000

    /** Names people say out loud → tickers. Explicit "$TICKER" and capitalised tickers are picked up too. */
    val ALIASES: Map<String, String> = linkedMapOf(
        "nvidia" to "NVDA", "apple" to "AAPL", "microsoft" to "MSFT", "alphabet" to "GOOGL", "google" to "GOOGL", "amazon" to "AMZN",
        "meta" to "META", "facebook" to "META", "tesla" to "TSLA", "palantir" to "PLTR", "amd" to "AMD", "advanced micro" to "AMD",
        "broadcom" to "AVGO", "taiwan semi" to "TSM", "tsmc" to "TSM", "micron" to "MU", "netflix" to "NFLX", "coinbase" to "COIN",
        "microstrategy" to "MSTR", "bitcoin" to "BTC-USD", "ethereum" to "ETH-USD", "s&p 500" to "SPY", "s&p" to "SPY", "spy" to "SPY",
        "nasdaq" to "QQQ", "qqq" to "QQQ", "mobileye" to "MBLY", "eli lilly" to "LLY", "lilly" to "LLY", "unitedhealth" to "UNH",
        "united health" to "UNH", "jpmorgan" to "JPM", "jp morgan" to "JPM", "sofi" to "SOFI", "hims" to "HIMS", "rocket lab" to "RKLB",
        "oklo" to "OKLO", "ionq" to "IONQ", "robinhood" to "HOOD", "super micro" to "SMCI", "intel" to "INTC", "oracle" to "ORCL",
        "salesforce" to "CRM", "shopify" to "SHOP", "uber" to "UBER", "berkshire" to "BRK-B", "costco" to "COST", "walmart" to "WMT",
        "novo nordisk" to "NVO", "novo" to "NVO", "asml" to "ASML", "arm holdings" to "ARM", "snowflake" to "SNOW", "crowdstrike" to "CRWD",
        "cloudflare" to "NET", "reddit" to "RDDT", "nike" to "NKE", "disney" to "DIS", "paypal" to "PYPL", "visa" to "V", "mastercard" to "MA",
        "lululemon" to "LULU", "starbucks" to "SBUX", "chipotle" to "CMG", "duolingo" to "DUOL", "applovin" to "APP", "celsius" to "CELH",
        "rivian" to "RIVN", "lucid" to "LCID", "nio" to "NIO", "alibaba" to "BABA", "pdd" to "PDD", "nu holdings" to "NU", "nubank" to "NU",
        "zeta" to "ZETA", "upstart" to "UPST", "affirm" to "AFRM", "dutch bros" to "BROS", "on holding" to "ONON", "hood" to "HOOD",
        "gold" to "GLD", "silver" to "SLV", "russell" to "IWM", "dow" to "DIA", "vix" to "^VIX", "kongsberg" to "KOG.OL", "equinor" to "EQNR",
    )

    private val TICKER_WORD = Regex("""\$([A-Z]{1,5}(?:\.[A-Z]{1,2})?)\b""")
    private val CAPS = Regex("""\b([A-Z]{2,5})\b""")
    private val NOT_TICKERS = setOf("I", "A", "AI", "CEO", "CFO", "ETF", "IPO", "USA", "US", "EPS", "PE", "GDP", "CPI", "PPI", "FED", "FOMC", "RSI", "MACD",
        "EV", "EVS", "OK", "THE", "AND", "FOR", "YOU", "NOT", "BUY", "SELL", "HOLD", "NOW", "NEW", "ALL", "TOP", "WHY", "HOW", "BIG", "ATH", "DCA", "YTD",
        "LIVE", "MY", "IS", "IT", "TO", "IN", "ON", "OF", "AT", "BE", "SO", "NO", "DO", "GO", "UP", "ARE", "THIS", "THAT", "WILL", "JUST", "HUGE", "BEST",
        "STOCK", "STOCKS", "MARKET", "CRASH", "RALLY", "TIME", "WEEK", "NEXT", "DONT", "IM", "VS", "ROTH", "IRA", "HSA", "LLC", "SEC", "DOJ", "FTC", "Q1",
        "Q2", "Q3", "Q4", "PM", "AM", "EST", "PST", "UK", "EU", "CNBC", "TV", "OMG", "WOW", "LOL")

    /** Tickers mentioned in [text] (aliases, $TICKER, and capitalised tokens when [caps]). */
    fun tickersIn(text: String, caps: Boolean = false): List<String> {
        val out = LinkedHashSet<String>()
        TICKER_WORD.findAll(text).forEach { out += it.groupValues[1] }
        val low = " " + text.lowercase().replace(Regex("[^a-z0-9&\\s]"), " ") + " "
        for ((k, v) in ALIASES) if (low.contains(" $k ")) out += v
        if (caps) CAPS.findAll(text).forEach { m -> val t = m.groupValues[1]; if (t !in NOT_TICKERS) out += t }
        return out.toList()
    }

    /** Joins caption pieces into readable text: drops [Music]/[Applause], repeated fragments and stray spacing. */
    fun clean(pieces: List<String>): String {
        val out = ArrayList<String>(pieces.size)
        for (p in pieces) {
            val t = p.replace(Regex("\\[(music|applause|laughter|__)\\]", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty() || out.lastOrNull() == t) continue
            out += t
        }
        return out.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    /** ~24-word windows (automatic captions often have no punctuation). */
    fun windows(text: String, size: Int = 24): List<String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val avgLen = if (sentences.isEmpty()) 0.0 else sentences.sumOf { it.split(' ').size }.toDouble() / sentences.size
        if (sentences.size > 3 && avgLen in 4.0..45.0) {
            // Real sentences: merge very short ones with the next.
            val out = mutableListOf<String>(); val sb = StringBuilder()
            for (s in sentences) { if (sb.isNotEmpty()) sb.append(' '); sb.append(s); if (sb.split(' ').size >= 12) { out += sb.toString(); sb.clear() } }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }
        val words = text.split(' ').filter { it.isNotBlank() }
        return words.chunked(size).map { it.joinToString(" ") }
    }

    private val FORECAST = listOf(
        "i think", "i believe", "i expect", "expect", "going to", "gonna", "will be", "will go", "will likely", "likely", "probably", "my target",
        "target", "price target", "support", "resistance", "breakout", "break out", "pullback", "pull back", "correction", "crash", "bottom", "top is in",
        "rally", "bull run", "bear market", "recession", "rate cut", "i'm buying", "i bought", "i'm adding", "i added", "i sold", "i'm selling", "trim",
        "trimming", "taking profit", "take profit", "buy zone", "accumulat", "i'm watching", "watch for", "if it breaks", "if we break", "next week",
        "this week", "by the end of", "next year", "upside", "downside", "overbought", "oversold", "divergence", "bollinger", "rsi",
    )

    /** The most "what he thinks will happen" lines, in order of appearance. */
    fun keyLines(text: String, max: Int = 8): List<String> {
        val w = windows(text)
        if (w.isEmpty()) return emptyList()
        val scored = w.mapIndexed { i, s ->
            val low = s.lowercase()
            var sc = FORECAST.count { low.contains(it) } * 2.0
            sc += tickersIn(s).size * 1.5
            sc += Regex("\\$?\\d+(?:[.,]\\d+)?\\s?(%|percent|dollars|bucks|k\\b)").findAll(low).count() * 1.2
            sc += Regex("\\b\\d{2,5}(?:\\.\\d+)?\\b").findAll(low).count() * 0.4
            if (low.contains("sponsor") || low.contains("link in the description") || low.contains("subscribe") || low.contains("discord") || low.contains("patreon")) sc -= 10
            Triple(i, s, sc)
        }
        return scored.filter { it.third >= 3.0 }.sortedByDescending { it.third }.take(max).sortedBy { it.first }.map { it.second.trim() }
    }

    /** Keeps long transcripts within the model's comfortable size: the start, then the most forecast-heavy parts. */
    fun fit(text: String): String {
        val words = text.split(' ')
        if (words.size <= MAX_WORDS) return text
        val head = words.take(5500).joinToString(" ")
        val rest = words.drop(5500).joinToString(" ")
        val extra = keyLines(rest, 60).joinToString(" … ")
        return "$head … $extra".split(' ').take(MAX_WORDS).joinToString(" ")
    }

    fun system(): String = """You summarise videos from Tyler Hill (YouTube channel "Tyler Hill Stocks") for a viewer who wants to know, quickly, WHAT HE THINKS WILL HAPPEN.
You get the title, publish date, description and an automatic transcript (may have recognition errors, e.g. tickers spelled as words).
Answer with ONE JSON object and nothing else:
{
 "headline": "his main call in one sentence (<= 25 words)",
 "stance": "bullish | bearish | mixed | neutral  (his overall stance on the market in this video)",
 "market": "what he expects for the overall market / S&P 500 / Nasdaq / Bitcoin: direction, levels, timeframe (1-3 sentences, empty string if not covered)",
 "calls": [ {"ticker": "NVDA", "name": "Nvidia", "view": "bullish | bearish | neutral", "what": "what he expects this stock to do and why (1-2 sentences)", "levels": "prices / zones / targets he names, else empty", "timeframe": "his timeframe, else empty", "action": "buying | adding | holding | trimming | selling | watching | empty"} ],
 "moves": "what he says he is doing with his own money (bought, sold, trimmed, cash level), empty if nothing",
 "watch": "the signals or events he says he is waiting for before acting (e.g. RSI > 70 with divergence, upper Bollinger band, earnings, Fed), empty if none",
 "caveats": "conditions or hedges he puts on his calls, empty if none"
}
Rules: only include what he actually says; never invent prices or tickers. Use US tickers where you are sure (Yahoo Finance style, e.g. BRK-B, BTC-USD); if unsure, leave "ticker" empty and fill "name". At most 12 calls, most important first. Plain sentences, no markdown."""

    fun userPrompt(v: YtVideo, transcript: String?): String = buildString {
        appendLine("Title: ${v.title}")
        appendLine("Published: ${v.published.take(10)}")
        if (v.description.isNotBlank()) appendLine("Description: ${v.description.take(1500)}")
        appendLine()
        if (transcript.isNullOrBlank()) appendLine("Transcript: (not available — summarise only from the title and description and say so in the headline)")
        else { appendLine("Transcript:"); appendLine(fit(transcript)) }
    }

    /** Parses the model's answer (tolerates ```json fences and text around the object). */
    fun parseAnswer(answer: String, v: YtVideo, keyLines: List<String>, source: String, words: Int, now: Long): TylerTake? {
        val start = answer.indexOf('{'); val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val m = runCatching { MiniJson.parse(answer.substring(start, end + 1)).obj() }.getOrNull() ?: return null
        val headline = m["headline"].str()?.trim().orEmpty()
        if (headline.isEmpty() && m["calls"].list().isEmpty() && m["market"].str().isNullOrBlank()) return null
        val stance = (m["stance"].str() ?: "neutral").lowercase().trim().let { s -> listOf("bullish", "bearish", "mixed", "neutral").firstOrNull { s.contains(it) } ?: "neutral" }
        return TylerTake(v.id, v.title, v.published, headline, stance, m["market"].str()?.trim().orEmpty(),
            m["calls"].list().map { callFrom(it.obj()) }.filter { it.ticker.isNotBlank() || it.name.isNotBlank() }.take(12),
            m["moves"].str()?.trim().orEmpty(), m["watch"].str()?.trim().orEmpty(), m["caveats"].str()?.trim().orEmpty(), keyLines, source, words, now)
    }

    fun callFrom(o: Map<String, Any?>): TylerCall {
        val name = o["name"].str()?.trim().orEmpty()
        var ticker = (o["ticker"].str() ?: "").trim().uppercase().removePrefix("$")
        if (ticker.isEmpty() || ticker.contains(' ')) ticker = tickersIn(name).firstOrNull() ?: ""
        val view = (o["view"].str() ?: "neutral").lowercase().let { s -> listOf("bullish", "bearish", "neutral").firstOrNull { s.contains(it) } ?: "neutral" }
        val action = (o["action"].str() ?: "").lowercase().trim().let { a -> if (a == "empty" || a == "none" || a == "n/a") "" else a }
        fun clean(k: String) = (o[k].str() ?: "").trim().let { if (it.equals("empty", true) || it.equals("n/a", true) || it == "-") "" else it }
        return TylerCall(ticker, name, view, clean("what"), clean("levels"), clean("timeframe"), action)
    }

    /** No AI available: the key quotes and the tickers he names, so the tab is still useful. */
    fun quotesOnly(v: YtVideo, transcript: String?, now: Long): TylerTake {
        val text = transcript ?: ""
        val lines = keyLines(text, 10)
        val tickers = (tickersIn(v.title, caps = true) + tickersIn(text)).distinct().take(12)
        val source = if (transcript.isNullOrBlank()) "description" else "quotes"
        return TylerTake(v.id, v.title, v.published, v.title, "neutral", "",
            tickers.map { TylerCall(it, "", "neutral", "Mentioned — see the quotes below.", "", "", "") }, "", "", "", lines, source,
            text.split(' ').count { it.isNotBlank() }, now)
    }

    /** His most recent call on [symbol] across the given takes (newest first). */
    fun latestOn(takes: List<TylerTake>, symbol: String): Pair<TylerTake, TylerCall>? {
        val s = symbol.uppercase()
        val alt = setOf(s, s.substringBefore('.'), if (s == "GOOG") "GOOGL" else s, if (s == "GOOGL") "GOOG" else s)
        for (t in takes.sortedByDescending { it.published }) {
            t.calls.firstOrNull { it.ticker.uppercase() in alt }?.let { return t to it }
        }
        return null
    }
}
