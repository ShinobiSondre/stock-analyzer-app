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
        val s = store.value.settings
        runCatching { AlertWorker.schedule(this, s.alertsEnabled, s.alertIntervalMin) }
    }
}
