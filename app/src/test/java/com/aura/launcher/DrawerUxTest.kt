package com.aura.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.AppShortcut
import com.aura.launcher.data.DrawerLayout
import com.aura.launcher.ui.AppDrawer
import com.aura.launcher.ui.AppMenuActions
import com.aura.launcher.ui.AuraBackdrop
import com.aura.launcher.ui.DrawerActions
import com.aura.launcher.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The friendlier app drawer: layouts, collapsing letter, press-and-hold menu, hiding, tips. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w412dp-h915dp-xhdpi")
class DrawerUxTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val labels = listOf(
        "Calculator", "Calendar", "Camera", "Chrome", "Clock", "Contacts", "Drive", "Files", "Gallery", "Gemini",
        "Gmail", "Google", "Google TV", "Home", "Keep Notes", "Maps", "Messages", "Music", "News",
        "Phone", "Photos", "Play Store", "Recorder", "Settings", "Spotify", "Translate", "Wallet", "Weather", "YouTube",
    )

    // Same package as the test app so Android's shortcut service can return shortcuts for "Maps".
    private fun app(label: String) = AppEntry(
        key = label, label = label, packageName = context.packageName, component = ComponentName(context.packageName, label),
        user = Process.myUserHandle(), subtitle = if (label == "Maps") "Navigate, explore, plan" else "",
        letter = AppRepository.letterOf(label), isWork = false,
    )

    private val apps by lazy { labels.map(::app) }
    private val noop = DrawerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private val layoutState = androidx.compose.runtime.mutableStateOf(DrawerLayout.LIST)

    private fun render(
        layout: DrawerLayout,
        showTip: Boolean = false,
        menu: AppMenuActions = AppMenuActions(),
        onTip: () -> Unit = {},
        launch: (AppEntry) -> Unit = {},
        list: List<AppEntry> = apps,
    ) {
        val repo = AppRepository(context)
        layoutState.value = layout
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop(drift = 1f)
                    AppDrawer(
                        apps = list, favorites = emptyList(), favoriteKeys = emptySet(), repo = repo,
                        dock = emptyList(), query = "", onQueryChange = {}, focusSearchToken = 0,
                        actions = noop.copy(launch = launch), layout = layoutState.value, menuActions = menu,
                        showTip = showTip, onTipDismiss = onTip,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
    }

    @Test
    fun gridShowsFourAcross() {
        render(DrawerLayout.GRID)
        save("drawer_grid")
        val calc = compose.onNodeWithText("Calculator").fetchSemanticsNode().boundsInRoot
        val chrome = compose.onNodeWithText("Chrome").fetchSemanticsNode().boundsInRoot
        val clock = compose.onNodeWithText("Clock").fetchSemanticsNode().boundsInRoot
        // Calculator, Calendar, Camera, Chrome on one row; Clock starts the next.
        assertTrue("same row: ${calc.top} vs ${chrome.top}", kotlin.math.abs(calc.top - chrome.top) < 1f)
        assertTrue("next row: clock ${clock.top} vs calculator ${calc.bottom}", clock.top > calc.top + 50f)
    }

    @Test
    fun compactFitsMoreAppsThanList() {
        fun visibleRows(): Int = labels.count { l ->
            compose.onAllNodes(androidx.compose.ui.test.hasText(l)).fetchSemanticsNodes()
                .any { it.boundsInRoot.top < it.boundsInRoot.bottom && it.boundsInRoot.bottom < 1700f }
        }
        render(DrawerLayout.LIST)
        val list = visibleRows()
        layoutState.value = DrawerLayout.COMPACT
        compose.mainClock.advanceTimeBy(500)
        val compact = visibleRows()
        assertTrue("compact shows $compact vs list $list", compact >= list + 2)
        save("drawer_compact")
    }

    @Test
    fun bigLetterShrinksWhileScrollingSoMoreAppsFit() {
        render(DrawerLayout.LIST)
        val before = compose.onNodeWithTag("big_letter").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("drawer_list").performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 300f) }
        compose.mainClock.advanceTimeBy(800)
        val after = compose.onNodeWithTag("big_letter").fetchSemanticsNode().boundsInRoot.height
        assertTrue("letter area $before → $after", after < before * 0.5f)
        save("drawer_scrolled")
    }

    @Test
    fun tipShowsOnceAndCanBeDismissed() {
        var dismissed = false
        render(DrawerLayout.LIST, showTip = true, onTip = { dismissed = true })
        save("drawer_tip")
        compose.onNodeWithText("Got it").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun holdingAnAppShowsItsShortcutsAndOptions() {
        val user = Process.myUserHandle()
        val shortcuts = listOf(
            AppShortcut("home", "Navigate home", context.packageName, user, null),
            AppShortcut("work", "Navigate to work", context.packageName, user, null),
        )
        var started: AppShortcut? = null
        var hidden: AppEntry? = null
        render(
            DrawerLayout.LIST,
            menu = AppMenuActions(
                launchShortcut = { started = it }, toggleHidden = { hidden = it },
                loadShortcuts = { if (it.label == "Maps") shortcuts else emptyList() },
            ),
            list = apps.filter { it.label in setOf("Maps", "Messages", "Music", "News", "Phone", "Photos") },
        )
        compose.onNodeWithText("Maps").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText("Navigate home").assertExists()
        compose.onNodeWithText("Navigate to work").assertExists()
        compose.onNodeWithText("Hide from app drawer").assertExists()
        compose.onNodeWithText("App info").assertExists()
        save("drawer_app_menu")

        compose.onNodeWithText("Navigate home").performClick()
        assertEquals("home", started?.id)

        compose.onNodeWithText("Maps").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Hide from app drawer").performClick()
        assertEquals("Maps", hidden?.label)
    }

    @Test
    fun shortcutsComeInTheAppsOwnOrder() {
        val ordered = AppRepository.orderShortcuts(
            listOf("c" to 2, "a" to 0, "b" to 1).map { (id, rank) ->
                ShortcutInfo.Builder(context, id).setShortLabel(id).setActivity(ComponentName(context.packageName, "Maps"))
                    .setIntent(Intent(Intent.ACTION_VIEW)).setRank(rank).build()
            }
        )
        assertEquals(listOf("a", "b", "c"), ordered.map { it.id })
    }

    @Test
    fun noShortcutsUntilAuraIsTheHomeApp() = kotlinx.coroutines.runBlocking {
        // Android only shares app shortcuts with the default home app.
        shadowOf(context.getSystemService(LauncherApps::class.java)).setHasShortcutHostPermission(false)
        assertTrue(AppRepository(context).shortcuts(app("Maps")).isEmpty())
    }

    @Test
    fun scrubbingStillJumpsInTheGrid() {
        render(DrawerLayout.GRID)
        val letters = apps.map { it.letter }.distinct()
        val target = letters.indexOf("S")
        compose.onNodeWithTag("alphabet").performTouchInput {
            val spacing = minOf(height.toFloat() / letters.size, 30.dp.toPx())
            val y = (height - spacing * letters.size) / 2f + spacing * (target + 0.5f)
            down(Offset(width * 0.6f, y))
            advanceEventTime(300)
            moveTo(Offset(width * 0.6f, y + 1f))
            advanceEventTime(300)
            up()
        }
        compose.mainClock.advanceTimeBy(1000)
        val spotify = compose.onNodeWithText("Spotify").fetchSemanticsNode().boundsInRoot
        assertTrue("Spotify should be on screen after jumping to S (top=${spotify.top})", spotify.top in 0f..1500f)
        save("drawer_grid_jump")
    }

    private fun save(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
