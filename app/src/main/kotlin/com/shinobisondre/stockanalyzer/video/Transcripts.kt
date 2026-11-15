// Copied from FaithScroll (ShinobiSondre/faith-scroll) so captions and playback work exactly the same.
package com.shinobisondre.stockanalyzer.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** One line of captions. */
data class Cue(val start: Long, val end: Long, val text: String)

/** Captions for a video, in the original language. */
data class Transcript(val lang: String, val cues: List<Cue>, val translated: Boolean, val spoken: String = "")

object Transcripts {
    private val cache = ConcurrentHashMap<String, Transcript>()
    private val missing = ConcurrentHashMap<String, Long>()

    /** Tyler speaks English: English captions are what we want. */
    fun target(): String = "en"

    /** Original captions (best track: manual before automatic, app language before others). */
    suspend fun original(videoId: String, urgent: Boolean = false): Transcript? = withContext(Dispatchers.IO) {
        cache["o|$videoId"]?.let { return@withContext it }
        missing[videoId]?.let { if (System.currentTimeMillis() - it < 10 * 60_000) return@withContext null }
        val t0 = System.currentTimeMillis()
        val info = WatchPage.info(videoId)
        if (info == null || info.tracks.isEmpty()) {
            missing[videoId] = System.currentTimeMillis()
            if (info == null) Diag.log("subs", "$videoId: page unreadable")
            return@withContext null
        }
        val want = target()
        val spoken = WatchPage.spoken(info)
        // Prefer hand-made captions in the app language, then YouTube's automatic ones (= spoken language).
        val chosen = info.tracks.firstOrNull { it.lang == want && it.kind != "asr" }
            ?: info.tracks.firstOrNull { it.kind == "asr" }
            ?: info.tracks.first()
        val cues = WatchPage.fetch(videoId, chosen)
        if (cues.isNullOrEmpty()) { missing[videoId] = System.currentTimeMillis(); return@withContext null }
        Transcript(chosen.lang, cues, false, spoken).also {
            cache["o|$videoId"] = it
            Diag.log("subs", "$videoId: ${cues.size} caption lines (${chosen.lang}${if (chosen.kind == "asr") ", auto" else ""}), spoken=${spoken.ifBlank { "?" }} in ${(System.currentTimeMillis() - t0) / 1000.0} s")
        }
    }

    /**
     * YouTube's own player fetches captions with a valid token. When the fallback player does that,
     * we read the same file (as JSON) so "What do they mean here?" and subtitles have a transcript.
     */
    suspend fun captureTimedText(videoId: String, url: String) = withContext(Dispatchers.IO) {
        if (cache["o|$videoId"] != null) return@withContext
        val u = android.net.Uri.parse(url)
        val b = u.buildUpon().clearQuery()
        for (k in u.queryParameterNames) if (k != "fmt" && k != "tlang") u.getQueryParameters(k).forEach { b.appendQueryParameter(k, it) }
        b.appendQueryParameter("fmt", "json3")
        val body = WatchPage.plainGet(b.build().toString()) ?: run { Diag.log("subs", "$videoId: captured captions download failed"); return@withContext }
        val cues = runCatching {
            val events = org.json.JSONObject(body).optJSONArray("events") ?: org.json.JSONArray()
            (0 until events.length()).mapNotNull { i ->
                val e = events.getJSONObject(i)
                val segs = e.optJSONArray("segs") ?: return@mapNotNull null
                val text = (0 until segs.length()).joinToString("") { segs.getJSONObject(it).optString("utf8") }.replace("\n", " ").trim()
                if (text.isBlank()) null else Cue(e.optLong("tStartMs"), e.optLong("tStartMs") + e.optLong("dDurationMs", 2000), text)
            }
        }.getOrDefault(emptyList())
        if (cues.isEmpty()) { Diag.log("subs", "$videoId: captured captions empty"); return@withContext }
        val lang = (u.getQueryParameter("lang") ?: "en").substringBefore('-').let { if (it == "nb" || it == "nn") "no" else it }
        cache["o|$videoId"] = Transcript(lang, cues, false)
        Diag.log("subs", "$videoId: transcript captured from YouTube's player (${cues.size} lines, $lang)")
    }

    fun hasTranscript(videoId: String) = cache["o|$videoId"] != null
    fun cached(videoId: String): Transcript? = cache["o|$videoId"]
    /** Forget a "no captions" result so the next try asks YouTube again. */
    fun retry(videoId: String) { missing.remove(videoId) }
}
