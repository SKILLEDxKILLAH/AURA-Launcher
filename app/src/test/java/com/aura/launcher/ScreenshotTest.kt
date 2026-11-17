package com.aura.launcher

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.Sky
import com.aura.launcher.data.WeatherNow
import com.aura.launcher.ui.AppDrawer
import com.aura.launcher.ui.AuraBackdrop
import com.aura.launcher.ui.DrawerActions
import com.aura.launcher.ui.HomeActions
import com.aura.launcher.ui.HomeScreen
import com.aura.launcher.ui.theme.AuraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders AURA's key screens to PNGs under app/build/screenshots for visual review.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w412dp-h915dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val sampleApps by lazy {
        listOf(
            "Calculator", "Calendar", "Camera", "Chrome", "Clock", "Contacts", "Drive", "Files", "Gallery", "Gemini",
            "Gmail", "Google", "Google TV", "Global Goals", "Home", "Keep Notes", "Maps", "Messages", "Music", "News",
            "Phone", "Photos", "Play Store", "Recorder", "Settings", "Spotify", "Translate", "Wallet", "Weather", "YouTube",
        ).map { label ->
            val subtitles = mapOf("Maps" to "Navigate, explore, plan", "Messages" to "Text, chat, and share",
                "Music" to "Play, browse, and manage", "News" to "Top stories and your interests",
                "Photos" to "Back up, organize, and edit", "Play Store" to "Discover and install apps")
            AppEntry(
                key = label, label = label, packageName = "x.$label", component = ComponentName("x", label),
                user = Process.myUserHandle(), subtitle = subtitles[label] ?: "", letter = AppRepository.letterOf(label), isWork = false,
            )
        }
    }

    private val noopDrawer = DrawerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test
    fun home() {
        val repo = AppRepository(context)
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    HomeScreen(
                        repo = repo, dock = emptyList(),
                        weather = WeatherNow(72, "°F", "Partly cloudy", Sky.PARTLY, true),
                        showWeather = true, weatherNeedsPermission = false,
                        actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}),
                    )
                }
            }
        }
        save("home")
    }

    @Test
    fun drawer() {
        renderDrawer()
        save("drawer")
    }

    @Test
    fun drawerScrubbing() {
        renderDrawer()
        compose.onNodeWithTag("alphabet").performTouchInput {
            down(Offset(width / 2f, height * 0.18f))
            moveTo(Offset(width / 2f, height * 0.36f))
        }
        compose.mainClock.advanceTimeBy(800)
        save("drawer_scrub")
    }

    @Test
    fun swipeFromRightOpensDrawer() {
        val vm = MainViewModel(context)
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        compose.onRoot().performTouchInput {
            down(Offset(width - 12f, height * 0.5f))
            moveBy(Offset(-width * 0.2f, 0f))
            moveBy(Offset(-width * 0.2f, 0f))
        }
        compose.mainClock.advanceTimeBy(300)
        save("swipe_midway")
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1500)
        save("swipe_open")
    }

    @Test
    fun geminiSignIn() {
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    com.aura.launcher.ui.GeminiSheet(
                        state = com.aura.launcher.ui.GeminiSheetState(
                            chat = emptyList(), busy = false, needsSignIn = true, signingIn = false, signInError = null,
                            accountLabel = null, model = "gemini-flash-latest", listenRequest = 0,
                        ),
                        onSend = {}, onSignIn = {}, onClear = {}, onOpenSettings = {}, onDismiss = {},
                    )
                }
            }
        }
        save("gemini_signin")
    }

    @Test
    fun geminiChat() {
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    com.aura.launcher.ui.GeminiSheet(
                        state = com.aura.launcher.ui.GeminiSheetState(
                            chat = listOf(
                                ChatItem(ChatRole.USER, "Turn on the flashlight and set a timer for 10 minutes"),
                                ChatItem(ChatRole.ACTION, "Flashlight on"),
                                ChatItem(ChatRole.ACTION, "Timer started · 10m"),
                                ChatItem(ChatRole.GEMINI, "Done — flashlight's on and your 10-minute timer is running."),
                            ),
                            busy = false, needsSignIn = false, signingIn = false, signInError = null,
                            accountLabel = "you@gmail.com", model = "gemini-flash-latest", listenRequest = 0,
                        ),
                        onSend = {}, onSignIn = {}, onClear = {}, onOpenSettings = {}, onDismiss = {},
                    )
                }
            }
        }
        save("gemini_chat")
    }

    @Test
    fun pressAndHoldMenu() {
        val vm = MainViewModel(context)
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        compose.onRoot().performTouchInput {
            down(Offset(width * 0.5f, height * 0.55f))
            advanceEventTime(900)
            up()
        }
        compose.mainClock.advanceTimeBy(400)
        save("home_menu")
    }

    @Test
    fun diagnostics() {
        compose.setContent {
            AuraTheme {
                com.aura.launcher.ui.DiagnosticsSheet(
                    results = listOf(
                        com.aura.launcher.gemini.CheckResult("Device", null, "Android 17 (API 37) · Google Pixel"),
                        com.aura.launcher.gemini.CheckResult("Sign-in", true, "Using Google account"),
                        com.aura.launcher.gemini.CheckResult("Access token", true, "Obtained in 84 ms"),
                        com.aura.launcher.gemini.CheckResult("Models", true, "14 chat models available · current model OK"),
                        com.aura.launcher.gemini.CheckResult("Gemini reply", true, "“AURA OK” in 912 ms"),
                        com.aura.launcher.gemini.CheckResult("Phone actions", true, "Gemini chose get_device_status in 1104 ms"),
                    ),
                    running = false, onRunAgain = {}, onClose = {},
                )
            }
        }
        save("diagnostics")
    }

    @Test
    fun editTopLabel() {
        val vm = MainViewModel(context)
        vm.prefs.updateTopLabel("")
        vm.prefs.updateTopLineMode(com.aura.launcher.data.TopLineMode.LABEL)
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        val label = com.aura.launcher.ui.defaultTopLabel()
        compose.onNodeWithText(label).performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("Hello, world")
        compose.mainClock.advanceTimeBy(300)
        save("top_label_editing")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performImeAction()
        compose.mainClock.advanceTimeBy(300)
        org.junit.Assert.assertEquals("Hello, world", vm.prefs.topLabel)
        compose.onNodeWithText("Hello, world").assertExists()
        // Clearing it brings back the default.
        compose.onNodeWithText("Hello, world").performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performImeAction()
        org.junit.Assert.assertNull(vm.prefs.topLabel)
        compose.onNodeWithText(label).assertExists()
    }

    @Test
    fun weatherAtTop() {
        val repo = AppRepository(context)
        val weather = WeatherNow(72, "°F", "Partly cloudy", Sky.PARTLY, true)
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    HomeScreen(
                        repo = repo, dock = emptyList(), weather = weather,
                        showWeather = false, weatherNeedsPermission = false,
                        actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}),
                        topLine = com.aura.launcher.ui.TopLineState(
                            com.aura.launcher.data.TopLineMode.WEATHER, null, weather, false, {}, {}, {},
                        ),
                    )
                }
            }
        }
        compose.onNodeWithText("72°F").assertExists()
        save("weather_top")
    }

    @Test
    fun modelAndThinkingPanel() {
        val models = listOf(
            "gemini-pro-latest" to "Gemini Pro Latest", "gemini-flash-lite-latest" to "Gemini Flash-Lite Latest",
            "gemini-flash-latest" to "Gemini Flash Latest", "gemini-4-hydrogen" to "Gemini 4 Hydrogen",
            "gemini-4-carbon" to "Gemini 4 Carbon", "gemini-3.8-flash" to "Gemini 3.8 Flash",
        ).map { (id, name) -> com.aura.launcher.gemini.GeminiModel(id, name, "") }
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    com.aura.launcher.ui.GeminiSheet(
                        state = com.aura.launcher.ui.GeminiSheetState(
                            chat = emptyList(), busy = false, needsSignIn = false, signingIn = false, signInError = null,
                            accountLabel = "you@gmail.com", model = "gemini-flash-latest", listenRequest = 0,
                            models = models, thinking = com.aura.launcher.data.ThinkingLevel.MEDIUM,
                        ),
                        onSend = {}, onSignIn = {}, onClear = {}, onOpenSettings = {}, onDismiss = {},
                    )
                }
            }
        }
        save("model_bar")
        compose.onNodeWithText("Change").performClick()
        compose.mainClock.advanceTimeBy(500)
        save("model_panel")
    }

    @Test
    fun thoughtInChat() {
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    com.aura.launcher.ui.GeminiSheet(
                        state = com.aura.launcher.ui.GeminiSheetState(
                            chat = listOf(
                                ChatItem(ChatRole.USER, "Plan a quiet evening and set a reminder at 8"),
                                ChatItem(ChatRole.THOUGHT, "The user wants a calm plan and an alarm at 20:00.", millis = 3400),
                                ChatItem(ChatRole.ACTION, "Alarm set for 20:00"),
                                ChatItem(ChatRole.GEMINI, "Done — tea, a short walk and a book. Your 8 PM reminder is set."),
                                ChatItem(ChatRole.NOTICE, "This model doesn't support Medium; using High"),
                            ),
                            busy = false, needsSignIn = false, signingIn = false, signInError = null,
                            accountLabel = "you@gmail.com", model = "gemini-4-hydrogen", listenRequest = 0,
                            thinking = com.aura.launcher.data.ThinkingLevel.MEDIUM,
                        ),
                        onSend = {}, onSignIn = {}, onClear = {}, onOpenSettings = {}, onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Thought for 3s  ▸").performClick()
        compose.mainClock.advanceTimeBy(400)
        save("thought_chat")
    }

    @Test
    fun swipeRightOpensFeedAndLeftStillOpensDrawer() {
        val vm = MainViewModel(context)
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        compose.onRoot().performTouchInput {
            down(Offset(40f, height * 0.5f))
            repeat(8) { moveBy(Offset(width * 0.08f, 0f)) }
            up()
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("Brief me").assertExists()
        compose.onNodeWithText("Top stories").assertExists()
        // Swipe left closes the feed…
        compose.onRoot().performTouchInput {
            down(Offset(width - 40f, height * 0.6f))
            repeat(8) { moveBy(Offset(-width * 0.08f, 0f)) }
            up()
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("Brief me").assertDoesNotExist()
        // …and swiping left again from home opens the app drawer, as before.
        compose.onRoot().performTouchInput {
            down(Offset(width - 40f, height * 0.5f))
            repeat(8) { moveBy(Offset(-width * 0.08f, 0f)) }
            up()
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("Search apps, settings, or ask Gemini…").assertExists()
    }

    @Test
    fun newsFeed() {
        val now = java.time.Instant.now()
        val stories = listOf(
            com.aura.launcher.data.NewsStory("City opens a new riverside park with miles of trails", "Example Times", null, "https://example.com/1", now.minusSeconds(1800)),
            com.aura.launcher.data.NewsStory("Researchers unveil a quieter, more efficient heat pump", "Example Science", null, "https://example.com/2", now.minusSeconds(3 * 3600)),
            com.aura.launcher.data.NewsStory("Local team clinches playoff spot in overtime thriller", "Example Sports", null, "https://example.com/3", now.minusSeconds(5 * 3600)),
            com.aura.launcher.data.NewsStory("New phone update brings longer battery life", "Example Tech", null, "https://example.com/4", now.minusSeconds(26 * 3600)),
        )
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    com.aura.launcher.ui.FeedScreen(
                        state = com.aura.launcher.ui.FeedState(
                            topic = com.aura.launcher.data.NewsTopic.TOP, stories = stories, loading = false, error = null,
                            weather = WeatherNow(46, "°F", "Clear", Sky.CLEAR, false, high = 52, low = 38),
                            weatherNeedsPermission = false,
                        ),
                        actions = com.aura.launcher.ui.FeedActions({}, {}, {}, {}, {}),
                    )
                }
            }
        }
        save("news_feed")
    }

    private fun renderDrawer() {
        val repo = AppRepository(context)
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop(drift = 1f)
                    AppDrawer(
                        apps = sampleApps, favorites = sampleApps.take(3), favoriteKeys = emptySet(), repo = repo,
                        dock = emptyList(), query = "", onQueryChange = {}, focusSearchToken = 0,
                        actions = noopDrawer,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
    }

    private fun save(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
