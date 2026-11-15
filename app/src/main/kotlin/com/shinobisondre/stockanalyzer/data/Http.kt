package com.shinobisondre.stockanalyzer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

/** Cookie jar kept in memory; Yahoo's crumb is tied to the A3 cookie. */
class MemoryCookieJar : CookieJar {
    private val store = HashMap<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            val list = store.getOrPut(c.domain) { mutableListOf() }
            list.removeAll { it.name == c.name && it.path == c.path }
            list.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.flatten().filter { it.expiresAt > now && it.matches(url) }
    }

    @Synchronized
    fun has(name: String) = store.values.flatten().any { it.name == name }
}

object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 14; SM-F966B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    val cookies = MemoryCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", UA).build() else req)
        }
        .build()

    suspend fun get(url: String, accept: String = "application/json,*/*", allowError: Boolean = false): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", accept).header("Accept-Language", "en-US,en;q=0.9").build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful && !allowError) throw HttpException(r.code, "HTTP ${r.code} for ${url.substringBefore('?')}")
            body
        }
    }

    suspend fun status(url: String): Int = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
    }

    suspend fun postForm(url: String, fields: List<Pair<String, String>>): Int = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { fields.forEach { add(it.first, it.second) } }.build()
        client.newCall(Request.Builder().url(url).post(form).build()).execute().use { it.code }
    }
}
