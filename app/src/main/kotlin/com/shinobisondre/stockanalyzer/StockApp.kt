package com.shinobisondre.stockanalyzer

import android.app.Application
import com.shinobisondre.stockanalyzer.data.Repository
import com.shinobisondre.stockanalyzer.data.Store
import com.shinobisondre.stockanalyzer.work.AlertWorker
import com.shinobisondre.stockanalyzer.work.Notifier

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
    }
}
