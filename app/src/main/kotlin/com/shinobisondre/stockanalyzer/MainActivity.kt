package com.shinobisondre.stockanalyzer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.shinobisondre.stockanalyzer.ui.AppRoot
import com.shinobisondre.stockanalyzer.ui.FoldInfo
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.Tab
import com.shinobisondre.stockanalyzer.ui.theme.StockTheme
import com.shinobisondre.stockanalyzer.update.Updater
import com.shinobisondre.stockanalyzer.work.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val fold = MutableStateFlow(FoldInfo())

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        // Hinge / posture of the Z Fold: drives the two-pane split and tabletop mode.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                    val f = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    fold.value = if (f == null) FoldInfo() else FoldInfo(
                        present = true,
                        vertical = f.orientation == FoldingFeature.Orientation.VERTICAL,
                        separating = f.isSeparating,
                        halfOpened = f.state == FoldingFeature.State.HALF_OPENED,
                        left = f.bounds.left, top = f.bounds.top, right = f.bounds.right, bottom = f.bounds.bottom,
                    )
                }
            }
        }

        setContent {
            StockTheme {
                val f by fold.collectAsStateWithLifecycle()
                AppRoot(vm, f)
            }
        }

        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(this) && !vm.state.value.onboarded) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.markOnboarded()
    }

    override fun onStart() {
        super.onStart()
        Updater.foreground = true
        vm.cancelBackgroundInstall()
    }

    override fun onStop() {
        super.onStop()
        Updater.foreground = false
        if (!isChangingConfigurations) vm.scheduleBackgroundInstall()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_SYMBOL)?.let { vm.open(it) }
        // A screenshot shared from Nordnet (or the gallery): import the holdings on it.
        if (intent.type?.startsWith("image/") == true) {
            val uris: List<Uri> = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(
                    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                )
                Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
                else -> emptyList()
            }
            if (uris.isNotEmpty()) {
                vm.close()
                vm.tab.value = Tab.PORTFOLIO
                vm.importScreenshots(uris)
            }
        }
        if (intent.getBooleanExtra(EXTRA_UPDATE, false)) { vm.tab.value = Tab.SETTINGS; vm.checkUpdate() }
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
        const val EXTRA_UPDATE = "update"
    }
}
