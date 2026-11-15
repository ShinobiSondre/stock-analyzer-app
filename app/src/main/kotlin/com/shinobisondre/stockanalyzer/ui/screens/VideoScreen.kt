package com.shinobisondre.stockanalyzer.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinobisondre.stockanalyzer.engine.YtVideo
import com.shinobisondre.stockanalyzer.ui.MainViewModel
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import com.shinobisondre.stockanalyzer.video.NativeVideo
import com.shinobisondre.stockanalyzer.video.PlaybackState
import com.shinobisondre.stockanalyzer.video.Transcripts
import com.shinobisondre.stockanalyzer.video.YouTubePlayer
import kotlinx.coroutines.launch

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The video itself, played inside the app like FaithScroll does: our own player
 * (ExoPlayer on the direct streams NewPipeExtractor finds), and YouTube's own
 * player as the fallback, continuing from the same second.
 */
@Composable
fun TylerVideo(vm: MainViewModel, videoId: String, modifier: Modifier = Modifier) {
    val playback = remember(videoId) { PlaybackState() }
    val scope = rememberCoroutineScope()
    var embedPos by remember(videoId) { mutableStateOf(0L) }
    var embedFailed by remember(videoId) { mutableStateOf(false) }
    // Continue where this video was left (switching to / from full screen recreates the player).
    LaunchedEffect(videoId, playback.ready) {
        if (playback.ready) vm.videoPos[videoId]?.let { if (it > 2_000) playback.seekTo(it) }
    }
    DisposableEffect(videoId) {
        onDispose { vm.videoPos[videoId] = if (playback.failed) embedPos else playback.position }
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            !playback.failed -> {
                NativeVideo(videoId = videoId, paused = false, muted = false, loop = false, fill = false, state = playback,
                    modifier = Modifier.fillMaxSize(), controller = true)
                if (!playback.ready) CircularProgressIndicator(color = Palette.accent)
            }
            !embedFailed -> YouTubePlayer(
                videoId = videoId, paused = false, muted = false,
                onPlaying = {}, onError = { embedFailed = true },
                modifier = Modifier.fillMaxSize(), controls = true,
                startSec = remember(videoId) { ((vm.videoPos[videoId] ?: playback.position) / 1000).toInt() },
                onTimedText = { url -> scope.launch { Transcripts.captureTimedText(videoId, url); vm.onCaptionsCaptured(videoId) } },
                onTime = { embedPos = it },
            )
            else -> Text("This video can't be played in the app.", color = Color.White, fontSize = 14.sp)
        }
    }
}

/** Video on top, Tyler's summary of it underneath. Full screen hides everything but the video. */
@Composable
fun VideoScreen(vm: MainViewModel, videoId: String, showClose: Boolean) {
    val t by vm.tyler.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val full by vm.videoFull.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val video: YtVideo? = t.videos.firstOrNull { it.id == videoId }
    val take = t.takes[videoId]
    BackHandler { if (full) vm.videoFull.value = false else vm.stopWatching() }

    if (full) {
        FullScreenVideo(vm, videoId)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box {
            TylerVideo(vm, videoId, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            if (showClose) IconButton(onClick = { vm.stopWatching() }, modifier = Modifier.padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))) {
                Icon(Icons.Filled.Close, "Close video", tint = Color.White)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text(video?.title ?: take?.title ?: "", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            Text(ytDate(video?.published ?: take?.published ?: ""), color = Palette.muted, fontSize = 12.sp)
            Row {
                TextButton(onClick = { vm.videoFull.value = true }) { Text("Full screen") }
                TextButton(onClick = { runCatching { uri.openUri("https://www.youtube.com/watch?v=$videoId") } }) { Text("Open in YouTube") }
            }
            when {
                take != null -> TakeCard(take, st.holdings.map { it.symbol }.toSet(), Modifier.padding(vertical = 6.dp), startOpen = true, showThumb = false,
                    onTicker = { vm.open(it) }, onRetry = { vm.resummarise(videoId) }, onWatch = null)
                videoId in t.working -> Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 10.dp), color = Palette.accent)
                    Text(t.working[videoId] ?: "", color = Palette.muted, fontSize = 13.sp)
                }
                else -> TextButton(onClick = { vm.resummarise(videoId) }) { Text("Summarise this video") }
            }
        }
    }
}

/** Landscape, no system bars, nothing but the video. */
@Composable
private fun FullScreenVideo(vm: MainViewModel, videoId: String) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    DisposableEffect(Unit) {
        val ctl = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        TylerVideo(vm, videoId, Modifier.fillMaxSize())
        IconButton(onClick = { vm.videoFull.value = false }, modifier = Modifier.statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))) {
            Icon(Icons.Filled.Close, "Exit full screen", tint = Color.White)
        }
    }
}
