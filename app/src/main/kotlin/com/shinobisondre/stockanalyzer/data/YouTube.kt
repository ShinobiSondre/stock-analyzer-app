package com.shinobisondre.stockanalyzer.data

import com.shinobisondre.stockanalyzer.engine.TylerTalk
import com.shinobisondre.stockanalyzer.engine.YouTubeParse
import com.shinobisondre.stockanalyzer.engine.YtVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** The channel's latest uploads. Captions and playback come from the FaithScroll code in the video package. */
object YouTube {
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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

    /** Latest uploads of the channel: the RSS feed (exact dates + descriptions), else NewPipe's channel tab like FaithScroll. */
    suspend fun latest(channelId: String = TylerTalk.CHANNEL_ID): List<YtVideo> {
        consent()
        val feed = runCatching { get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId") }.getOrNull()
        val vids = if (feed?.first == 200) YouTubeParse.feed(feed.second) else emptyList()
        if (vids.isNotEmpty()) return vids
        return withContext(Dispatchers.IO) {
            com.shinobisondre.stockanalyzer.video.StreamResolver.ensureInit()
            val info = org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(org.schabi.newpipe.extractor.ServiceList.YouTube, "https://www.youtube.com/channel/$channelId")
            val tab = info.tabs.firstOrNull { it.contentFilters.contains(org.schabi.newpipe.extractor.channel.tabs.ChannelTabs.VIDEOS) } ?: info.tabs.first()
            val items = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(org.schabi.newpipe.extractor.ServiceList.YouTube, tab).relatedItems
            items.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>().mapNotNull { i ->
                val id = Regex("(?:v=|shorts/|youtu\\.be/)([A-Za-z0-9_-]{11})").find(i.url)?.groupValues?.get(1) ?: return@mapNotNull null
                val date = runCatching { i.uploadDate?.offsetDateTime()?.toString() }.getOrNull()
                    ?: YouTubeParse.relativeToIso(i.textualUploadDate ?: "", System.currentTimeMillis())
                YtVideo(id, i.name.orEmpty(), date, short = i.isShortFormContent || "/shorts/" in i.url)
            }
        }
    }
}
