package com.aura.launcher

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.AppRepository
import com.aura.launcher.gemini.AuraAgent
import com.aura.launcher.gemini.GeminiApi
import com.aura.launcher.gemini.GeminiCredential
import com.aura.launcher.gemini.PhoneTools
import com.aura.launcher.gemini.ReplyReports
import com.aura.launcher.gemini.ReportReason
import com.aura.launcher.ui.AuraBackdrop
import com.aura.launcher.ui.theme.AuraTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Checks for the Google Play policy points AURA was changed for. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w412dp-h915dp-xhdpi")
class PlayReadinessTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private lateinit var server: HttpServer
    private val bodies = CopyOnWriteArrayList<Pair<String, String>>()
    private val responses = ArrayDeque<Pair<Int, String>>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            bodies += (ex.requestHeaders.getFirst("Authorization") ?: "") to ex.requestBody.bufferedReader().readText()
            val (code, resp) = synchronized(responses) { responses.removeFirstOrNull() ?: (200 to "{}") }
            val out = if (code == 200 && ex.requestURI.toString().contains("alt=sse")) "data: $resp\r\n\r\n" else resp
            val bytes = out.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        GeminiApi.baseUrl = "http://127.0.0.1:${server.address.port}/v1beta/"
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun noBroadPackageVisibilityPermission() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()
        assertFalse("QUERY_ALL_PACKAGES is restricted on Google Play", "android.permission.QUERY_ALL_PACKAGES" in requested)
    }

    @Test
    fun apiKeyIsKeptOutOfCloudBackup() {
        // An older version stored the key with the settings; it's moved to its own file.
        context.getSharedPreferences("aura_prefs", 0).edit().putString("gemini_api_key", "AIza-test").commit()
        val prefs = com.aura.launcher.data.AuraPrefs(context)
        assertEquals("AIza-test", prefs.apiKey)
        assertFalse(context.getSharedPreferences("aura_prefs", 0).contains("gemini_api_key"))
        assertEquals("AIza-test", context.getSharedPreferences("aura_secrets", 0).getString("gemini_api_key", null))
        val rules = context.resources.getXml(R.xml.data_extraction_rules)
        val excluded = mutableListOf<String>()
        var section = ""
        while (rules.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (rules.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            if (rules.name == "cloud-backup" || rules.name == "device-transfer") section = rules.name
            if (rules.name == "exclude" && section == "cloud-backup") excluded += rules.getAttributeValue(null, "path")
        }
        assertTrue("aura_secrets.xml" in excluded && "aura_google_auth.xml" in excluded)
        assertFalse("settings themselves still back up", "aura_prefs.xml" in excluded)
    }

    @Test
    fun accessibilityServiceCannotReadTheScreen() {
        val parser = context.resources.getXml(R.xml.aura_gestures)
        var canRead: String? = null
        var tool: String? = null
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "accessibility-service") {
                for (i in 0 until parser.attributeCount) {
                    when (parser.getAttributeName(i)) {
                        "canRetrieveWindowContent" -> canRead = parser.getAttributeValue(i)
                        "isAccessibilityTool" -> tool = parser.getAttributeValue(i)
                    }
                }
            }
        }
        assertEquals("false", canRead)
        assertEquals("false", tool) // honest: a launcher isn't an accessibility tool
    }

    private fun text(t: String) = JSONObject().put(
        "candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", t)))))
    ).toString()

    private fun runAgent(credential: GeminiCredential, prompt: String) {
        val agent = AuraAgent(PhoneTools(context, AppRepository(context)))
        val job = CoroutineScope(Dispatchers.IO).async { agent.send(prompt, credential, "gemini-flash-latest") {} }
        val deadline = System.currentTimeMillis() + 15_000
        while (!job.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        runBlocking { job.await() }
    }

    @Test
    fun theListOfInstalledAppsIsNotSentToGemini() {
        responses += 200 to text("Hi!")
        runAgent(GeminiCredential.ApiKey("k"), "Hello")
        val sent = bodies.single().second
        assertFalse(sent.contains("Installed apps"))
        // Opening apps still works: the tool is declared and matching happens on the phone.
        assertTrue(sent.contains("\"open_app\""))
    }

    @Test
    fun narrowGoogleAccessFirstAndBroaderOnlyIfGoogleAsks() {
        var widened = 0
        var refreshed = 0
        val credential = GeminiCredential.GoogleAccount(widenScopes = { widened++; true }) { force -> if (force) { refreshed++; "broad" } else "narrow" }
        responses += 403 to """{"error":{"code":403,"message":"Request had insufficient authentication scopes.","status":"PERMISSION_DENIED","details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}"""
        responses += 200 to text("Done")
        runAgent(credential, "Hello")
        assertEquals(1, widened)
        assertEquals(listOf("Bearer narrow", "Bearer broad"), bodies.map { it.first })
    }

    @Test
    fun otherPermissionErrorsDontTriggerBroaderAccess() {
        var widened = 0
        val credential = GeminiCredential.GoogleAccount(widenScopes = { widened++; true }) { "t" }
        responses += 403 to """{"error":{"code":403,"message":"Generative Language API has not been used in project 1 before or it is disabled. SERVICE_DISABLED"}}"""
        val e = runCatching { runAgent(credential, "Hello") }.exceptionOrNull()
        assertTrue(e is com.aura.launcher.gemini.GeminiHttpException)
        assertEquals(0, widened)
    }

    @Test
    fun aReportCarriesOnlyTheReplyReasonModelAndVersion() = runBlocking {
        val json = ReplyReports.payload("Some reply", ReportReason.OFFENSIVE, "gemini-flash-latest")
        assertEquals(setOf("type", "reason", "reply", "model", "app_version"), json.keys().asSequence().toSet())
        val ok = ReplyReports.send("Some reply", ReportReason.HARMFUL, "m", endpoint = "http://127.0.0.1:${server.address.port}/report")
        assertTrue(ok)
        assertEquals("HARMFUL", JSONObject(bodies.single().second).getString("reason"))
    }

    @Test
    fun everyFinishedReplyCanBeReported() {
        var reported: Pair<ChatItem, ReportReason>? = null
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    com.aura.launcher.ui.GeminiSheet(
                        state = com.aura.launcher.ui.GeminiSheetState(
                            chat = listOf(
                                ChatItem(ChatRole.USER, "Tell me a joke"),
                                ChatItem(ChatRole.GEMINI, "Why did the scarecrow win an award? He was outstanding in his field."),
                            ),
                            busy = false, needsSignIn = false, signingIn = false, signInError = null,
                            accountLabel = "you@gmail.com", model = "gemini-flash-latest", listenRequest = 0,
                        ),
                        onSend = {}, onSignIn = {}, onClear = {}, onOpenSettings = {}, onDismiss = {},
                        onReport = { item, reason -> reported = item to reason },
                    )
                }
            }
        }
        compose.onNodeWithText("Report").performClick()
        compose.waitForIdle()
        save("gemini_report")
        compose.onNodeWithText("Offensive or hateful").performClick()
        assertEquals(ReportReason.OFFENSIVE, reported?.second)
        assertTrue(reported!!.first.text.startsWith("Why did the scarecrow"))
    }

    @Test
    fun gesturesAskFirstAndOnlyOpenSettingsAfterAgreeing() {
        val vm = MainViewModel(context)
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        vm.enableGestureService()
        compose.waitForIdle()
        compose.onNodeWithTag("gesture_disclosure", useUnmergedTree = true).assertExists()
        save("gesture_disclosure")

        compose.onNodeWithText("No thanks").performClick()
        compose.waitForIdle()
        assertNull(shadowOf(context).nextStartedActivity)
        assertTrue(compose.onAllNodes(hasText("Agree and continue")).fetchSemanticsNodes().isEmpty())

        // Asked again next time, and only "Agree" opens Accessibility settings.
        vm.enableGestureService()
        compose.waitForIdle()
        compose.onNodeWithText("Agree and continue").performClick()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(context).nextStartedActivity?.action)
    }

    private fun save(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
