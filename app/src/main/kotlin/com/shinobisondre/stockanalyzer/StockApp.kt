package com.shinobisondre.stockanalyzer

import android.app.Application
import com.shinobisondre.stockanalyzer.data.Repository
import com.shinobisondre.stockanalyzer.data.Store
import com.shinobisondre.stockanalyzer.work.AlertWorker
import com.shinobisondre.stockanalyzer.work.Notifier

import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class StockApp : Application() {
    lateinit var store: Store
        private set
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        repo = Repository(this, store)
        Notifier.createChannels(this)
        // Video playback + captions, set up like FaithScroll: stream cache, and proof-of-origin tokens
        // so YouTube's bot check doesn't refuse streams and captions.
        runCatching { com.shinobisondre.stockanalyzer.video.StreamResolver.setup(this) }
        com.shinobisondre.stockanalyzer.potoken.AppContext.set(this)
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
            .setPoTokenProvider(com.shinobisondre.stockanalyzer.potoken.PoTokenProviderImpl)
        val s = store.value.settings
        runCatching { AlertWorker.schedule(this, s.alertsEnabled, s.alertIntervalMin) }
        // Keep the instant-alerts server in step with your stocks and alert settings.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            store.state.map { st -> Triple(st.holdings.map { it.symbol }, st.watchlist, st.settings) }
                .distinctUntilChanged().debounce(4000)
                .collect { runCatching { com.shinobisondre.stockanalyzer.push.Push.register(this@StockApp) } }
        }
    }
}
