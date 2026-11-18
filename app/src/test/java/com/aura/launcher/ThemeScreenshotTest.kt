package com.aura.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.AuraPrefs
import com.aura.launcher.data.IconTheme
import com.aura.launcher.data.Sky
import com.aura.launcher.data.WeatherNow
import com.aura.launcher.ui.AuraBackdrop
import com.aura.launcher.ui.HomeActions
import com.aura.launcher.ui.HomeScreen
import com.aura.launcher.ui.theme.AuraPalette
import com.aura.launcher.ui.theme.AuraTheme
import com.aura.launcher.ui.theme.ColorSource
import com.aura.launcher.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Light mode, wallpaper colors, themed icons and streaming replies, rendered for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w412dp-h915dp-xhdpi")
class ThemeScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun home(palette: AuraPalette, name: String) {
        val repo = AppRepository(context)
        compose.setContent {
            AuraTheme(palette) {
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
        save(name)
    }

    @Test fun homeLight() = home(AuraPalette.Light, "home_light")

    @Test fun homeWallpaperColors() = home(AuraPalette.resolve(context, ThemeMode.DARK, ColorSource.WALLPAPER, true), "home_wallpaper_colors")

    @Test fun homeWallpaperColorsLight() = home(AuraPalette.resolve(context, ThemeMode.LIGHT, ColorSource.WALLPAPER, false), "home_wallpaper_colors_light")

    @Test
    fun themeModeFollowsThePhone() {
        assertTrue(AuraPalette.resolve(context, ThemeMode.SYSTEM, ColorSource.AURA, systemDark = false).isLight)
        assertTrue(!AuraPalette.resolve(context, ThemeMode.SYSTEM, ColorSource.AURA, systemDark = true).isLight)
        assertEquals(AuraPalette.Dark, AuraPalette.resolve(context, ThemeMode.DARK, ColorSource.AURA, systemDark = false))
    }

    @Test
    fun geminiLightWhileStreaming() {
        compose.setContent {
            AuraTheme(AuraPalette.Light) {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    com.aura.launcher.ui.GeminiSheet(
                        state = com.aura.launcher.ui.GeminiSheetState(
                            chat = listOf(
                                ChatItem(ChatRole.USER, "Plan a quick dinner I can make in 20 minutes"),
                                ChatItem(ChatRole.THOUGHT, "**Picking something fast**\nLooking for a one-pan dish", millis = 2100),
                                ChatItem(ChatRole.GEMINI, "Try lemon garlic shrimp pasta: boil spaghetti, and while it cooks", streaming = true),
                            ),
                            busy = true, needsSignIn = false, signingIn = false, signInError = null,
                            accountLabel = "you@gmail.com", model = "gemini-flash-latest", listenRequest = 0,
                        ),
                        onSend = {}, onSignIn = {}, onClear = {}, onOpenSettings = {}, onDismiss = {},
                    )
                }
            }
        }
        // The reply is shown as it's written, without a separate "Working on it…" line.
        compose.onNodeWithText("Try lemon garlic shrimp pasta: boil spaghetti, and while it cooks").assertExists()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("Working on it…")).fetchSemanticsNodes().isEmpty())
        save("gemini_streaming_light")
    }

    @Test
    fun thinkingWhileStreaming() {
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    com.aura.launcher.ui.GeminiSheet(
                        state = com.aura.launcher.ui.GeminiSheetState(
                            chat = listOf(
                                ChatItem(ChatRole.USER, "What's a good time to leave for the airport?"),
                                ChatItem(ChatRole.THOUGHT, "**Checking traffic**\nEvening rush usually adds 20 minutes", streaming = true),
                            ),
                            busy = true, needsSignIn = false, signingIn = false, signInError = null,
                            accountLabel = "you@gmail.com", model = "gemini-flash-latest", listenRequest = 0,
                        ),
                        onSend = {}, onSignIn = {}, onClear = {}, onOpenSettings = {}, onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Thinking…  ▸").assertExists()
        compose.onNodeWithText("Evening rush usually adds 20 minutes").assertExists()
        save("gemini_thinking_live")
    }

    @Test
    fun lookSettings() {
        val prefs = AuraPrefs(context)
        compose.setContent {
            AuraTheme(if (prefs.themeMode == ThemeMode.LIGHT) AuraPalette.Light else AuraPalette.Dark) {
                com.aura.launcher.ui.SettingsScreen(
                    prefs = prefs, signingIn = false, signInError = null, onSignIn = {}, onSignOut = {}, onRunCheck = {},
                    requestPermissions = {}, onClose = {},
                )
            }
        }
        compose.onNodeWithText("Light").performScrollTo().performClick()
        compose.onNodeWithText("Themed icons").performScrollTo()
        compose.waitForIdle()
        assertEquals(ThemeMode.LIGHT, prefs.themeMode)
        save("settings_look_light")
    }

    // ---- Themed icons ----

    private fun monoIcon(withMono: Boolean): AdaptiveIconDrawable {
        val bg = ColorDrawable(AColor.RED)
        val fg = ColorDrawable(AColor.BLUE)
        // A real monochrome layer is a small glyph in the middle of the 108dp canvas.
        val mono = if (withMono) android.graphics.drawable.InsetDrawable(
            android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
                intrinsicWidth = 108; intrinsicHeight = 108; paint.color = AColor.BLACK
            },
            0.38f,
        ) else null
        return AdaptiveIconDrawable(bg, fg, mono)
    }

    @Test
    fun themedIconUsesTheMonochromeLayerInThemeColors() {
        val theme = IconTheme(background = 0xFF3A3531.toInt(), foreground = 0xFFEADBC8.toInt())
        val themed = AppRepository.themedIcon(monoIcon(true), theme)
        assertNotNull(themed)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        themed!!.setBounds(0, 0, 96, 96)
        themed.draw(Canvas(bmp))
        // Center: the monochrome glyph, tinted; edge (inside the mask): the theme background. No red/blue.
        assertEquals(theme.foreground, bmp.getPixel(48, 48))
        val edge = bmp.getPixel(48, 6)
        assertTrue("edge should be theme background or masked out, was ${Integer.toHexString(edge)}", edge == theme.background || AColor.alpha(edge) == 0)
    }

    @Test
    fun appsWithoutAMonochromeIconKeepTheirOwn() {
        assertNull(AppRepository.themedIcon(monoIcon(false), IconTheme(0, 0)))
        assertNull(AppRepository.themedIcon(ColorDrawable(AColor.GREEN), IconTheme(0, 0)))
    }

    private fun save(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
