package com.aura.launcher

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        val vm = MainViewModel(context).also { it.prefs.markWelcomeDone() }
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
        val vm = MainViewModel(context).also { it.prefs.markWelcomeDone() }
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
        val vm = MainViewModel(context).also { it.prefs.markWelcomeDone() }
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
        val vm = MainViewModel(context).also { it.prefs.markWelcomeDone() }
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
        // Synthetic "photos" so the screenshot shows the banner fade without real images.
        fun photo(url: String, top: Int, bottom: Int) {
            val bmp = android.graphics.Bitmap.createBitmap(800, 450, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.drawPaint(android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 800f, 450f, top, bottom, android.graphics.Shader.TileMode.CLAMP)
            })
            val sun = android.graphics.Paint().apply { color = 0xFFFFE2A8.toInt(); isAntiAlias = true }
            c.drawCircle(620f, 140f, 70f, sun)
            val hill = android.graphics.Paint().apply { color = 0xFF2E4A3A.toInt(); isAntiAlias = true }
            c.drawOval(-200f, 300f, 1000f, 700f, hill)
            com.aura.launcher.ui.NetImages.put(url, bmp)
        }
        photo("test://park", 0xFF6FA8DC.toInt(), 0xFFF6C38B.toInt())
        photo("test://pump", 0xFF4B6584.toInt(), 0xFFA5B1C2.toInt())
        val stories = listOf(
            com.aura.launcher.data.NewsStory("City opens a new riverside park with miles of trails", "Example Times", null, "https://example.com/1", now.minusSeconds(1800),
                summary = "The 40-acre park links three neighborhoods with walking and cycling paths along the water.", imageUrl = "test://park"),
            com.aura.launcher.data.NewsStory("Researchers unveil a quieter, more efficient heat pump", "Example Science", null, "https://example.com/2", now.minusSeconds(3 * 3600),
                summary = "Early tests show it uses a third less power than current models.", imageUrl = "test://pump"),
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

    /** Regression: with the keyboard up (less height), the typing field must stay fully visible. */
    @Test
    fun typingFieldStaysAboveKeyboard() {
        val visibleHeight = 560.dp // ~915dp screen minus a ~355dp keyboard
        val chat = buildList {
            repeat(6) {
                add(ChatItem(ChatRole.USER, "Turn on the flashlight"))
                add(ChatItem(ChatRole.ACTION, "Flashlight on"))
                add(ChatItem(ChatRole.GEMINI, "I've turned on your flashlight."))
            }
            add(ChatItem(ChatRole.GEMINI, "Newest reply"))
        }
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    Box(Modifier.fillMaxWidth().height(visibleHeight)) {
                        com.aura.launcher.ui.GeminiSheet(
                            state = com.aura.launcher.ui.GeminiSheetState(
                                chat = chat, busy = false, needsSignIn = false, signingIn = false, signInError = null,
                                accountLabel = "you@gmail.com", model = "gemini-flash-lite-latest", listenRequest = 0,
                                thinking = com.aura.launcher.data.ThinkingLevel.MINIMAL,
                            ),
                            onSend = {}, onSignIn = {}, onClear = {}, onOpenSettings = {}, onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        val field = compose.onNodeWithText("What can I do for you?").fetchSemanticsNode().boundsInRoot
        val limit = with(compose.density) { visibleHeight.toPx() }
        org.junit.Assert.assertTrue("typing field bottom ${field.bottom} must be above keyboard at $limit", field.bottom <= limit + 0.5f)
        org.junit.Assert.assertTrue("typing field must have real height", field.height > 20f)
        // The newest message is scrolled into view, just above the typing field.
        val newest = compose.onNodeWithText("Newest reply").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("newest message must be visible", newest.bottom <= field.top && newest.top >= 0f)
        save("keyboard_layout")
    }

    private fun gizmoDrawer(onLaunch: (AppEntry) -> Unit): List<AppEntry> {
        fun app(label: String) = AppEntry(
            key = label, label = label, packageName = "x.$label", component = ComponentName("x", label),
            user = Process.myUserHandle(), subtitle = "", letter = AppRepository.letterOf(label), isWork = false,
        )
        val apps = listOf("Alarm", "Books", "Camera", "Drive", "Email", "Files") +
            (1..16).map { "Gizmo %02d".format(it) } +
            listOf("Home", "Maps", "Notes", "Phone", "Radio", "Settings", "Tasks", "Weather")
        val entries = apps.map(::app)
        val repo = AppRepository(context)
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop(drift = 1f)
                    com.aura.launcher.ui.AppDrawer(
                        apps = entries, favorites = emptyList(), favoriteKeys = emptySet(), repo = repo,
                        dock = emptyList(), query = "", onQueryChange = {}, focusSearchToken = 0,
                        actions = noopDrawer.copy(launch = onLaunch),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        return entries
    }

    private fun scrubTo(letterIndex: Int, letterCount: Int, release: androidx.compose.ui.test.TouchInjectionScope.() -> Float) {
        val node = compose.onNodeWithTag("alphabet")
        var yLetter = 0f
        node.performTouchInput {
            val spacing = minOf(height.toFloat() / letterCount, 30.dp.toPx())
            yLetter = (height - spacing * letterCount) / 2f + spacing * (letterIndex + 0.5f)
            down(Offset(width / 2f, yLetter))
        }
        compose.mainClock.advanceTimeBy(600)
        // Slide left into the preview, to the right of the (short) app names.
        node.performTouchInput { moveTo(Offset(-200.dp.toPx(), yLetter)) }
        compose.mainClock.advanceTimeBy(600)
        node.performTouchInput { moveTo(Offset(-200.dp.toPx(), release())) }
        compose.mainClock.advanceTimeBy(300)
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(600)
    }

    @Test
    fun scrubReachesTheLastAppInALongLetter() {
        var launched: AppEntry? = null
        gizmoDrawer { launched = it }
        // Letters: A B C D E F G H M N P R S T W → G is index 6 of 15. Release at the bottom.
        scrubTo(6, 15) { height - 3f }
        org.junit.Assert.assertEquals("Gizmo 16", launched?.label)
    }

    @Test
    fun scrubReachesTheFirstAppAndSavesAScreenshot() {
        var launched: AppEntry? = null
        gizmoDrawer { launched = it }
        val node = compose.onNodeWithTag("alphabet")
        var yLetter = 0f
        node.performTouchInput {
            val spacing = minOf(height.toFloat() / 15, 30.dp.toPx())
            yLetter = (height - spacing * 15) / 2f + spacing * 6.5f
            down(Offset(width / 2f, yLetter))
        }
        compose.mainClock.advanceTimeBy(600)
        node.performTouchInput { moveTo(Offset(-200.dp.toPx(), yLetter)) }
        compose.mainClock.advanceTimeBy(600)
        // First row sits just under the 56dp "G" header at the top of the list.
        node.performTouchInput { moveTo(Offset(-200.dp.toPx(), (56 + 20).dp.toPx())) }
        compose.mainClock.advanceTimeBy(400)
        save("scrub_long_list")
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(600)
        org.junit.Assert.assertEquals("Gizmo 01", launched?.label)
    }

    @Test
    fun drawerWithSuggestions() {
        val repo = AppRepository(context)
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop(drift = 1f)
                    com.aura.launcher.ui.AppDrawer(
                        apps = sampleApps, favorites = emptyList(), favoriteKeys = emptySet(), repo = repo,
                        dock = emptyList(), query = "", onQueryChange = {}, focusSearchToken = 0,
                        actions = noopDrawer,
                        suggested = sampleApps.filter { it.label in setOf("Maps", "Spotify", "Gmail", "Messages", "Chrome") },
                        recent = sampleApps.filter { it.label in setOf("Camera", "Photos", "Weather") },
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Suggested").assertExists()
        save("drawer_suggested")
        compose.onNodeWithText("Recent").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Weather").assertExists()
    }

    private fun clickLabel(label: String) = androidx.compose.ui.test.SemanticsMatcher("click label = $label") {
        it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == label
    }

    @Test
    fun geminiCanBeRemovedFromTheDrawerInSettings() {
        val vm = MainViewModel(context).also { it.prefs.markWelcomeDone() }
        vm.prefs.updateDrawerGeminiBar(true)
        vm.prefs.updateDrawerGeminiButton(true)
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }

        // Turn both off through the Settings screen.
        vm.settingsOpen = true
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Gemini bar").performScrollTo().performClick()
        compose.onNodeWithText("Gemini button").performScrollTo().performClick()
        org.junit.Assert.assertFalse(vm.prefs.drawerGeminiBar)
        org.junit.Assert.assertFalse(vm.prefs.drawerGeminiButton)
        vm.settingsOpen = false
        compose.mainClock.advanceTimeBy(500)

        // Open the drawer: no Gemini bar or button there, but the home card is untouched.
        compose.onRoot().performTouchInput {
            down(Offset(width - 40f, height * 0.5f)); repeat(8) { moveBy(Offset(-width * 0.08f, 0f)) }; up()
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("Search apps, settings, or ask Gemini…").assertExists()
        compose.onNodeWithText("What can I do for you?").assertDoesNotExist()
        compose.onNode(clickLabel("Open Gemini")).assertDoesNotExist()
        save("drawer_no_gemini")

        // Turning them back on restores both.
        vm.prefs.updateDrawerGeminiBar(true)
        vm.prefs.updateDrawerGeminiButton(true)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("What can I do for you?").assertExists()
        compose.onNode(clickLabel("Open Gemini")).assertExists()
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
