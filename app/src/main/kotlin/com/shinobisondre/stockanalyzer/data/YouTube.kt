package com.shinobisondre.stockanalyzer.data

import com.shinobisondre.stockanalyzer.engine.PlayerInfo
import com.shinobisondre.stockanalyzer.engine.TylerTalk
import com.shinobisondre.stockanalyzer.engine.YouTubeParse
import com.shinobisondre.stockanalyzer.engine.YtVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * YouTube without an API key, the FaithScroll way: the channel's RSS feed for the
 * latest uploads, then per video the watch page (API key + caption list) and the
 * player API answering as the Android app, whose caption links download without a token.
 */
object YouTube {
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val ANDROID_VERSION = "20.10.38"
    private const val ANDROID_UA = "com.google.android.youtube/$ANDROID_VERSION (Linux; U; Android 14) gzip"
    /** YouTube's public website key, used when the page didn't include one (consent / "unusual traffic" page). */
    private const val PUBLIC_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    @Volatile private var apiKey: String? = null

    private fun consent() {
        // Skips the EU cookie-consent page (same as a browser that clicked "reject all").
        val c = Cookie.Builder().domain("youtube.com").path("/").name("SOCS").value("CAI").expiresAt(System.currentTimeMillis() + 365L * 86400_000).build()
        Http.cookies.saveFromResponse("https://www.youtube.com/".toHttpUrl(), listOf(c))
    }

    private suspend fun get(url: String, ua: String = DESKTOP_UA): Pair<Int, String> = withContext(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", ua).header("Accept-Language", "en-US,en;q=0.9").build()).execute().use {
            it.code to (it.body?.string() ?: "")
        }
    }

    /** Latest uploads of the channel (feed first, the /videos page if the feed is down). */
    suspend fun latest(channelId: String = TylerTalk.CHANNEL_ID): List<YtVideo> {
        consent()
        val feed = runCatching { get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId") }.getOrNull()
        val vids = if (feed?.first == 200) YouTubeParse.feed(feed.second) else emptyList()
        if (vids.isNotEmpty()) return vids
        val page = get("https://www.youtube.com/channel/$channelId/videos")
        if (page.first != 200) throw HttpException(page.first, "YouTube answered HTTP ${page.first}")
        return YouTubeParse.channelPage(page.second, System.currentTimeMillis())
    }

    private suspend fun androidPlayer(videoId: String, key: String): String? = withContext(Dispatchers.IO) {
        val body = """{"context":{"client":{"clientName":"ANDROID","clientVersion":"$ANDROID_VERSION","androidSdkVersion":34,"hl":"en"}},"videoId":"$videoId"}"""
        runCatching {
            Http.client.newCall(
                Request.Builder().url("https://www.youtube.com/youtubei/v1/player?key=$key")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", ANDROID_UA).header("Accept-Language", "en-US").build()
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()
    }

    /** Video details and the transcript pieces (null when YouTube offers no captions or refuses them). */
    suspend fun transcript(videoId: String): Pair<PlayerInfo?, List<String>?> {
        consent()
        val html = runCatching { get("https://www.youtube.com/watch?v=$videoId").second }.getOrDefault("")
        YouTubeParse.apiKey(html)?.let { apiKey = it }
        val page = YouTubeParse.extractJson(html, "ytInitialPlayerResponse")?.let { YouTubeParse.player(it, plain = false) }
        val app = androidPlayer(videoId, apiKey ?: PUBLIC_KEY)?.let { YouTubeParse.player(it, plain = true) }
        val info = when {
            app != null && app.title.isNotBlank() -> app.copy(publishDate = page?.publishDate ?: app.publishDate)
            else -> page
        }
        val tracks = app?.tracks.orEmpty().ifEmpty { page?.tracks.orEmpty() }
        val (track, tlang) = YouTubeParse.pickTrack(tracks) ?: return info to null
        val t = tlang?.let { "&tlang=$it" } ?: ""
        val urls = listOfNotNull(
            if (track.plain) track.baseUrl.replace("&fmt=srv3", "") + t else null,
            page?.tracks?.firstOrNull { it.lang == track.lang && it.asr == track.asr }?.baseUrl?.let { it.replace(Regex("&fmt=[^&]*"), "") + "&fmt=json3" + t },
        ).distinct()
        for (u in urls) {
            val body = runCatching { get(u, if (u.contains("fmt=json3")) DESKTOP_UA else ANDROID_UA).second }.getOrDefault("")
            val pieces = YouTubeParse.captions(body)
            if (pieces.isNotEmpty()) return info to pieces
        }
        return info to null
    }
}
