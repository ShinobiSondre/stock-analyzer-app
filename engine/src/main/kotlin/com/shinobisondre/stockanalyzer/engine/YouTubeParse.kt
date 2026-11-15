package com.shinobisondre.stockanalyzer.engine

import com.shinobisondre.stockanalyzer.engine.MiniJson.list
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import com.shinobisondre.stockanalyzer.engine.MiniJson.str

data class YtVideo(val id: String, val title: String, val published: String, val description: String = "", val short: Boolean = false)

data class CaptionTrack(val baseUrl: String, val lang: String, val asr: Boolean, val plain: Boolean)

data class PlayerInfo(val tracks: List<CaptionTrack>, val title: String, val description: String, val channelId: String, val publishDate: String?, val status: String)

/**
 * Pure parsing for YouTube pages/answers — the same approach as FaithScroll
 * (and yt-dlp / youtube-transcript-api): the video page gives the API key, the
 * player API answering as the Android app lists the caption tracks, and those
 * caption links download without a token.
 */
object YouTubeParse {

    /** Channel RSS feed (latest 15 uploads). */
    fun feed(xml: String): List<YtVideo> = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
        val e = m.groupValues[1]
        val id = Regex("<yt:videoId>([^<]+)</yt:videoId>").find(e)?.groupValues?.get(1) ?: return@mapNotNull null
        val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
        val pub = Regex("<published>([^<]+)</published>").find(e)?.groupValues?.get(1) ?: ""
        val desc = Regex("<media:description>(.*?)</media:description>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
        val link = Regex("<link rel=\"alternate\" href=\"([^\"]+)\"").find(e)?.groupValues?.get(1) ?: ""
        YtVideo(id, title, pub, desc, short = link.contains("/shorts/"))
    }.toList()

    /** The channel's /videos page (fallback when the feed is down): video ids and titles in page order. */
    fun channelPage(html: String, nowMs: Long): List<YtVideo> {
        val out = LinkedHashMap<String, YtVideo>()
        // Classic renderer
        Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\".*?\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"(.*?)\"publishedTimeText\":\\{\"simpleText\":\"([^\"]*)\"", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val id = m.groupValues[1]
                if (id !in out) out[id] = YtVideo(id, jsonUnescape(m.groupValues[2]), relativeToIso(m.groupValues[4], nowMs))
            }
        // Newer lockup view model
        Regex("\"lockupViewModel\":\\{\"contentImage\".*?\"contentId\":\"([A-Za-z0-9_-]{11})\"", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
            val id = m.groupValues[1]
            if (id in out) return@forEach
            val tail = html.substring(m.range.last, minOf(html.length, m.range.last + 6000))
            val title = Regex("\"title\":\\{\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(tail)?.groupValues?.get(1)?.let { jsonUnescape(it) } ?: ""
            val ago = Regex("\"content\":\"([^\"]*\\bago)\"").find(tail)?.groupValues?.get(1) ?: ""
            out[id] = YtVideo(id, title, relativeToIso(ago, nowMs))
        }
        return out.values.toList()
    }

    /** "3 days ago" → ISO date (approximate). */
    fun relativeToIso(text: String, nowMs: Long): String {
        val m = Regex("(\\d+)\\s+(second|minute|hour|day|week|month|year)s?").find(text.lowercase())
        val secs = if (m == null) 0L else {
            val n = m.groupValues[1].toLong()
            n * when (m.groupValues[2]) { "second" -> 1L; "minute" -> 60L; "hour" -> 3600L; "day" -> 86400L; "week" -> 604800L; "month" -> 2592000L; else -> 31536000L }
        }
        return java.time.Instant.ofEpochMilli(nowMs - secs * 1000).toString()
    }

    fun channelIdFromPage(html: String): String? =
        Regex("\"externalId\":\"(UC[A-Za-z0-9_-]{22})\"").find(html)?.groupValues?.get(1)
            ?: Regex("<link rel=\"canonical\" href=\"https://www.youtube.com/channel/(UC[A-Za-z0-9_-]{22})\"").find(html)?.groupValues?.get(1)

    fun apiKey(html: String): String? = Regex("\"INNERTUBE_API_KEY\":\\s*\"([a-zA-Z0-9_-]+)\"").find(html)?.groupValues?.get(1)

    /** Pulls `var NAME = {...};` out of a page by matching braces (the JSON is large). */
    fun extractJson(html: String, name: String): String? {
        val i = html.indexOf(name).takeIf { it >= 0 } ?: return null
        val start = html.indexOf('{', i).takeIf { it >= 0 } ?: return null
        var depth = 0; var inStr = false; var esc = false
        for (j in start until html.length) {
            val c = html[j]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return html.substring(start, j + 1) } }
        }
        return null
    }

    fun player(json: String, plain: Boolean): PlayerInfo? {
        val pr = runCatching { MiniJson.parse(json).obj() }.getOrNull() ?: return null
        val tracks = pr["captions"].obj()["playerCaptionsTracklistRenderer"].obj()["captionTracks"].list().mapNotNull { t ->
            val o = t.obj()
            val url = o["baseUrl"].str() ?: return@mapNotNull null
            CaptionTrack(url, (o["languageCode"].str() ?: "").substringBefore('-').lowercase(), o["kind"].str() == "asr", plain)
        }
        val vd = pr["videoDetails"].obj()
        val mf = pr["microformat"].obj()["playerMicroformatRenderer"].obj()
        return PlayerInfo(tracks, vd["title"].str() ?: "", vd["shortDescription"].str() ?: "", vd["channelId"].str() ?: "",
            mf["publishDate"].str() ?: mf["uploadDate"].str(), pr["playabilityStatus"].obj()["status"].str() ?: "")
    }

    /** English first (hand-made before automatic), otherwise the automatic track (spoken language) to be translated to English. */
    fun pickTrack(tracks: List<CaptionTrack>): Pair<CaptionTrack, String?>? {
        tracks.firstOrNull { it.lang == "en" && !it.asr }?.let { return it to null }
        tracks.firstOrNull { it.lang == "en" && it.asr }?.let { return it to null }
        tracks.firstOrNull { it.asr }?.let { return it to "en" }
        return tracks.firstOrNull()?.let { it to (if (it.lang == "en") null else "en") }
    }

    /** Caption text from either of YouTube's formats (json3 events or the XML <text> list). */
    fun captions(body: String): List<String> {
        val t = body.trimStart()
        if (t.startsWith("{")) {
            val events = runCatching { MiniJson.parse(t).obj()["events"].list() }.getOrDefault(emptyList())
            return events.mapNotNull { e ->
                val segs = e.obj()["segs"].list()
                if (segs.isEmpty()) null else segs.joinToString("") { it.obj()["utf8"].str() ?: "" }.replace("\n", " ").trim().ifBlank { null }
            }
        }
        val xml = Regex("<text start=\"[\\d.]+\"(?: dur=\"[\\d.]+\")?[^>]*>(.*?)</text>", RegexOption.DOT_MATCHES_ALL).findAll(t).map { it.groupValues[1] }.toList()
        if (xml.isNotEmpty()) return xml.map { unescape(unescape(it)).replace("\n", " ").trim() }.filter { it.isNotBlank() }
        // srv3: <p t=".." d=".."><s>word</s>…</p>
        return Regex("<p [^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(t).map { m ->
            unescape(m.groupValues[1].replace(Regex("<[^>]+>"), "")).replace("\n", " ").trim()
        }.filter { it.isNotBlank() }.toList()
    }

    fun unescape(s: String): String {
        var r = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        r = Regex("&#(x?)([0-9a-fA-F]+);").replace(r) { m ->
            val code = if (m.groupValues[1].isNotEmpty()) m.groupValues[2].toIntOrNull(16) else m.groupValues[2].toIntOrNull()
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        return r
    }

    fun jsonUnescape(s: String): String = runCatching { MiniJson.parse("\"$s\"") as String }.getOrDefault(s)
}
