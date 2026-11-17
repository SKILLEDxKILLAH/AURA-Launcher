package com.aura.launcher.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.launcher.ActivityBridge
import com.aura.launcher.MainViewModel
import com.aura.launcher.data.DockResolver
import com.aura.launcher.data.SettingShortcuts
import com.aura.launcher.gemini.GeminiBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun AuraRoot(vm: MainViewModel, requestPermissions: (List<String>) -> Unit) {
    val context = LocalContext.current
    val prefs = vm.prefs
    val apps by vm.apps.collectAsStateWithLifecycle()
    val counts by vm.launchCounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var drawer by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var focusToken by remember { mutableIntStateOf(0) }

    val favorites = remember(apps, prefs.favorites, counts) {
        val pinned = apps.filter { it.key in prefs.favorites }
        val frequent = apps.filter { (counts[it.key] ?: 0) >= 3 && it.key !in prefs.favorites }
            .sortedByDescending { counts[it.key] ?: 0 }
        (pinned + frequent).take(8)
    }

    LaunchedEffect(prefs.fahrenheit, prefs.showWeather) { if (prefs.showWeather) vm.refreshWeather() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)

        // velocity > 0 means a rightward fling (opening), < 0 leftward (closing).
        fun settle(velocity: Float) {
            val target = when {
                velocity > 700f -> 1f
                velocity < -700f -> 0f
                drawer > 0.4f -> 1f
                else -> 0f
            }
            settleJob?.cancel()
            settleJob = scope.launch {
                animate(drawer, target, initialVelocity = velocity / widthPx, animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f)) { v, _ ->
                    drawer = v
                }
                if (target == 0f) {
                    query = ""
                    focusManager.clearFocus()
                }
            }
        }

        fun dragBy(dx: Float) {
            settleJob?.cancel()
            drawer = (drawer + dx / (widthPx * 0.85f)).coerceIn(0f, 1f)
        }

        fun openDrawer(focusSearch: Boolean = false) {
            settle(3000f)
            if (focusSearch) focusToken++
        }

        fun closeDrawer() = settle(-3000f)

        LaunchedEffect(Unit) {
            vm.resetToHome.collect {
                settleJob?.cancel()
                drawer = 0f
                query = ""
                focusManager.clearFocus()
            }
        }

        BackHandler(enabled = drawer > 0f && !vm.geminiSheetOpen && !vm.settingsOpen) {
            if (query.isNotEmpty()) query = "" else closeDrawer()
        }
        BackHandler(enabled = vm.geminiSheetOpen) { vm.geminiSheetOpen = false }
        BackHandler(enabled = vm.settingsOpen) { vm.settingsOpen = false }

        val dragState = rememberDraggableState { dragBy(it) }

        // ---- Background --------------------------------------------------------------------
        if (prefs.useAuraBackdrop) AuraBackdrop(drift = drawer)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = drawer }.background(Color(0x59000000)))

        // ---- Home --------------------------------------------------------------------------
        val openGoogle = {
            val launch = context.packageManager.getLaunchIntentForPackage(GeminiBridge.GOOGLE_PACKAGE)
            if (launch == null || !ActivityBridge.start(context, launch)) openDrawer(focusSearch = true)
        }
        if (drawer < 0.999f) {
            HomeScreen(
                repo = vm.repo,
                dock = vm.dock,
                weather = vm.weather,
                showWeather = prefs.showWeather,
                weatherNeedsPermission = vm.weatherNeedsPermission,
                actions = HomeActions(
                    openGeminiSheet = { vm.geminiSheetOpen = true },
                    openGeminiApp = { vm.openGeminiApp() },
                    geminiVoice = { vm.geminiVoice() },
                    openClock = { ActivityBridge.start(context, DockResolver.clockIntent()) },
                    openCalendar = {
                        val uri = ContentUris.appendId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), System.currentTimeMillis()).build()
                        ActivityBridge.start(context, Intent(Intent.ACTION_VIEW, uri))
                    },
                    onWeather = {
                        if (vm.weatherNeedsPermission) requestPermissions(listOf(Manifest.permission.ACCESS_COARSE_LOCATION))
                        else ActivityBridge.start(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, "weather"))
                    },
                    openGoogle = openGoogle,
                    openSettings = { vm.settingsOpen = true },
                    launchDock = vm::launchDock,
                ),
                modifier = Modifier
                    .graphicsLayer {
                        alpha = (1f - drawer * 2.2f).coerceIn(0f, 1f)
                        translationX = drawer * widthPx * 0.25f
                        val s = 1f - 0.06f * drawer
                        scaleX = s; scaleY = s
                    }
                    // Lets a swipe that starts right at the left edge reach AURA instead of
                    // the system back gesture (Android caps this at 200dp per edge).
                    .leftEdgeGestureZone()
                    .draggable(dragState, Orientation.Horizontal, onDragStopped = { v -> settle(v) }),
            )
        }

        // ---- App drawer --------------------------------------------------------------------
        if (drawer > 0.001f) {
            AppDrawer(
                apps = apps,
                favorites = favorites,
                favoriteKeys = prefs.favorites,
                repo = vm.repo,
                dock = vm.dock,
                query = query,
                onQueryChange = { query = it },
                focusSearchToken = focusToken,
                actions = DrawerActions(
                    launch = vm::launch,
                    appInfo = vm.repo::openAppInfo,
                    uninstall = vm.repo::uninstall,
                    toggleFavorite = { prefs.toggleFavorite(it.key) },
                    openSetting = { ActivityBridge.start(context, SettingShortcuts.intentFor(it)) },
                    askGemini = { vm.ask(it) },
                    webSearch = { q ->
                        ActivityBridge.start(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q))
                    },
                    openGeminiSheet = { vm.geminiSheetOpen = true },
                    openGeminiApp = { vm.openGeminiApp() },
                    geminiVoice = { vm.geminiVoice() },
                    openGoogle = openGoogle,
                    openSettings = { vm.settingsOpen = true },
                    launchDock = vm::launchDock,
                ),
                modifier = Modifier
                    .graphicsLayer {
                        alpha = (drawer * 1.5f).coerceIn(0f, 1f)
                        translationX = -(1f - drawer) * widthPx
                    }
                    .draggable(dragState, Orientation.Horizontal, onDragStopped = { v -> settle(v) }),
            )
        }

        // ---- Overlays ----------------------------------------------------------------------
        AnimatedVisibility(
            vm.geminiSheetOpen,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            GeminiSheet(
                chat = vm.chat,
                busy = vm.agentBusy,
                agentReady = prefs.agentReady,
                model = prefs.model,
                onSend = { vm.ask(it) },
                onVoice = { vm.geminiVoice() },
                onOpenGeminiApp = { vm.openGeminiApp() },
                onClear = { vm.clearChat() },
                onSetup = { vm.geminiSheetOpen = false; vm.settingsOpen = true },
                onDismiss = { vm.geminiSheetOpen = false },
            )
        }

        AnimatedVisibility(vm.settingsOpen, enter = fadeIn(), exit = fadeOut()) {
            SettingsScreen(prefs, requestPermissions) { vm.settingsOpen = false }
        }
    }
}

/** Excludes a 200dp band on the left edge, centered vertically, from the system back gesture. */
@Composable
private fun Modifier.leftEdgeGestureZone(): Modifier {
    val density = LocalDensity.current
    val band = with(density) { 200.dp.toPx() }
    val edge = with(density) { 32.dp.toPx() }
    return systemGestureExclusion { coords ->
        val h = coords.size.height.toFloat()
        val top = ((h - band) / 2f).coerceAtLeast(0f)
        Rect(0f, top, edge, top + band)
    }
}
