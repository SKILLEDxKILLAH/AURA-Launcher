package com.aura.launcher

import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.AppRepository
import com.aura.launcher.gemini.AuraAgent
import com.aura.launcher.gemini.GeminiApi
import com.aura.launcher.gemini.GeminiCredential
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
            val (code, resp) = synchronized(responses) { responses.removeFirst() }
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
            agent.send("How much battery do I have?", credential, "gemini-flash-latest", listOf("Maps", "Messages")) { events += it }
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
        assertTrue(requests[1].path.endsWith("models/gemini-flash-latest:generateContent"))

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
}
