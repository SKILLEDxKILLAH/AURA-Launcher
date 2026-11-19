package com.aura.launcher

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Process
import android.provider.ContactsContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.Calculator
import com.aura.launcher.ui.AppDrawer
import com.aura.launcher.ui.AuraBackdrop
import com.aura.launcher.ui.DrawerActions
import com.aura.launcher.ui.theme.AuraTheme
import com.aura.launcher.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Welcome on first launch, choosing dock apps, and sums in search. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w412dp-h915dp-xhdpi")
class FeatureGapsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun calculatorAnswersSumsOnly() {
        assertEquals("84", Calculator.evaluate("12*7"))
        assertEquals("3.5", Calculator.evaluate("(3+4)/2"))
        assertEquals("1,024", Calculator.evaluate("2^10"))
        assertEquals("12", Calculator.evaluate("15% of 80"))
        assertEquals("21", Calculator.evaluate("3 x 7"))
        assertEquals("0.3333333333", Calculator.evaluate("1/3")?.take(12))
        assertEquals("1,234,567", Calculator.evaluate("1234566+1"))
        assertEquals("-5", Calculator.evaluate("-2-3"))
        listOf("42", "maps", "1/0", "2+", "covid-19", "x", "").forEach { assertNull("“$it”", Calculator.evaluate(it)) }
    }

    @Test
    fun welcomeWalksThroughAndThenStaysAway() {
        val vm = MainViewModel(context)
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        compose.onNodeWithTag("welcome", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Welcome to AURA").assertExists()
        save("welcome")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Set AURA as home screen").assertExists()
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Pick a look").assertExists()
        compose.onNodeWithText("Light").performClick()
        assertEquals(ThemeMode.LIGHT, vm.prefs.themeMode)
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Set up Gemini").performClick()
        compose.waitForIdle()
        assertTrue(vm.prefs.welcomeDone)
        assertTrue(vm.geminiSheetOpen && vm.geminiSetupOpen)
        assertTrue(MainViewModel(context).prefs.welcomeDone)
    }

    @Test
    fun dockStartsWithDefaultsAndCanBeChosen() {
        val vm = MainViewModel(context).also { it.prefs.markWelcomeDone() }
        fun app(label: String) = AppEntry(
            key = label, label = label, packageName = "x.$label", component = ComponentName("x.$label", label),
            user = Process.myUserHandle(), subtitle = "", letter = AppRepository.letterOf(label), isWork = false,
        )
        val spotify = app("Spotify")
        assertFalse(vm.isInDock(spotify))
        vm.toggleDock(spotify)
        assertTrue(vm.prefs.dockKeys.contains("Spotify"))
        vm.toggleDock(spotify)
        assertFalse(vm.prefs.dockKeys.contains("Spotify"))
        vm.resetDock()
        assertTrue(vm.prefs.dockKeys.isEmpty())
    }

    @Test
    fun searchShowsTheAnswerFirst() {
        val repo = AppRepository(context)
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop(drift = 1f)
                    AppDrawer(
                        apps = emptyList(), favorites = emptyList(), favoriteKeys = emptySet(), repo = repo,
                        dock = emptyList(), query = "15% of 80", onQueryChange = {}, focusSearchToken = 0,
                        actions = DrawerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
                    )
                }
            }
        }
        compose.onNodeWithText("12").assertExists()
        compose.onNodeWithText("12").performClick()
        val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
        assertEquals("12", clip?.getItemAt(0)?.text?.toString())
        save("search_calculator")
    }

    private fun save(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
