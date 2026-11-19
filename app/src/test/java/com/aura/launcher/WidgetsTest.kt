package com.aura.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.AuraWidgets
import com.aura.launcher.ui.WidgetPicker
import com.aura.launcher.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Home-screen widgets: picking, placing, resizing, moving, removing, and gestures around them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w412dp-h915dp-xhdpi")
class WidgetsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val awm get() = shadowOf(AppWidgetManager.getInstance(context))

    private fun install(pkg: String, appLabel: String) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = appLabel; name = appLabel }
        })
    }

    private fun provider(pkg: String, cls: String, label: String, w: Int = 4, h: Int = 2, configure: Boolean = false) =
        AppWidgetProviderInfo().apply {
            provider = ComponentName(pkg, cls)
            // providerInfo is hidden from apps but is what loadLabel() reads.
            AppWidgetProviderInfo::class.java.getField("providerInfo").set(this, ActivityInfo().apply {
                packageName = pkg; name = cls; nonLocalizedLabel = label
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            })
            widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
            minHeight = 220 // px
            targetCellWidth = w
            targetCellHeight = h
            if (configure) this.configure = ComponentName(pkg, "$cls.Setup")
        }

    private fun seed(): List<AppWidgetProviderInfo> {
        install("com.example.weather", "Weather")
        install("com.example.notes", "Keep Notes")
        val list = listOf(
            provider("com.example.weather", "Forecast", "Forecast"),
            provider("com.example.weather", "Now", "Current conditions", 2, 2),
            provider("com.example.notes", "Note", "Single note", 2, 2, configure = true),
            // Lock-screen-only widgets don't belong on the home screen.
            provider("com.example.notes", "Lock", "Lock screen note").apply { widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD },
        )
        list.forEach { awm.addInstalledProvider(it); awm.addInstalledProvidersForProfile(Process.myUserHandle(), it) }
        return list
    }

    @Test
    fun pickerListsHomeWidgetsGroupedByApp() {
        seed()
        val widgets = AuraWidgets(context)
        val choices = widgets.choices()
        assertEquals(listOf("Single note", "Current conditions", "Forecast"), choices.map { it.label })
        assertEquals(listOf("Keep Notes", "Weather", "Weather"), choices.map { it.appLabel })
        assertEquals("4 × 2", choices.first { it.label == "Forecast" }.cells)
        assertTrue(widgets.needsConfiguration(choices.first { it.label == "Single note" }.info))

        var picked: String? = null
        compose.setContent {
            AuraTheme { WidgetPicker(widgets, AppRepository(context), onPick = { picked = it.label }, onClose = {}) }
        }
        compose.waitUntil(5000) { compose.onAllNodes(hasText("Forecast")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Keep Notes").assertExists()
        save("widget_picker")
        compose.onNodeWithText("Forecast").performClick()
        assertEquals("Forecast", picked)
    }

    @Test
    fun placedWidgetsAreRememberedAndCanBeResizedMovedAndRemoved() {
        val infos = seed()
        awm.setAllowedToBindAppWidgets(true)
        val widgets = AuraWidgets(context)
        val host = shadowOf(widgets.host)
        host.setAppWidgetIdToAllocate(11)
        val a = widgets.allocateId().also { assertTrue(widgets.bindIfAllowed(it, infos[0])) }
        host.setAppWidgetIdToAllocate(12)
        val b = widgets.allocateId().also { assertTrue(widgets.bindIfAllowed(it, infos[1])) }
        widgets.add(a, 160)
        widgets.add(b, 120)
        widgets.resize(a, 48)
        widgets.move(b, -1)

        // A fresh start (e.g. after a reboot) brings them back in the same order and size.
        val again = AuraWidgets(context)
        assertEquals(listOf(b to 120, a to 208), again.placed.map { it.id to it.heightDp })
        again.resize(a, 10_000)
        assertEquals(AuraWidgets.MAX_HEIGHT, again.placed.first { it.id == a }.heightDp)
        again.remove(b)
        assertEquals(listOf(a), AuraWidgets(context).placed.map { it.id })
    }

    @Test
    fun holdingAWidgetOpensItsMenuAndSwipesStillWork() {
        val infos = seed()
        awm.setAllowedToBindAppWidgets(true)
        val vm = MainViewModel(context).also { it.prefs.markWelcomeDone() }
        val id = vm.widgets.allocateId()
        assertTrue(vm.widgets.bindIfAllowed(id, infos[0]))
        vm.widgets.add(id, 180)

        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("widget_$id").assertExists()
        save("home_with_widget")

        compose.onNodeWithTag("widget_$id").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Make taller").assertExists()
        compose.onNodeWithText("Remove").assertExists()
        // The home screen's own menu did not open on top.
        assertTrue(compose.onAllNodes(hasText("Wallpaper & style")).fetchSemanticsNodes().isEmpty())
        save("home_widget_menu")
        compose.onNodeWithText("Make taller").performClick()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(228, vm.widgets.placed.single().heightDp)

        // A sideways swipe that starts on the widget still opens the app drawer.
        compose.onNodeWithTag("widget_$id").performTouchInput {
            down(Offset(width * 0.9f, height / 2f))
            repeat(12) { moveBy(Offset(-60f, 0f)); advanceEventTime(16) }
            up()
        }
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithText("Search apps, settings, or ask Gemini…").assertExists()
    }

    @Test
    fun homeMenuOffersWidgets() {
        val vm = MainViewModel(context).also { it.prefs.markWelcomeDone() }
        compose.setContent { AuraTheme { com.aura.launcher.ui.AuraRoot(vm) {} } }
        compose.onRoot().performTouchInput {
            down(Offset(width * 0.5f, height * 0.55f))
            advanceEventTime(900)
            up()
        }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Widgets").performClick()
        compose.mainClock.advanceTimeBy(400)
        assertTrue(vm.widgetPickerOpen)
        assertFalse(vm.widgets.placed.isNotEmpty())
    }

    private fun save(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
