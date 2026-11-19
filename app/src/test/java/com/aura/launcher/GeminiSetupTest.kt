package com.aura.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.GeminiAuthMode
import com.aura.launcher.gemini.GeminiApi
import com.aura.launcher.gemini.LocalModel
import com.aura.launcher.gemini.NanoStatus
import com.aura.launcher.ui.theme.AuraTheme
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/** Setting Gemini up without Google Cloud: on the phone (Nano) or with a free AI Studio key. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w412dp-h915dp-xhdpi")
class GeminiSetupTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** Stands in for Gemini Nano: answers in two streamed pieces. */
    private class FakeNano(var state: NanoStatus) : LocalModel {
        val prompts = mutableListOf<Pair<List<Pair<Boolean, String>>, String>>()
        var downloaded = false
        override suspend fun status() = state
        override suspend fun download(onProgress: (Float) -> Unit) { downloaded = true; state = NanoStatus.AVAILABLE }
        override suspend fun name() = "Gemini Nano v3"
        override suspend fun reply(history: List<Pair<Boolean, String>>, prompt: String, onPartial: suspend (String) -> Unit): String {
            prompts += history to prompt
            onPartial("Paris is")
            onPartial("Paris is the capital of France.")
            return "Paris is the capital of France."
        }
    }

    private lateinit var server: HttpServer
    private var modelsAnswer = 200 to """{"models":[{"name":"models/gemini-flash-latest","displayName":"Gemini Flash Latest","supportedGenerationMethods":["generateContent"]}]}"""
    private val seenKeys = mutableListOf<String?>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seenKeys += ex.requestHeaders.getFirst("x-goog-api-key")
            val (code, body) = modelsAnswer
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        GeminiApi.baseUrl = "http://127.0.0.1:${server.address.port}/v1beta/"
    }

    @After
    fun stop() = server.stop(0)

    private fun vmWith(nano: LocalModel): MainViewModel = MainViewModel(context).also { it.localModel = nano; it.prefs.markWelcomeDone() }

    private fun settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!until()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    @Test
    fun onThePhoneInOneTapThenQuestionsAndCommandsWork() {
        val nano = FakeNano(NanoStatus.DOWNLOADABLE)
        val vm = vmWith(nano)
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        vm.ask("What's the capital of France?")     // asked before any setup: kept, answered after
        settle { vm.nanoStatus == NanoStatus.DOWNLOADABLE }
        compose.onNodeWithTag("gemini_setup", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("On this phone").assertExists()
        save("gemini_setup")

        compose.onNodeWithText("Download and use (one time)").performClick()
        settle { vm.chat.any { it.role == ChatRole.GEMINI && !it.streaming } }
        assertTrue(nano.downloaded)
        assertEquals(GeminiAuthMode.ON_DEVICE, vm.prefs.authMode)
        assertEquals("Paris is the capital of France.", vm.chat.last { it.role == ChatRole.GEMINI }.text)
        compose.onNodeWithText("On this phone · private").assertExists()
        save("gemini_on_device")

        // Everyday commands are done by AURA itself, not sent to the model.
        val asked = nano.prompts.size
        vm.ask("Set a timer for 10 minutes")
        settle { !vm.agentBusy && vm.chat.any { it.role == ChatRole.ACTION } }
        assertEquals(asked, nano.prompts.size)
        assertNotNull(vm.chat.lastOrNull { it.role == ChatRole.ACTION })

        // Follow-up questions carry the conversation.
        vm.ask("And of Italy?")
        settle { nano.prompts.size == asked + 1 && !vm.agentBusy }
        assertEquals("What's the capital of France?", nano.prompts.last().first.first().second)
    }

    @Test
    fun phonesWithoutNanoGetTheFreeKeyFirst() {
        val vm = vmWith(FakeNano(NanoStatus.UNAVAILABLE))
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        vm.openGemini()
        settle { vm.nanoStatus == NanoStatus.UNAVAILABLE }
        assertTrue(compose.onAllNodes(hasText("On this phone")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Recommended").assertExists()
        save("gemini_setup_no_nano")
    }

    @Test
    fun aCopiedKeyIsCheckedWithGoogleAndUsed() {
        val vm = vmWith(FakeNano(NanoStatus.UNAVAILABLE))
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        vm.openGemini()
        settle { vm.nanoStatus == NanoStatus.UNAVAILABLE }

        compose.onNodeWithText("Open Google AI Studio").performScrollTo().performClick()
        assertEquals(MainViewModel.AI_STUDIO_KEYS, shadowOf(context).nextStartedActivity?.dataString)

        val key = "AIzaSyD-test_key_1234567890abcdefghijklm"
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("key", "  $key\n"))
        compose.onNodeWithText("Paste my key").performScrollTo().performClick()
        settle { vm.prefs.authMode == GeminiAuthMode.API_KEY && !vm.keyChecking }
        assertEquals(key, vm.prefs.apiKey)
        assertEquals(key, seenKeys.last())
        assertTrue(vm.prefs.geminiReady)
    }

    @Test
    fun aWrongKeyExplainsWhatToDo() {
        val vm = vmWith(FakeNano(NanoStatus.UNAVAILABLE))
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("x", "hello there"))
        vm.pasteApiKey()
        assertTrue(vm.keyError!!.contains("AIza"))

        modelsAnswer = 400 to """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}"""
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("k", "AIzaSyD-wrong_key_1234567890abcdefghijklm"))
        vm.pasteApiKey()
        settle { !vm.keyChecking }
        assertEquals("Google didn't accept that key. Copy it again from AI Studio.", vm.keyError)
        assertTrue(!vm.prefs.geminiReady)
    }

    private fun save(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
