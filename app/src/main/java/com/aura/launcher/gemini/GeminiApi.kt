package com.aura.launcher.gemini

import com.aura.launcher.data.ThinkingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** How a Gemini API request is authenticated. */
sealed interface GeminiCredential {
    data class ApiKey(val key: String) : GeminiCredential

    /**
     * Google-account OAuth; [token] returns a fresh access token, refreshing when asked.
     * [widenScopes] is called if Google says the token's access is too narrow; true = retry.
     */
    class GoogleAccount(
        val widenScopes: suspend () -> Boolean = { false },
        val token: suspend (forceRefresh: Boolean) -> String,
    ) : GeminiCredential
}

/** A non-2xx Gemini API response. [message] is user-friendly; [raw] is Google's own text. */
class GeminiHttpException(val code: Int, message: String, val raw: String) : IOException(message)

/** Result of a generateContent call made with a thinking setting. [note] explains any fallback. */
data class ThinkingResponse(val json: JSONObject, val applied: ThinkingLevel, val note: String?)

/** A model that can chat and call tools from AURA. */
data class GeminiModel(val id: String, val displayName: String, val description: String)

/** Thin client for the Gemini REST API shared by the agent, the model picker and diagnostics. */
object GeminiApi {
    /** Overridable so tests can point AURA at a local fake Gemini server. */
    @androidx.annotation.VisibleForTesting
    internal var baseUrl = "https://generativelanguage.googleapis.com/v1beta/"

    // ---- Thinking -------------------------------------------------------------------------------

    /** Level a model actually accepted for a requested level, learned at runtime. */
    private val acceptedLevel = ConcurrentHashMap<String, ThinkingLevel>()

    /** Gemini 2.5 uses a token budget; Gemini 3 and newer (and the -latest aliases) use a level. */
    internal fun usesBudget(model: String) = model.startsWith("gemini-2.5") || model.startsWith("gemini-2.0")

    internal fun thinkingConfig(model: String, level: ThinkingLevel): JSONObject {
        if (level == ThinkingLevel.AUTO) return JSONObject().put("includeThoughts", true)
        val config = JSONObject().put("includeThoughts", level != ThinkingLevel.MINIMAL)
        if (usesBudget(model)) {
            val pro = model.contains("pro")
            val budget = when (level) {
                ThinkingLevel.MINIMAL -> if (pro) 128 else 0
                ThinkingLevel.LOW -> 1024
                ThinkingLevel.MEDIUM -> 8192
                ThinkingLevel.HIGH -> if (pro) 32768 else 24576
                ThinkingLevel.AUTO -> -1
            }
            config.put("thinkingBudget", budget)
        } else {
            config.put("thinkingLevel", level.name.lowercase())
        }
        return config
    }

    /** What to try next if a model rejects [level]: nearest supported level, then the model's default. */
    private fun fallbacks(level: ThinkingLevel): List<ThinkingLevel> = when (level) {
        ThinkingLevel.MINIMAL -> listOf(ThinkingLevel.MINIMAL, ThinkingLevel.LOW, ThinkingLevel.AUTO)
        ThinkingLevel.MEDIUM -> listOf(ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.LOW, ThinkingLevel.AUTO)
        ThinkingLevel.LOW -> listOf(ThinkingLevel.LOW, ThinkingLevel.AUTO)
        ThinkingLevel.HIGH -> listOf(ThinkingLevel.HIGH, ThinkingLevel.AUTO)
        ThinkingLevel.AUTO -> listOf(ThinkingLevel.AUTO)
    }

    /**
     * generateContent with the requested thinking level. If the model rejects that level, steps to
     * the nearest one it accepts (remembered for next time) and finally to the model's own default,
     * so a thinking setting never breaks Gemini.
     */
    suspend fun generateThinking(
        credential: GeminiCredential,
        model: String,
        body: JSONObject,
        level: ThinkingLevel,
        onChunk: (suspend (JSONObject) -> Unit)? = null,
    ): ThinkingResponse {
        val key = "$model|$level"
        suspend fun send(request: JSONObject) =
            if (onChunk == null) generate(credential, model, request) else stream(credential, model, request, onChunk)
        if (model in noThinkingConfig) {
            return ThinkingResponse(send(withThinking(body, null)), ThinkingLevel.AUTO, unsupportedNote(level))
        }
        val ladder = acceptedLevel[key]?.let { listOf(it) } ?: fallbacks(level)
        for (attempt in ladder) {
            try {
                val json = send(withThinking(body, thinkingConfig(model, attempt)))
                acceptedLevel[key] = attempt
                val note = when {
                    attempt == level -> null
                    attempt == ThinkingLevel.AUTO -> "This model doesn't take a thinking level, so it chose its own"
                    else -> "This model doesn't support ${level.label}; using ${attempt.label}"
                }
                return ThinkingResponse(json, attempt, note)
            } catch (e: GeminiHttpException) {
                if (!isThinkingRejection(e)) throw e
            }
        }
        // Every thinking setting was rejected: this model takes none at all.
        val json = send(withThinking(body, null))
        noThinkingConfig += model
        return ThinkingResponse(json, ThinkingLevel.AUTO, unsupportedNote(level))
    }

    private val noThinkingConfig = ConcurrentHashMap.newKeySet<String>()

    private fun unsupportedNote(level: ThinkingLevel) =
        if (level == ThinkingLevel.AUTO) null else "This model doesn't support thinking settings"

    private fun isThinkingRejection(e: GeminiHttpException) = e.code == 400 && e.raw.contains("think", ignoreCase = true)

    private fun withThinking(body: JSONObject, config: JSONObject?): JSONObject {
        val request = JSONObject(body.toString())
        val generationConfig = request.optJSONObject("generationConfig") ?: JSONObject()
        if (config != null) generationConfig.put("thinkingConfig", config) else generationConfig.remove("thinkingConfig")
        if (generationConfig.length() > 0) request.put("generationConfig", generationConfig) else request.remove("generationConfig")
        return request
    }

    suspend fun generate(credential: GeminiCredential, model: String, body: JSONObject): JSONObject =
        call(credential, "POST", "models/${URLEncoder.encode(model, "UTF-8")}:generateContent", body)

    /**
     * streamGenerateContent: [onChunk] gets each piece as Gemini writes it, so replies appear word by
     * word. Returns the whole response put back together in generateContent's shape, with thought
     * signatures kept, so the agent and the conversation history work exactly as without streaming.
     */
    suspend fun stream(
        credential: GeminiCredential,
        model: String,
        body: JSONObject,
        onChunk: suspend (JSONObject) -> Unit,
    ): JSONObject {
        val path = "models/${URLEncoder.encode(model, "UTF-8")}:streamGenerateContent?alt=sse"
        val chunks = mutableListOf<JSONObject>()
        authorized(credential) { header, value ->
            chunks.clear()
            rawStream(path, body, header, value) { chunk ->
                chunks += chunk
                onChunk(chunk)
            }
        }
        return assemble(chunks)
    }

    /** Joins streamed chunks into one response; neighbouring text pieces of the same kind are merged. */
    internal fun assemble(chunks: List<JSONObject>): JSONObject {
        val parts = mutableListOf<JSONObject>()
        var role = "model"
        var finishReason: String? = null
        var promptFeedback: JSONObject? = null
        var sawCandidate = false
        for (chunk in chunks) {
            chunk.optJSONObject("promptFeedback")?.let { promptFeedback = it }
            val candidate = chunk.optJSONArray("candidates")?.optJSONObject(0) ?: continue
            sawCandidate = true
            candidate.optString("finishReason").takeIf { it.isNotEmpty() }?.let { finishReason = it }
            val content = candidate.optJSONObject("content") ?: continue
            content.optString("role").takeIf { it.isNotEmpty() }?.let { role = it }
            val ps = content.optJSONArray("parts") ?: continue
            for (i in 0 until ps.length()) {
                val part = JSONObject(ps.getJSONObject(i).toString())
                val last = parts.lastOrNull()
                if (last != null && isPlainText(part) && isPlainText(last) &&
                    part.optBoolean("thought") == last.optBoolean("thought") &&
                    !(last.has("thoughtSignature") && part.optString("text").isNotEmpty())
                ) {
                    last.put("text", last.optString("text") + part.optString("text"))
                    part.optString("thoughtSignature").takeIf { it.isNotEmpty() }?.let { last.put("thoughtSignature", it) }
                } else {
                    parts += part
                }
            }
        }
        val out = JSONObject()
        promptFeedback?.let { out.put("promptFeedback", it) }
        if (sawCandidate) {
            val candidate = JSONObject().put("content", JSONObject().put("role", role).put("parts", JSONArray(parts)))
            finishReason?.let { candidate.put("finishReason", it) }
            out.put("candidates", JSONArray().put(candidate))
        }
        return out
    }

    private fun isPlainText(part: JSONObject) = part.has("text") && !part.has("functionCall") && !part.has("functionResponse")

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
        authorized(credential) { header, value -> raw(method, path, body, header, value) }

    /** Runs [request] with the credential's auth header; a Google token that expired is refreshed once. */
    private suspend fun <T> authorized(credential: GeminiCredential, request: suspend (String, String) -> T): T =
        when (credential) {
            is GeminiCredential.ApiKey -> request("x-goog-api-key", credential.key)
            is GeminiCredential.GoogleAccount -> try {
                request("Authorization", "Bearer " + credential.token(false))
            } catch (e: GeminiHttpException) {
                when {
                    // Token expired or was revoked: refresh silently and retry once.
                    e.code == 401 -> request("Authorization", "Bearer " + credential.token(true))
                    // The narrow Gemini-only access wasn't enough: ask for the broader scope once.
                    e.code == 403 && isScopeProblem(e) && credential.widenScopes() ->
                        request("Authorization", "Bearer " + credential.token(true))
                    else -> throw e
                }
            }
        }

    private fun open(method: String, path: String, body: JSONObject?, header: String, value: String): HttpURLConnection {
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
        body?.let { b -> conn.outputStream.use { it.write(b.toString().toByteArray()) } }
        return conn
    }

    private fun failure(conn: HttpURLConnection, code: Int): GeminiHttpException {
        val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: text.take(300)
        return GeminiHttpException(code, friendly(code, msg), msg)
    }

    private suspend fun raw(method: String, path: String, body: JSONObject?, header: String, value: String): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = open(method, path, body, header, value)
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw failure(conn, code)
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }

    /** Reads a server-sent-events response, handing each `data:` JSON object to [onEvent]. */
    private suspend fun rawStream(
        path: String,
        body: JSONObject,
        header: String,
        value: String,
        onEvent: suspend (JSONObject) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val conn = open("POST", path, body, header, value)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw failure(conn, code)
            conn.inputStream.bufferedReader().use { reader ->
                val data = StringBuilder()
                suspend fun flush() {
                    if (data.isNotEmpty()) {
                        val event = JSONObject(data.toString())
                        data.setLength(0)
                        event.optJSONObject("error")?.let { err ->
                            val msg = err.optString("message")
                            val c = err.optInt("code", 500)
                            throw GeminiHttpException(c, friendly(c, msg), msg)
                        }
                        onEvent(event)
                    }
                }
                while (true) {
                    ensureActive()
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                        line.isBlank() -> flush()
                    }
                }
                flush()
            }
        } finally {
            conn.disconnect()
        }
    }

    internal fun isScopeProblem(e: GeminiHttpException) =
        e.raw.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT", true) || e.raw.contains("insufficient authentication scopes", true)

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
