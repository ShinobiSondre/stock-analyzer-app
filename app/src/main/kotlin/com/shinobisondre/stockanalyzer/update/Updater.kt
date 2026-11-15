package com.shinobisondre.stockanalyzer.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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

    /** The downloaded APK for [rel], if it is already on disk and complete. */
    fun downloaded(ctx: Context, rel: Release): File? =
        File(File(ctx.cacheDir, "updates"), "StockAnalyzer-${rel.versionName}.apk").takeIf { it.exists() && (rel.size <= 0 || it.length() == rel.size) }

    /** True while this app's screen is visible (set by MainActivity). */
    @Volatile var foreground: Boolean = false
    /** Set while the photo picker (or another screen we opened) is up — don't restart the app under it. */
    @Volatile var holdInstall: Boolean = false

    /**
     * Installs through a PackageInstaller session. On Android 12+ an app may update
     * itself without the "Do you want to install this update?" prompt
     * (USER_ACTION_NOT_REQUIRED + UPDATE_PACKAGES_WITHOUT_USER_ACTION). If Android
     * still wants confirmation, [InstallReceiver] shows its prompt (or a
     * notification when the app is in the background).
     */
    fun installSession(ctx: Context, apk: File, versionName: String) {
        val app = ctx.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("installing", versionName).apply()
            val status = Intent(app, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(app, id, status, flags).intentSender)
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

/** Result of a self-update session. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Updater.foreground) runCatching { ctx.startActivity(confirm) }
                else Notifier.postIntent(ctx, Notifier.CH_UPDATES, 9002, "Finish updating StockAnalyzer", "Tap to install the downloaded update.",
                    PendingIntent.getActivity(ctx, 9002, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // this process is replaced; PackageReplacedReceiver runs in the new version
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                ctx.getSharedPreferences("updater", Context.MODE_PRIVATE).edit().putString("lastError", msg).remove("installing").apply()
            }
        }
    }

    companion object { const val ACTION = "com.shinobisondre.stockanalyzer.INSTALL_STATUS" }
}

/** Runs in the NEW version right after a self-update: say so, with a tap to reopen. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val v = prefs.getString("installing", null) ?: return
        prefs.edit().remove("installing").remove("lastError").apply()
        File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        Notifier.post(ctx, Notifier.CH_UPDATES, 9003, "StockAnalyzer updated to ${BuildConfig.VERSION_NAME}", "Tap to open.")
    }
}
