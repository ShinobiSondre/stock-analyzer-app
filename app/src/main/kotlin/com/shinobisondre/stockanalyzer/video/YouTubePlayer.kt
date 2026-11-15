// Copied from FaithScroll (ShinobiSondre/faith-scroll): YouTube's own player inside the app, used when
// the native player can't get the streams. It also reveals the caption link YouTube's player requests.
package com.shinobisondre.stockanalyzer.video

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private class PlayerBridge(val onState: (Int) -> Unit, val onError: (Int) -> Unit, val onTime: (Long) -> Unit = {}) {
    @JavascriptInterface fun state(s: Int) = onState(s)
    @JavascriptInterface fun error(code: Int) = onError(code)
    @JavascriptInterface fun time(ms: Double) = onTime(ms.toLong())
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun YouTubePlayer(
    videoId: String,
    paused: Boolean,
    muted: Boolean,
    onPlaying: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
    controls: Boolean = false,
    startSec: Int = 0,
    captions: Boolean = false, // show YouTube's captions, translated into the app language
    onTimedText: (String) -> Unit = {}, // the caption file YouTube's player downloaded
    onTime: (Long) -> Unit = {},
    onEndedEmbed: () -> Unit = {}
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val lang = Transcripts.target()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    AndroidView(
        modifier = modifier,
        factory = { c ->
            WebView(c).apply {
                setBackgroundColor(AColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = object : android.webkit.WebViewClient() {
                    // Notice when YouTube's player fetches captions (the URL carries its token).
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        val u = request.url.toString()
                        if (u.contains("/api/timedtext")) post { onTimedText(u) }
                        return null
                    }
                }
                if (!controls) setOnTouchListener { _, _ -> true } // feed: gestures handled by Compose
                addJavascriptInterface(
                    PlayerBridge(
                        onState = { s -> if (s == 1) post { onPlaying() }; if (s == 0) post { onEndedEmbed() } },
                        onError = { post { onError() } },
                        onTime = { ms -> post { onTime(ms) } }
                    ),
                    "Android"
                )
                loadDataWithBaseURL("https://www.youtube.com", playerHtml(videoId, muted, controls, startSec, captions, lang), "text/html", "utf-8", null)
                webView = this
            }
        },
        onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() }
    )

    LaunchedEffect(paused, webView) { webView?.evaluateJavascript(if (paused) "pauseV()" else "playV()", null) }
    LaunchedEffect(muted, webView) { webView?.evaluateJavascript("setMuted(${muted})", null) }
    LaunchedEffect(captions, webView) { webView?.evaluateJavascript("setCaptions(${captions})", null) }

    DisposableEffect(lifecycle, webView) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> { webView?.evaluateJavascript("pauseV()", null); webView?.onPause() }
                Lifecycle.Event.ON_RESUME -> { webView?.onResume(); if (!paused) webView?.evaluateJavascript("playV()", null) }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}

private fun playerHtml(id: String, muted: Boolean, controls: Boolean, startSec: Int = 0, captions: Boolean = false, lang: String = "en"): String {
    val ctl = if (controls) 1 else 0
    val loop = if (controls) "" else "loop:1,playlist:'$id',"
    return """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;padding:0;height:100%;background:transparent;overflow:hidden}#p{position:absolute;inset:0;width:100%;height:100%}</style>
</head><body><div id="p"></div>
<script>
var player,ready=false,want='play',muted=$muted,capsOn=$captions,lang='$lang';
var s=document.createElement('script');s.src='https://www.youtube.com/iframe_api';document.head.appendChild(s);
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
    playerVars:{autoplay:1,playsinline:1,controls:$ctl,rel:0,modestbranding:1,fs:0,iv_load_policy:3,${loop}disablekb:1,start:$startSec,cc_load_policy:${if (captions) 1 else 0},cc_lang_pref:lang,hl:lang},
    events:{
      onReady:function(e){ready=true;if(muted)e.target.mute();else e.target.unMute();if(want=='play')e.target.playVideo();
        applyCaptions();setInterval(function(){try{Android.time(player.getCurrentTime()*1000);}catch(x){}},500);},
      onApiChange:function(){applyCaptions();},
      onStateChange:function(e){Android.state(e.data);},
      onError:function(e){Android.error(e.data);}
    }});
}
function playV(){want='play';if(ready)player.playVideo();}
function pauseV(){want='pause';if(ready)player.pauseVideo();}
function setMuted(m){muted=m;if(ready){if(m)player.mute();else player.unMute();}}
function applyCaptions(){
  if(!ready)return;
  try{
    if(!capsOn){player.unloadModule('captions');return;}
    player.loadModule('captions');
    var tl=player.getOption('captions','tracklist')||[];
    if(!tl.length)return;
    var t=null;
    for(var i=0;i<tl.length;i++){if((tl[i].languageCode||'').split('-')[0]==lang){t=tl[i];break;}}
    if(t){player.setOption('captions','track',{languageCode:t.languageCode});}
    else{player.setOption('captions','track',{languageCode:tl[0].languageCode,translationLanguage:{languageCode:lang}});}
  }catch(x){}
}
function setCaptions(on){capsOn=on;applyCaptions();}
</script></body></html>
"""
}

