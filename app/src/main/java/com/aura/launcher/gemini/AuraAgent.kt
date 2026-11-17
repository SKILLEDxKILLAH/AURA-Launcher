package com.aura.launcher.gemini

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * In-launcher Gemini agent. Talks to the Gemini API with function calling and executes the
 * returned calls through [PhoneTools], so Gemini can operate the phone directly from AURA.
 */
class AuraAgent(private val tools: PhoneTools) {

    sealed interface Event {
        data class Reply(val text: String) : Event
        data class Action(val label: String, val ok: Boolean) : Event
    }

    private val history = JSONArray()

    fun reset() {
        while (history.length() > 0) history.remove(history.length() - 1)
    }

    suspend fun send(
        prompt: String,
        apiKey: String,
        model: String,
        appNames: List<String>,
        onEvent: suspend (Event) -> Unit,
    ) {
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        trimHistory()

        repeat(MAX_STEPS) {
            val response = withContext(Dispatchers.IO) { generate(apiKey, model, appNames) }
            val content = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?: throw IOException(blockReason(response))
            if (!content.has("role")) content.put("role", "model")
            history.put(content)

            val parts = content.optJSONArray("parts") ?: JSONArray()
            val calls = mutableListOf<JSONObject>()
            val text = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                part.optJSONObject("functionCall")?.let { calls += it }
                if (part.has("text") && !part.optBoolean("thought", false)) text.append(part.getString("text"))
            }
            if (text.isNotBlank()) onEvent(Event.Reply(text.toString().trim()))
            if (calls.isEmpty()) return

            val responses = JSONArray()
            for (call in calls) {
                val name = call.getString("name")
                val result = withContext(Dispatchers.Main) { tools.execute(name, call.optJSONObject("args") ?: JSONObject()) }
                onEvent(Event.Action(result.label, result.ok))
                val fr = JSONObject().put("name", name)
                    .put("response", JSONObject().put("ok", result.ok).put("result", result.detail))
                call.optString("id").takeIf { it.isNotEmpty() }?.let { fr.put("id", it) }
                responses.put(JSONObject().put("functionResponse", fr))
            }
            history.put(JSONObject().put("role", "user").put("parts", responses))
        }
    }

    private fun generate(apiKey: String, model: String, appNames: List<String>): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(appNames)))))
            .put("contents", history)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools.declarations())))
            .put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw IOException("Gemini API $code: ${msg ?: text.take(200)}")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun systemPrompt(appNames: List<String>): String {
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy, h:mm a z"))
        return """
            You are Google Gemini running inside AURA, a minimalist launcher on the user's Android ${Build.VERSION.RELEASE} phone.
            You can operate the phone with the provided tools. When the user asks you to do something, do it with tools rather than explaining how.
            Rules:
            - Reply in one short, warm sentence after acting. No markdown.
            - Opening another app moves AURA to the background, so perform at most one app-opening action per request and do it last; flashlight, alarms and timers can come first.
            - Calls and messages go to real people: only call or text when the user clearly asked to.
            - For requests that need the screen, email, Google account data or anything the tools can't do, use ask_gemini_app.
            - Current local time: $now.
            Installed apps: ${appNames.joinToString(", ").take(4000)}
        """.trimIndent()
    }

    private fun blockReason(response: JSONObject): String =
        response.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }
            ?.let { "Gemini declined this request ($it)" } ?: "Gemini returned no answer"

    private fun trimHistory() {
        // Keep the conversation bounded; drop oldest turns but never split a call/response pair.
        while (history.length() > 24) history.remove(0)
        while (history.length() > 0) {
            val first = history.getJSONObject(0)
            val isPlainUser = first.optString("role") == "user" &&
                first.optJSONArray("parts")?.optJSONObject(0)?.has("functionResponse") != true
            if (isPlainUser) break
            history.remove(0)
        }
    }

    companion object {
        private const val MAX_STEPS = 6
    }
}
