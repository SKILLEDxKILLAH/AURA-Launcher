package com.aura.launcher.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** How a Gemini API request is authenticated. */
sealed interface GeminiCredential {
    data class ApiKey(val key: String) : GeminiCredential

    /** Google-account OAuth; [token] returns a fresh access token, refreshing when asked. */
    class GoogleAccount(val token: suspend (forceRefresh: Boolean) -> String) : GeminiCredential
}

/** A non-2xx Gemini API response. [message] is user-friendly; [raw] is Google's own text. */
class GeminiHttpException(val code: Int, message: String, val raw: String) : IOException(message)

/** A model that can chat and call tools from AURA. */
data class GeminiModel(val id: String, val displayName: String, val description: String)

/** Thin client for the Gemini REST API shared by the agent, the model picker and diagnostics. */
object GeminiApi {
    /** Overridable so tests can point AURA at a local fake Gemini server. */
    @androidx.annotation.VisibleForTesting
    internal var baseUrl = "https://generativelanguage.googleapis.com/v1beta/"

    suspend fun generate(credential: GeminiCredential, model: String, body: JSONObject): JSONObject =
        call(credential, "POST", "models/${URLEncoder.encode(model, "UTF-8")}:generateContent", body)

    /**
     * Gemini models this account can use for chat with tools, newest first. Filters out
     * embeddings, image/audio/TTS/live-only, robotics and Gemma models, which can't drive AURA.
     */
    suspend fun listModels(credential: GeminiCredential): List<GeminiModel> {
        val out = mutableListOf<GeminiModel>()
        var pageToken: String? = null
        do {
            val path = "models?pageSize=1000" + (pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val json = call(credential, "GET", path, null)
            out += parseModels(json)
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return sortModels(out)
    }

    internal fun parseModels(json: JSONObject): List<GeminiModel> {
        val out = mutableListOf<GeminiModel>()
        val models = json.optJSONArray("models")
        for (i in 0 until (models?.length() ?: 0)) {
            val m = models!!.getJSONObject(i)
            val id = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")
            val canGenerate = (0 until (methods?.length() ?: 0)).any { methods!!.getString(it) == "generateContent" }
            if (canGenerate && isChatModel(id)) {
                out += GeminiModel(id, m.optString("displayName").ifBlank { id }, m.optString("description"))
            }
        }
        return out
    }

    internal fun sortModels(models: List<GeminiModel>) =
        models.distinctBy { it.id }.sortedWith(compareByDescending<GeminiModel> { rank(it.id) }.thenByDescending { it.id })

    private val excluded = listOf("embedding", "tts", "image", "audio", "live", "robotics", "computer-use", "aqa", "learnlm", "veo", "imagen")

    private fun isChatModel(id: String) = id.startsWith("gemini") && excluded.none { id.contains(it) }

    /** "latest" aliases first, then by version number, so the best defaults sit at the top. */
    private fun rank(id: String): Double {
        val version = Regex("""gemini-(\d+(?:\.\d+)?)""").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val alias = if (id.endsWith("-latest")) 100.0 else 0.0
        val stable = if (id.contains("preview") || id.contains("exp")) 0.0 else 0.5
        return alias + version + stable
    }

    suspend fun call(credential: GeminiCredential, method: String, path: String, body: JSONObject?): JSONObject =
        when (credential) {
            is GeminiCredential.ApiKey -> raw(method, path, body, "x-goog-api-key", credential.key)
            is GeminiCredential.GoogleAccount -> try {
                raw(method, path, body, "Authorization", "Bearer " + credential.token(false))
            } catch (e: GeminiHttpException) {
                if (e.code != 401) throw e
                // Token expired or was revoked: refresh silently and retry once.
                raw(method, path, body, "Authorization", "Bearer " + credential.token(true))
            }
        }

    private suspend fun raw(method: String, path: String, body: JSONObject?, header: String, value: String): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty(header, value)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                body?.let { b -> conn.outputStream.use { it.write(b.toString().toByteArray()) } }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: text.take(300)
                    throw GeminiHttpException(code, friendly(code, msg), msg)
                }
                JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }

    private fun friendly(code: Int, msg: String): String = when {
        code == 401 -> "Your Google sign-in expired — sign in again"
        code == 403 && (msg.contains("SERVICE_DISABLED", true) || msg.contains("has not been used in project", true)) ->
            "The Gemini API isn't enabled in your Google Cloud project yet. Enable “Generative Language API”, then try again."
        code == 403 && msg.contains("scope", true) -> "Google didn't grant Gemini access. Sign out and sign in again in AURA settings."
        code == 404 -> "That Gemini model isn't available to your account — pick another model"
        code == 429 -> "Gemini is busy or your quota is used up — try again in a moment"
        else -> "Gemini error $code: $msg"
    }
}
