// Adapted from FaithScroll's Asr.kt (ShinobiSondre/faith-scroll): same audio download, decoding,
// 15-second pieces, two parallel workers, Whisper with Vosk as the backup. FaithScroll follows the
// playhead; here the whole video is transcribed so it can be summarised.
package com.shinobisondre.stockanalyzer.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Listens to a whole video on the phone and writes it down (for videos YouTube won't give captions for). */
object Asr {
    private const val RATE = 16000
    private const val PIECE = 15_000L // long enough for Whisper to understand the context
    /** Longer videos (livestreams) are listened to up to this point. */
    private const val MAX_MS = 75 * 60_000L

    // FaithScroll's English engine, used if Whisper can't run. GitHub copy first, the original site as backup.
    private val VOSK_URLS = listOf(
        "https://github.com/ShinobiSondre/faith-scroll/releases/download/models/vosk-model-small-en-us-0.15.zip",
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
    )
    private const val VOSK_DIR = "vosk-model-small-en-us-0.15"
    private val modelLock = Mutex()
    @Volatile private var vosk: Model? = null
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    /**
     * The whole video as caption lines, or null if its audio can't be fetched. [onStage] gets
     * plain progress text ("Listening to the video… 40%").
     */
    suspend fun transcribeVideo(ctx: Context, videoId: String, onStage: (String) -> Unit): List<Cue>? = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        // 1) The speech engine (one-time download).
        onStage("Getting the speech model ready…")
        val whisperOk = runCatching {
            Whisper.load(app) { p -> onStage("Downloading the speech model (one time, ~57 MB)… ${(p * 100).toInt()}%") }
        }.onFailure { Diag.err("asr", "whisper", it) }.getOrDefault(false)
        val voskModel: Model? = if (whisperOk) null else runCatching { voskModel(app, onStage) }.onFailure { Diag.err("asr", "vosk model", it) }.getOrNull()
        if (!whisperOk && voskModel == null) { Diag.log("asr", "$videoId: no speech engine"); return@withContext null }

        // 2) The audio track (small, fetched in parallel pieces like FaithScroll).
        onStage("Downloading the audio…")
        val dir = File(app.cacheDir, "asr").apply { mkdirs() }
        val audio = runCatching { downloadAudio(videoId, dir) }.onFailure { Diag.err("asr", "$videoId audio", it) }.getOrNull()
            ?: return@withContext null
        try {
            // 3) Listen: 15-second pieces, two at a time, each worker with its own decoder.
            val first = openReader(audio)
            val duration = minOf(first.durationMs.takeIf { it in 1 until Long.MAX_VALUE } ?: MAX_MS, MAX_MS)
            first.close()
            val pieces = ((duration + PIECE - 1) / PIECE).toInt()
            val next = AtomicInteger(0)
            val done = AtomicInteger(0)
            val engine = if (whisperOk) "Whisper" else "Vosk"
            Diag.log("asr", "$videoId: listening to ${duration / 60_000} min with $engine ($pieces pieces)")
            onStage("Listening to the video… 0%")
            val t0 = System.currentTimeMillis()
            val cues = coroutineScope {
                (0 until Whisper.WORKERS).map {
                    async(Dispatchers.Default) {
                        val out = mutableListOf<Cue>()
                        val reader = openReader(audio)
                        try {
                            val pcm = ShortArray((RATE * (PIECE + 1000) / 1000).toInt())
                            while (true) {
                                coroutineContext.ensureActive()
                                val i = next.getAndIncrement()
                                if (i >= pieces) break
                                val start = i * PIECE
                                val n = reader.read(start, start + PIECE, pcm)
                                if (n > 0) out += if (whisperOk) {
                                    Whisper.transcribePiece(pcm, n, "en", threads = 3).filterNot { looksLikeNoise(it.third) }
                                        .flatMap { (a, b, t) -> splitLine(start + a, start + b, collapseRepeats(t)) }
                                } else voskPiece(voskModel!!, pcm, n, start)
                                val d = done.incrementAndGet()
                                onStage("Listening to the video… ${d * 100 / pieces}%")
                            }
                        } finally { runCatching { reader.close() } }
                        out
                    }
                }.awaitAll().flatten().sortedBy { it.start }
            }
            Diag.log("asr", "$videoId: ${cues.size} lines in ${(System.currentTimeMillis() - t0) / 1000} s")
            cues.ifEmpty { null }
        } finally {
            audio.delete()
        }
    }

    // ---- Vosk (backup engine) ----

    private suspend fun voskModel(ctx: Context, onStage: (String) -> Unit): Model = modelLock.withLock {
        vosk ?: withContext(Dispatchers.IO) {
            val dir = File(ctx.filesDir, "vosk/$VOSK_DIR")
            if (!dir.isDirectory) {
                val root = File(ctx.filesDir, "vosk").apply { mkdirs() }
                val tmp = File(root, "model.zip")
                var lastError: Throwable? = null
                val ok = VOSK_URLS.any { url ->
                    runCatching { downloadFrom(url, tmp) { p -> onStage("Downloading the speech model (one time, ~40 MB)… ${(p * 100).toInt()}%") }; true }
                        .getOrElse { lastError = it; Diag.err("asr", "speech model from ${android.net.Uri.parse(url).host}", it); false }
                }
                if (!ok) throw lastError ?: IllegalStateException("Model download failed")
                unzip(tmp, root)
            }
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(dir.absolutePath)
        }.also { vosk = it }
    }

    private fun downloadFrom(url: String, tmp: File, onProgress: (Float) -> Unit) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("Model download failed: HTTP ${r.code}")
            val total = r.body!!.contentLength().coerceAtLeast(1)
            var got = 0L
            r.body!!.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        onProgress(got.toFloat() / total)
                    }
                }
            }
        }
    }

    private fun unzip(tmp: File, root: File) {
        ZipInputStream(tmp.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val f = File(root, e.name)
                if (!f.canonicalPath.startsWith(root.canonicalPath)) continue // no paths outside the folder
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
            }
        }
        tmp.delete()
    }

    private fun voskPiece(m: Model, pcm: ShortArray, n: Int, startMs: Long): List<Cue> {
        val rec = Recognizer(m, RATE.toFloat()).apply { setWords(true) }
        try {
            rec.acceptWaveForm(pcm, n)
            val words = JSONObject(rec.finalResult).optJSONArray("result") ?: return emptyList()
            val list = (0 until words.length()).map { words.getJSONObject(it) }
            val out = mutableListOf<Cue>()
            var cur = mutableListOf<JSONObject>()
            for (w in list) {
                val gap = cur.lastOrNull()?.let { w.getDouble("start") - it.getDouble("end") } ?: 0.0
                if (cur.size >= 7 || gap > 0.7) { out += toCue(cur, startMs); cur = mutableListOf() }
                cur += w
            }
            if (cur.isNotEmpty()) out += toCue(cur, startMs)
            return out
        } finally { rec.close() }
    }

    private fun toCue(words: List<JSONObject>, offsetMs: Long): Cue = Cue(
        start = offsetMs + (words.first().getDouble("start") * 1000).toLong(),
        end = offsetMs + (words.last().getDouble("end") * 1000).toLong(),
        text = words.joinToString(" ") { it.getString("word") }.replaceFirstChar { it.uppercase() },
    )

    // ---- Reading audio ----

    /** Decodes a stretch of the audio track to 16 kHz mono. */
    class AudioReader(private val ex: MediaExtractor, private val codec: MediaCodec, val durationMs: Long, var srcRate: Int, var channels: Int) {
        fun read(fromMs: Long, toMs: Long, out: ShortArray): Int {
            ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()
            val info = MediaCodec.BufferInfo()
            var n = 0
            var inputDone = false
            val endUs = toMs * 1000
            while (n < out.size) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0 || ex.sampleTime > endUs + 200_000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else { codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.let { srcRate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = buf.remaining() / channels
                    // Pick the samples of this buffer that fall inside [from, to), mixed to mono at 16 kHz.
                    var k = 0
                    while (true) {
                        val tUs = info.presentationTimeUs + k * 1_000_000L / RATE
                        val src = ((tUs - info.presentationTimeUs) * srcRate / 1_000_000L).toInt()
                        if (src >= frames || n >= out.size) break
                        if (tUs >= fromMs * 1000 && tUs < endUs) {
                            var s = 0
                            for (c in 0 until channels) s += buf.get(src * channels + c)
                            out[n++] = (s / channels).toShort()
                        }
                        k++
                    }
                    val bufferEnd = info.presentationTimeUs + frames * 1_000_000L / srcRate
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || bufferEnd >= endUs) break
                }
            }
            return n
        }
        fun close() { runCatching { codec.stop() }; codec.release(); ex.release() }
    }

    private fun openReader(f: File): AudioReader {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else Long.MAX_VALUE
        return AudioReader(ex, codec, dur, fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
    }

    /** Downloads a video's audio track into [dir] (copied from FaithScroll). */
    suspend fun downloadAudio(videoId: String, dir: File): File? = withContext(Dispatchers.IO) {
        val f = File(dir, "$videoId.m4a")
        if (f.exists() && f.length() > 10_000) return@withContext f
        val r = StreamResolver.resolve(videoId) ?: run { Diag.log("asr", "$videoId: no streams"); return@withContext null }
        // A small, widely decodable audio track (AAC-LC ~128k if the tiny HE-AAC one is all there is otherwise).
        val tracks = StreamResolver.preferredAudio(r).filter { it.original || it.lang == "en" }.ifEmpty { StreamResolver.preferredAudio(r) }
        val url = (tracks.filter { it.bitrate >= 64 }.minByOrNull { it.bitrate } ?: tracks.minByOrNull { it.bitrate })?.url
            ?: r.muxed.minByOrNull { it.height }?.url
            ?: run { Diag.log("asr", "$videoId: no audio track"); return@withContext null }
        Diag.log("asr", "$videoId: downloading audio")
        val part = File(dir, "$videoId.part")
        val clen = android.net.Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()
        if (clen != null && clen in 1..(150L * 1024 * 1024)) {
            // Size known: fetch 1 MB pieces, 4 at a time (much faster than one after another).
            val piece = 1L * 1024 * 1024
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            java.io.RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(clen)
                coroutineScope {
                    (0 until clen step piece).map { start ->
                        launch(Dispatchers.IO) {
                            gate.acquire()
                            try {
                                val end = minOf(start + piece, clen) - 1
                                val bytes = http.newCall(Request.Builder().url("$url&range=$start-$end").header("User-Agent", "Mozilla/5.0").build())
                                    .execute().use { resp -> if (!resp.isSuccessful) error("HTTP ${resp.code}") else resp.body!!.bytes() }
                                synchronized(raf) { raf.seek(start); raf.write(bytes) }
                            } finally { gate.release() }
                        }
                    }.forEach { it.join() }
                }
            }
            part.renameTo(f)
            Diag.log("asr", "$videoId: audio ${f.length() / 1024} KB (parallel)")
            return@withContext f
        }
        part.outputStream().use { out ->
            var pos = 0L
            val chunk = 2L * 1024 * 1024
            val cap = 80L * 1024 * 1024
            while (pos < (clen ?: cap) && pos < cap) {
                coroutineContext.ensureActive()
                val end = minOf(pos + chunk, clen ?: Long.MAX_VALUE) - 1
                val req = Request.Builder().url("$url&range=$pos-$end").header("User-Agent", "Mozilla/5.0").build()
                val bytes = http.newCall(req).execute().use { resp ->
                    if (resp.code == 416) null else if (!resp.isSuccessful) error("HTTP ${resp.code}") else resp.body?.bytes()
                } ?: break
                if (bytes.isEmpty()) break
                out.write(bytes); pos += bytes.size
                if (bytes.size < chunk) break
            }
        }
        part.renameTo(f)
        Diag.log("asr", "$videoId: audio ${f.length() / 1024} KB")
        f
    }

    // ---- Cleaning Whisper's output (from FaithScroll) ----

    /** Lines that are just repeated syllables or symbols. */
    fun looksLikeNoise(text: String): Boolean {
        val words = text.lowercase().split(Regex("\\s+")).map { it.trim('.', ',', '!', '?', '\'', '"', '-', '…') }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val letters = text.count { it.isLetter() }
        if (letters < text.length * 0.5) return true
        if (words.size >= 4 && words.toSet().size.toFloat() / words.size < 0.4f) return true
        if (words.size >= 4 && words.count { it.length <= 2 } > words.size * 0.7) return true
        return false
    }

    /** Drops a phrase repeated 3+ times in a row. */
    private fun collapseRepeats(text: String): String {
        val w = text.split(" ").toMutableList()
        for (len in 1..4) {
            var i = 0
            while (i + len * 3 <= w.size) {
                val a = w.subList(i, i + len).map { it.lowercase() }
                if ((1..2).all { k -> w.subList(i + k * len, i + (k + 1) * len).map { it.lowercase() } == a }) {
                    var j = i + len * 3
                    while (j + len <= w.size && w.subList(j, j + len).map { it.lowercase() } == a) j += len
                    repeat(j - i - len) { w.removeAt(i + len) }
                }
                i++
            }
        }
        return w.joinToString(" ")
    }

    /** Long Whisper segments become lines of up to ~8 words, time split evenly. */
    private fun splitLine(start: Long, end: Long, text: String): List<Cue> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 9) return listOf(Cue(start, maxOf(end, start + 800), text))
        val parts = words.chunked(8)
        val per = (end - start).coerceAtLeast(1000) / parts.size
        return parts.mapIndexed { i, p -> Cue(start + i * per, start + (i + 1) * per, p.joinToString(" ")) }
    }
}
