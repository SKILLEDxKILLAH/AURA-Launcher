package com.aura.launcher.gemini

import android.os.Build
import com.aura.launcher.data.ThinkingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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

        /** The model's thought summary for one step and how long that step took. */
        data class Thought(val summary: String, val millis: Long) : Event

        /** Something the user should know, e.g. a thinking-level fallback. */
        data class Notice(val text: String) : Event

        /**
         * What Gemini has written so far in the current step, while it is still writing. A
         * [Thought] and/or [Reply] with the finished text always follows.
         */
        data class Partial(val thought: String, val reply: String) : Event
    }

    private val history = JSONArray()

    fun reset() {
        while (history.length() > 0) history.remove(history.length() - 1)
    }

    suspend fun send(
        prompt: String,
        credential: GeminiCredential,
        model: String,
        thinking: ThinkingLevel = ThinkingLevel.AUTO,
        stream: Boolean = true,
        onEvent: suspend (Event) -> Unit,
    ) {
        var noticeShown = false
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        trimHistory()

        repeat(MAX_STEPS) {
            val started = System.currentTimeMillis()
            val liveThought = StringBuilder()
            val liveReply = StringBuilder()
            val onChunk: (suspend (JSONObject) -> Unit)? = if (!stream) null else { chunk ->
                val ps = chunk.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                var changed = false
                for (i in 0 until (ps?.length() ?: 0)) {
                    val part = ps!!.getJSONObject(i)
                    val piece = part.optString("text")
                    if (piece.isEmpty()) continue
                    (if (part.optBoolean("thought", false)) liveThought else liveReply).append(piece)
                    changed = true
                }
                if (changed) onEvent(Event.Partial(liveThought.toString().trim(), liveReply.toString().trim()))
            }
            val result = generateAuthorized(credential, model, thinking, onChunk)
            val response = result.json
            if (result.note != null && !noticeShown) {
                noticeShown = true
                onEvent(Event.Notice(result.note))
            }
            val content = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?: throw IOException(blockReason(response))
            if (!content.has("role")) content.put("role", "model")

            val parts = content.optJSONArray("parts") ?: JSONArray()
            val calls = mutableListOf<JSONObject>()
            val text = StringBuilder()
            val thoughts = StringBuilder()
            val kept = JSONArray()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                part.optJSONObject("functionCall")?.let { calls += it }
                val isThought = part.optBoolean("thought", false)
                if (part.has("text")) (if (isThought) thoughts else text).append(part.getString("text"))
                // Thought summaries are display-only; keep everything else (incl. thought signatures).
                if (!isThought || part.has("thoughtSignature")) kept.put(part)
            }
            history.put(JSONObject(content.toString()).put("parts", kept))

            if (thoughts.isNotBlank()) onEvent(Event.Thought(thoughts.toString().trim(), System.currentTimeMillis() - started))
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

    private suspend fun generateAuthorized(
        credential: GeminiCredential,
        model: String,
        thinking: ThinkingLevel,
        onChunk: (suspend (JSONObject) -> Unit)?,
    ): ThinkingResponse {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", history)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools.declarations())))
            .put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")))
        return GeminiApi.generateThinking(credential, model, body, thinking, onChunk)
    }

    /**
     * No list of installed apps is sent: Google Play treats it as sensitive data. open_app
     * matches the name the user said against the apps on the phone instead.
     */
    private fun systemPrompt(): String {
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy, h:mm a z"))
        return """
            You are Google Gemini running inside AURA, a minimalist launcher on the user's Android ${Build.VERSION.RELEASE} phone.
            You can operate the phone with the provided tools. When the user asks you to do something, do it with tools rather than explaining how.
            Rules:
            - Reply in one short, warm sentence after acting. No markdown.
            - Opening another app moves AURA to the background, so perform at most one app-opening action per request and do it last; flashlight, alarms and timers can come first.
            - Calls and messages go to real people: only call or text when the user clearly asked to.
            - If a request needs something the tools can't do, answer it directly and briefly instead.
            - Current local time: $now.
            - To open an app, call open_app with the name the user said; it's matched on the phone.
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
