package com.aura.launcher

import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.AppRepository
import com.aura.launcher.gemini.AuraAgent
import com.aura.launcher.gemini.GeminiApi
import com.aura.launcher.gemini.GeminiCredential
import com.aura.launcher.gemini.GeminiHttpException
import com.aura.launcher.gemini.PhoneTools
import com.sun.net.httpserver.HttpServer
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Drives AURA's real Gemini code against a local fake of the Gemini REST API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class GeminiPipelineTest {
    private data class Req(val method: String, val path: String, val auth: String?, val body: String)

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Req>()
    private val responses = ArrayDeque<Pair<Int, String>>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.bufferedReader().readText()
            requests += Req(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.getFirst("Authorization"), body)
            val (code, queued) = synchronized(responses) { responses.removeFirst() }
            // Streaming requests get server-sent events: a queued list of chunks (separated by
            // "\n---\n") becomes one `data:` event each.
            val resp = if (code == 200 && ex.requestURI.toString().contains("alt=sse")) {
                queued.split("\n---\n").joinToString("") { "data: $it\r\n\r\n" }
            } else queued
            val bytes = resp.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        GeminiApi.baseUrl = "http://127.0.0.1:${server.address.port}/v1beta/"
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun functionCall(name: String) = JSONObject().put(
        "candidates", JSONArray().put(
            JSONObject().put(
                "content", JSONObject().put("role", "model").put(
                    "parts", JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", name).put("args", JSONObject())).put("thoughtSignature", "sig-123"))
                )
            )
        )
    ).toString()

    private fun text(t: String) = JSONObject().put(
        "candidates", JSONArray().put(
            JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", t))))
        )
    ).toString()

    @Test
    fun googleAccountAgentLoopRefreshesTokenAndRunsTools() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val tools = PhoneTools(context, AppRepository(context))
        val agent = AuraAgent(tools)
        var refreshes = 0
        val credential = GeminiCredential.GoogleAccount { force -> if (force) { refreshes++; "fresh-token" } else "stale-token" }

        responses += 401 to """{"error":{"code":401,"message":"Request had invalid authentication credentials."}}"""
        responses += 200 to functionCall("get_device_status")
        responses += 200 to text("You're at 50% battery.")

        val events = java.util.Collections.synchronizedList(mutableListOf<AuraAgent.Event>())
        // The agent runs tools on the main thread, so run it in the background and pump the
        // (Robolectric) main looper here until it finishes.
        val job = CoroutineScope(Dispatchers.IO).async {
            agent.send("How much battery do I have?", credential, "gemini-flash-latest") { events += it }
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (!job.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "Agent loop timed out; requests so far: $requests" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        runBlocking { job.await() }

        // Expired token → one silent refresh, then the same request succeeds.
        assertEquals(1, refreshes)
        assertEquals("Bearer stale-token", requests[0].auth)
        assertEquals("Bearer fresh-token", requests[1].auth)
        assertTrue(requests[1].path.endsWith("models/gemini-flash-latest:streamGenerateContent?alt=sse"))

        // The request carries AURA's phone tools.
        val first = JSONObject(requests[1].body)
        val declared = first.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertTrue((0 until declared.length()).any { declared.getJSONObject(it).getString("name") == "get_device_status" })

        // The tool ran and its result went back, with Gemini's thought signature preserved.
        assertTrue(events.any { it is AuraAgent.Event.Action && it.ok && it.label == "Checked device status" })
        val second = JSONObject(requests[2].body).getJSONArray("contents")
        assertTrue(second.toString().contains("\"functionResponse\""))
        assertTrue(second.toString().contains("sig-123"))
        assertEquals(AuraAgent.Event.Reply("You're at 50% battery."), events.last())
    }

    private fun chunk(vararg parts: JSONObject) = JSONObject().put(
        "candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray(parts.toList()))))
    ).toString()

    private fun runAgent(agent: AuraAgent, prompt: String, events: MutableList<AuraAgent.Event>) {
        val credential = GeminiCredential.ApiKey("test-key")
        val job = CoroutineScope(Dispatchers.IO).async {
            agent.send(prompt, credential, "gemini-flash-latest") { events += it }
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (!job.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "Agent loop timed out; requests so far: $requests" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        runBlocking { job.await() }
    }

    @Test
    fun repliesStreamInPiecesAndHistoryStaysWhole() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val agent = AuraAgent(PhoneTools(context, AppRepository(context)))
        // Thought summary, then the reply in three pieces; the signature arrives last on an empty part.
        responses += 200 to listOf(
            chunk(JSONObject().put("text", "Checking the time zone.").put("thought", true)),
            chunk(JSONObject().put("text", "It's ")),
            chunk(JSONObject().put("text", "sunny ")),
            chunk(JSONObject().put("text", "today.")),
            chunk(JSONObject().put("text", "").put("thoughtSignature", "sig-text")),
        ).joinToString("\n---\n")
        responses += 200 to text("Still sunny.")

        val events = java.util.Collections.synchronizedList(mutableListOf<AuraAgent.Event>())
        runAgent(agent, "Weather?", events)

        // The reply was shown growing, word by word, before the finished line.
        val partials = events.filterIsInstance<AuraAgent.Event.Partial>().map { it.reply }.filter { it.isNotEmpty() }
        assertEquals(listOf("It's", "It's sunny", "It's sunny today."), partials)
        assertEquals("Checking the time zone.", events.filterIsInstance<AuraAgent.Event.Partial>().first().thought)
        assertEquals(AuraAgent.Event.Reply("It's sunny today."), events.last())
        assertTrue(events.any { it is AuraAgent.Event.Thought && it.summary == "Checking the time zone." })

        // Next turn: the streamed answer went into the history as one part, signature kept.
        runAgent(agent, "And tomorrow?", events)
        val contents = JSONObject(requests[1].body).getJSONArray("contents")
        val model = contents.getJSONObject(1)
        assertEquals("model", model.getString("role"))
        val parts = model.getJSONArray("parts")
        assertEquals(1, parts.length())
        assertEquals("It's sunny today.", parts.getJSONObject(0).getString("text"))
        assertEquals("sig-text", parts.getJSONObject(0).getString("thoughtSignature"))
    }

    @Test
    fun streamedToolCallStillRuns() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val agent = AuraAgent(PhoneTools(context, AppRepository(context)))
        responses += 200 to functionCall("get_device_status")
        responses += 200 to listOf(chunk(JSONObject().put("text", "Battery ")), chunk(JSONObject().put("text", "is fine."))).joinToString("\n---\n")
        val events = java.util.Collections.synchronizedList(mutableListOf<AuraAgent.Event>())
        runAgent(agent, "Battery?", events)
        assertTrue(events.any { it is AuraAgent.Event.Action && it.ok })
        assertEquals(AuraAgent.Event.Reply("Battery is fine."), events.last())
        assertTrue(requests[1].body.contains("sig-123"))
    }

    @Test
    fun streamingErrorsAreReportedLikeOthers() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val agent = AuraAgent(PhoneTools(context, AppRepository(context)))
        responses += 429 to """{"error":{"code":429,"message":"Resource has been exhausted"}}"""
        val events = mutableListOf<AuraAgent.Event>()
        val e = runCatching { runAgent(agent, "Hi", events) }.exceptionOrNull()
        assertTrue(e is GeminiHttpException && e.code == 429)
    }

    @Test
    fun modelListKeepsOnlyChatModelsAndPutsBestFirst() {
        val json = JSONObject(
            """{"models":[
              {"name":"models/text-embedding-004","displayName":"Embedding","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/gemini-2.5-flash-preview-tts","displayName":"TTS","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemma-3-27b-it","displayName":"Gemma 3","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-2.5-flash-image","displayName":"Nano Banana","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-3-pro-preview","displayName":"Gemini 3 Pro Preview","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-flash-latest","displayName":"Gemini Flash Latest","supportedGenerationMethods":["generateContent"]}
            ]}"""
        )
        val ids = GeminiApi.sortModels(GeminiApi.parseModels(json)).map { it.id }
        assertEquals(listOf("gemini-flash-latest", "gemini-3-pro-preview", "gemini-2.5-pro", "gemini-2.5-flash"), ids)
    }

    private val key = GeminiCredential.ApiKey("test-key")
    private fun body() = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "hi")))))
    private fun thinkingOf(i: Int) = JSONObject(requests[i].body).optJSONObject("generationConfig")?.optJSONObject("thinkingConfig")

    @Test
    fun newerModelsGetAThinkingLevel() = runBlocking {
        responses += 200 to text("ok")
        val r = GeminiApi.generateThinking(key, "gemini-4-hydrogen", body(), com.aura.launcher.data.ThinkingLevel.HIGH)
        assertEquals("high", thinkingOf(0)!!.getString("thinkingLevel"))
        assertEquals(true, thinkingOf(0)!!.getBoolean("includeThoughts"))
        assertEquals(null, r.note)
    }

    @Test
    fun gemini25GetsATokenBudget() = runBlocking {
        responses += 200 to text("ok")
        GeminiApi.generateThinking(key, "gemini-2.5-flash", body(), com.aura.launcher.data.ThinkingLevel.MEDIUM)
        assertEquals(8192, thinkingOf(0)!!.getInt("thinkingBudget"))
        assertTrue(!thinkingOf(0)!!.has("thinkingLevel"))
    }

    @Test
    fun unsupportedLevelStepsToNearestAndIsRemembered() = runBlocking {
        responses += 400 to """{"error":{"code":400,"message":"thinking_level MEDIUM is not supported for this model."}}"""
        responses += 200 to text("ok")
        val r = GeminiApi.generateThinking(key, "gemini-3.7-flash", body(), com.aura.launcher.data.ThinkingLevel.MEDIUM)
        assertEquals("medium", thinkingOf(0)!!.getString("thinkingLevel"))
        assertEquals("high", thinkingOf(1)!!.getString("thinkingLevel"))
        assertEquals(com.aura.launcher.data.ThinkingLevel.HIGH, r.applied)
        assertTrue(r.note!!.contains("using High"))
        // Next time it goes straight to the level that worked.
        responses += 200 to text("ok again")
        GeminiApi.generateThinking(key, "gemini-3.7-flash", body(), com.aura.launcher.data.ThinkingLevel.MEDIUM)
        assertEquals(3, requests.size)
        assertEquals("high", thinkingOf(2)!!.getString("thinkingLevel"))
    }

    @Test
    fun modelWithoutThinkingStillAnswers() = runBlocking {
        // Low is rejected, then Auto (with thought summaries) is rejected, then a bare request succeeds.
        repeat(2) { responses += 400 to """{"error":{"code":400,"message":"Thinking is not supported by this model."}}""" }
        responses += 200 to text("plain answer")
        val r = GeminiApi.generateThinking(key, "gemini-4-argon", body(), com.aura.launcher.data.ThinkingLevel.LOW)
        assertEquals(3, requests.size)
        assertEquals("low", thinkingOf(0)!!.getString("thinkingLevel"))
        assertTrue(!thinkingOf(1)!!.has("thinkingLevel"))
        assertEquals(null, thinkingOf(2))
        assertEquals(com.aura.launcher.data.ThinkingLevel.AUTO, r.applied)
        assertTrue(r.note!!.contains("doesn't support thinking"))
    }

    @Test
    fun otherErrorsAreNotMistakenForThinkingProblems() {
        responses += 404 to """{"error":{"code":404,"message":"models/gemini-x is not found"}}"""
        val e = runCatching { runBlocking { GeminiApi.generateThinking(key, "gemini-x", body(), com.aura.launcher.data.ThinkingLevel.HIGH) } }.exceptionOrNull()
        assertTrue(e is com.aura.launcher.gemini.GeminiHttpException && e.code == 404)
        assertEquals(1, requests.size)
    }

    @Test
    fun thoughtSummariesAreShownButNotResent() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val agent = AuraAgent(PhoneTools(context, AppRepository(context)))
        responses += 200 to JSONObject().put(
            "candidates", JSONArray().put(
                JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray()
                    .put(JSONObject().put("text", "The user wants a greeting.").put("thought", true))
                    .put(JSONObject().put("text", "Hello!").put("thoughtSignature", "sig-9"))))
            )
        ).toString()
        responses += 200 to text("Second answer")
        val events = mutableListOf<AuraAgent.Event>()
        runBlocking {
            agent.send("Say hi", key, "gemini-4-hydrogen", com.aura.launcher.data.ThinkingLevel.HIGH) { events += it }
            agent.send("Again", key, "gemini-4-hydrogen", com.aura.launcher.data.ThinkingLevel.HIGH) { events += it }
        }
        assertTrue(events.any { it is AuraAgent.Event.Thought && it.summary == "The user wants a greeting." })
        assertTrue(events.contains(AuraAgent.Event.Reply("Hello!")))
        val resent = JSONObject(requests[1].body).getJSONArray("contents").toString()
        assertTrue(!resent.contains("The user wants a greeting."))
        assertTrue(resent.contains("sig-9"))
    }
}
