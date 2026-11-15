// Copied from FaithScroll (ShinobiSondre/faith-scroll) — there it runs NB-Whisper for Norwegian;
// here the same whisper.cpp code runs OpenAI's English "base.en" model.
package com.shinobisondre.stockanalyzer.video

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * English speech recognition on the phone: Whisper base.en (quantised) run with whisper.cpp.
 * One-time ~57 MB download, then fully offline.
 */
object Whisper {
    private const val FILE = "ggml-base.en-q5_1.bin"
    private val URLS = listOf(
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$FILE",
    )
    private val lock = Mutex()
    @Volatile private var ctx: Long = 0
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val loaded by lazy { runCatching { System.loadLibrary("sawhisper"); true }.getOrElse { Diag.err("whisper", "native library", it); false } }

    private external fun nativeInit(path: String): Long
    private external fun nativeFree(ctx: Long)
    private external fun nativeTranscribe(ctx: Long, samples: FloatArray, lang: String, threads: Int, audioCtx: Int): ByteArray
    private external fun nativeNewState(ctx: Long): Long
    private external fun nativeTranscribeState(ctx: Long, state: Long, samples: FloatArray, lang: String, threads: Int, audioCtx: Int): ByteArray

    /** Independent workspaces on the one loaded model, so pieces can be transcribed in parallel. */
    const val WORKERS = 2
    private val states = Channel<Long>(WORKERS)

    fun file(c: Context) = File(c.filesDir, "whisper/$FILE")
    fun ready(c: Context) = file(c).let { it.exists() && it.length() > 40_000_000 }

    /** Loads (downloading once if needed) the model. [onProgress] gets 0..1 while downloading. */
    suspend fun load(c: Context, onProgress: (Float) -> Unit = {}): Boolean = lock.withLock {
        if (ctx != 0L) return@withLock true
        if (!loaded) return@withLock false
        withContext(Dispatchers.IO) {
            val f = file(c)
            if (!ready(c)) download(f, onProgress)
            ctx = nativeInit(f.absolutePath)
            if (ctx == 0L) Diag.log("whisper", "model failed to load")
            else {
                repeat(WORKERS) { nativeNewState(ctx).takeIf { it != 0L }?.let { st -> states.trySend(st) } }
                Diag.log("whisper", "English model ready ($WORKERS parallel workers)")
            }
        }
        ctx != 0L
    }

    private fun download(f: File, onProgress: (Float) -> Unit) {
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, "$FILE.part")
        var last: Throwable? = null
        for (url in URLS) {
            val ok = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val total = r.body!!.contentLength().coerceAtLeast(1)
                    var got = 0L
                    r.body!!.byteStream().use { input -> part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            onProgress(got.toFloat() / total)
                        }
                    } }
                }
                part.renameTo(f)
            }.onFailure { last = it; Diag.err("whisper", "model from ${android.net.Uri.parse(url).host}", it) }.isSuccess
            if (ok) { Diag.log("whisper", "English model from ${android.net.Uri.parse(url).host}"); return }
        }
        throw last ?: IllegalStateException("Model download failed")
    }

    /** Parallel-safe: borrows one of the worker states for this piece of audio (16 kHz mono). */
    suspend fun transcribePiece(pcm: ShortArray, n: Int, lang: String = "en", threads: Int): List<Triple<Long, Long, String>> {
        if (ctx == 0L || n < 1600) return emptyList()
        val st = states.receive()
        try {
            val f = FloatArray(n) { pcm[it] / 32768f }
            // Window sized to the piece with a generous margin: much faster than the full 30 s, without
            // the looping that a tight trim caused.
            val audioCtx = (n / 16000.0 * 50 + 160).toInt().coerceIn(512, 1500)
            val t0 = System.currentTimeMillis()
            val text = withContext(Dispatchers.Default) { nativeTranscribeState(ctx, st, f, lang, threads, audioCtx) }.toString(Charsets.UTF_8)
            Diag.log("whisper", "${n / 16000} s piece in ${(System.currentTimeMillis() - t0) / 1000.0} s")
            return parse(text)
        } finally { states.send(st) }
    }

    private fun parse(text: String) = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size < 3) return@mapNotNull null
        val t = parts[2].trim()
        if (t.isBlank() || t.startsWith("[") || t.startsWith("(")) null // "[Music]", "(laughs)"
        else Triple(parts[0].toLongOrNull() ?: 0L, parts[1].toLongOrNull() ?: 0L, t)
    }.toList()
}
