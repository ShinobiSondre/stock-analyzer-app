package com.shinobisondre.stockanalyzer.data

import com.shinobisondre.stockanalyzer.engine.MiniJson
import com.shinobisondre.stockanalyzer.engine.MiniJson.list
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import com.shinobisondre.stockanalyzer.engine.MiniJson.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Free AI with no account or key (same providers as FaithScroll): OVHcloud AI
 * Endpoints' anonymous tier (Llama 3.3 70B, then Mistral Small), then Pollinations.
 */
object Ai {
    class RateLimited(val seconds: Int) : Exception("The free AI service is busy — try again in ${seconds}s")

    private const val OVH = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions"
    private val client by lazy { Http.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build() }
    private val JSON = "application/json".toMediaType()

    private fun messages(system: String, user: String) = listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to user))

    private fun clean(s: String) = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    private fun openAiStyle(model: String, system: String, user: String, maxTokens: Int): String {
        val body = MiniJson.write(mapOf("model" to model, "messages" to messages(system, user), "max_tokens" to maxTokens, "temperature" to 0.2))
        client.newCall(Request.Builder().url(OVH).post(body.toRequestBody(JSON)).header("Content-Type", "application/json").build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 429 || r.code == 402) throw RateLimited(r.header("Retry-After")?.toIntOrNull()?.coerceIn(1, 120) ?: 30)
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} ${text.take(120)}")
            val answer = MiniJson.parse(text).obj()["choices"].list().firstOrNull().obj()["message"].obj()["content"].str().orEmpty()
            return clean(answer).ifBlank { throw IllegalStateException("Empty answer") }
        }
    }

    private fun pollinations(system: String, user: String): String {
        val body = MiniJson.write(mapOf("messages" to messages(system, user), "private" to true))
        client.newCall(Request.Builder().url("https://text.pollinations.ai/").post(body.toRequestBody(JSON)).header("Content-Type", "application/json").build()).execute().use { r ->
            val text = r.body?.string().orEmpty().trim()
            if (r.code == 429 || r.code == 402) throw RateLimited(r.header("Retry-After")?.toIntOrNull()?.coerceIn(1, 120) ?: 30)
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return clean(text).ifBlank { throw IllegalStateException("Empty answer") }
        }
    }

    /** Returns (answer, provider). Waits once for a short rate limit before giving up. */
    suspend fun chat(system: String, user: String, maxTokens: Int = 1400): Pair<String, String> = withContext(Dispatchers.IO) {
        val providers: List<Pair<String, () -> String>> = listOf(
            "Llama 3.3 70B" to { openAiStyle("meta-llama-3_3-70b-instruct", system, user, maxTokens) },
            "Mistral Small" to { openAiStyle("mistral-small-3-2-24b-instruct", system, user, maxTokens) },
            "Pollinations" to { pollinations(system, user) },
        )
        var limited: RateLimited? = null
        var last: Throwable? = null
        for (attempt in 0..1) {
            for ((_, call) in providers) {
                val r = runCatching { call() }
                if (r.isSuccess) return@withContext r.getOrThrow() to providers.first { it.second === call }.first
                val e = r.exceptionOrNull()!!
                if (e is RateLimited) limited = if (limited == null || e.seconds < limited!!.seconds) e else limited
                last = e
            }
            val wait = limited?.seconds
            if (attempt == 0 && wait != null && wait <= 45) { delay(wait * 1000L); limited = null } else break
        }
        throw limited ?: last ?: IllegalStateException("No AI service answered")
    }
}
