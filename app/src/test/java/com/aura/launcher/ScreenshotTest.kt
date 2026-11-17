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
    fun swipeFromLeftOpensDrawer() {
        val vm = MainViewModel(context)
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        compose.onRoot().performTouchInput {
            down(Offset(12f, height * 0.5f))
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
        }
        compose.mainClock.advanceTimeBy(300)
        save("swipe_midway")
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1500)
        save("swipe_open")
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
