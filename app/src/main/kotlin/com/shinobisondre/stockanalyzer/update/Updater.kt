package com.shinobisondre.stockanalyzer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.shinobisondre.stockanalyzer.BuildConfig
import com.shinobisondre.stockanalyzer.data.Http
import com.shinobisondre.stockanalyzer.engine.MiniJson
import com.shinobisondre.stockanalyzer.engine.MiniJson.list
import com.shinobisondre.stockanalyzer.engine.MiniJson.num
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import com.shinobisondre.stockanalyzer.engine.MiniJson.str
import com.shinobisondre.stockanalyzer.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Self-update from this repo's GitHub Releases: CI publishes every green
 * build of main as v1.0.<run number> with the signed APK attached.
 */
object Updater {
    data class Release(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String, val size: Long)

    private const val PREFS = "updater"

    suspend fun latest(): Release? {
        val body = Http.get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest", accept = "application/vnd.github+json")
        val r = MiniJson.parse(body).obj()
        val tag = r["tag_name"].str() ?: return null
        val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
        val apk = r["assets"].list().map { it.obj() }.firstOrNull { (it["name"].str() ?: "").endsWith(".apk") } ?: return null
        return Release(code, tag.removePrefix("v"), r["body"].str() ?: "", apk["browser_download_url"].str() ?: return null, apk["size"].num()?.toLong() ?: 0L)
    }

    /** Newer release than the installed build, or null. */
    suspend fun check(): Release? = latest()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    suspend fun backgroundCheck(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastCheck", 0) < 12 * 3600_000) return
        prefs.edit().putLong("lastCheck", now).apply()
        val rel = check() ?: return
        if (prefs.getInt("notified", 0) >= rel.versionCode) return
        prefs.edit().putInt("notified", rel.versionCode).apply()
        Notifier.post(ctx, Notifier.CH_UPDATES, 9001, "StockAnalyzer ${rel.versionName} is available", "Tap to download and install the update.")
    }

    suspend fun download(ctx: Context, rel: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "StockAnalyzer-${rel.versionName}.apk")
        Http.client.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Download failed (HTTP ${resp.code})")
            val body = resp.body ?: throw IllegalStateException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: rel.size
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        out
    }

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
