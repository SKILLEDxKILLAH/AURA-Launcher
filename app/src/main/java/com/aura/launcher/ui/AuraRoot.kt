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
    var homeMenuAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val view = androidx.compose.ui.platform.LocalView.current

    val favorites = remember(apps, prefs.favorites, counts) {
        val pinned = apps.filter { it.key in prefs.favorites }
        val frequent = apps.filter { (counts[it.key] ?: 0) >= 3 && it.key !in prefs.favorites }
            .sortedByDescending { counts[it.key] ?: 0 }
        (pinned + frequent).take(8)
    }

    LaunchedEffect(prefs.fahrenheit, prefs.showWeather, prefs.topLineMode) {
        if (prefs.showWeather || prefs.topLineMode == com.aura.launcher.data.TopLineMode.WEATHER) vm.refreshWeather()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)

        // Internally, positive = toward open. The drawer opens with a leftward swipe from the
        // right edge, so raw horizontal deltas/velocities are negated where gestures feed in.
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
                homeMenuAt = null
                focusManager.clearFocus()
            }
        }

        BackHandler(enabled = drawer > 0f && !vm.geminiSheetOpen && !vm.settingsOpen) {
            if (query.isNotEmpty()) query = "" else closeDrawer()
        }
        BackHandler(enabled = homeMenuAt != null) { homeMenuAt = null }
        BackHandler(enabled = vm.geminiSheetOpen) { vm.geminiSheetOpen = false }
        BackHandler(enabled = vm.settingsOpen && !vm.diagnosticsOpen) { vm.settingsOpen = false }
        BackHandler(enabled = vm.diagnosticsOpen) { vm.diagnosticsOpen = false }

        val dragState = rememberDraggableState { dragBy(-it) }

        // ---- Background --------------------------------------------------------------------
        if (prefs.useAuraBackdrop) {
            AuraBackdrop(drift = drawer)
        } else {
            // Keep white text legible over any system wallpaper.
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color(0x66000000), 0.35f to Color(0x1A000000), 0.6f to Color(0x1A000000), 1f to Color(0x80000000),
                    )
                )
            )
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = drawer }.background(Color(0x59000000)))

        // ---- Home --------------------------------------------------------------------------
        val openGoogle = {
            val launch = context.packageManager.getLaunchIntentForPackage(com.aura.launcher.data.DockResolver.GOOGLE_PACKAGE)
            if (launch == null || !ActivityBridge.start(context, launch)) openDrawer(focusSearch = true)
        }
        val topLine = TopLineState(
            mode = prefs.topLineMode,
            label = prefs.topLabel,
            weather = vm.weather,
            weatherNeedsPermission = vm.weatherNeedsPermission,
            onLabelChange = prefs::updateTopLabel,
            onWeather = {
                if (vm.weatherNeedsPermission) requestPermissions(listOf(Manifest.permission.ACCESS_COARSE_LOCATION))
                else vm.openWeatherApp()
            },
            onLongPress = { vm.settingsOpen = true },
        )
        if (drawer < 0.999f) {
            HomeScreen(
                repo = vm.repo,
                dock = vm.dock,
                weather = vm.weather,
                showWeather = prefs.showWeather && prefs.topLineMode != com.aura.launcher.data.TopLineMode.WEATHER,
                weatherNeedsPermission = vm.weatherNeedsPermission,
                actions = HomeActions(
                    openGeminiSheet = { vm.openGemini() },
                    geminiLongPress = { vm.openGemini(listen = true) },
                    geminiVoice = { vm.openGemini(listen = true) },
                    openClock = { ActivityBridge.start(context, DockResolver.clockIntent()) },
                    openCalendar = {
                        val uri = ContentUris.appendId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), System.currentTimeMillis()).build()
                        ActivityBridge.start(context, Intent(Intent.ACTION_VIEW, uri))
                    },
                    onWeather = {
                        if (vm.weatherNeedsPermission) requestPermissions(listOf(Manifest.permission.ACCESS_COARSE_LOCATION))
                        else vm.openWeatherApp()
                    },
                    openGoogle = openGoogle,
                    openSettings = { vm.settingsOpen = true },
                    launchDock = vm::launchDock,
                ),
                topLine = topLine,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = (1f - drawer * 2.2f).coerceIn(0f, 1f)
                        translationX = -drawer * widthPx * 0.25f
                        val s = 1f - 0.06f * drawer
                        scaleX = s; scaleY = s
                    }
                    // Lets a swipe that starts right at the right edge reach AURA instead of
                    // the system back gesture (Android caps this at 200dp per edge).
                    .rightEdgeGestureZone()
                    // Press and hold → menu; swipe down with 1 finger → notifications, 2 → Quick Settings.
                    .homeGestures(
                        onLongPress = { pos ->
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            homeMenuAt = pos
                        },
                        onSwipeDown = { fingers -> vm.pullDown(fingers) },
                    )
                    .draggable(dragState, Orientation.Horizontal, onDragStopped = { v -> settle(-v) }),
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
                    openGeminiSheet = { vm.openGemini() },
                    geminiLongPress = { vm.openGemini(listen = true) },
                    geminiVoice = { vm.openGemini(listen = true) },
                    openGoogle = openGoogle,
                    openSettings = { vm.settingsOpen = true },
                    launchDock = vm::launchDock,
                ),
                topLine = topLine,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = (drawer * 1.5f).coerceIn(0f, 1f)
                        translationX = (1f - drawer) * widthPx
                    }
                    .draggable(dragState, Orientation.Horizontal, onDragStopped = { v -> settle(-v) }),
            )
        }

        // ---- Overlays ----------------------------------------------------------------------
        AnimatedVisibility(
            vm.geminiSheetOpen,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            GeminiSheet(
                state = GeminiSheetState(
                    chat = vm.chat,
                    busy = vm.agentBusy,
                    needsSignIn = !prefs.geminiReady,
                    signingIn = vm.signingIn,
                    signInError = vm.signInError,
                    accountLabel = if (prefs.authMode == com.aura.launcher.data.GeminiAuthMode.API_KEY) "API key" else prefs.accountEmail,
                    model = prefs.model,
                    listenRequest = vm.listenRequest,
                    models = vm.models,
                    modelsLoading = vm.modelsLoading,
                    modelsError = vm.modelsError,
                    thinking = prefs.thinking,
                ),
                onSend = { vm.ask(it) },
                onSignIn = { vm.signIn() },
                onClear = { vm.clearChat() },
                onOpenSettings = { vm.geminiSheetOpen = false; vm.settingsOpen = true },
                onDismiss = { vm.geminiSheetOpen = false },
                onLoadModels = { vm.loadModels() },
                onSelectModel = vm::selectModel,
                onRunCheck = vm::runDiagnostics,
                onSelectThinking = vm::selectThinking,
            )
        }

        homeMenuAt?.let { anchor ->
            HomeMenu(
                anchor = anchor,
                usingSystemWallpaper = !prefs.useAuraBackdrop,
                onWallpaper = { homeMenuAt = null; vm.openWallpaperPicker() },
                onUseAuraBackdrop = { homeMenuAt = null; prefs.updateUseAuraBackdrop(true) },
                onSettings = { homeMenuAt = null; vm.settingsOpen = true },
                onDismiss = { homeMenuAt = null },
            )
        }

        AnimatedVisibility(vm.gestureSetupNeeded, enter = fadeIn(), exit = fadeOut()) {
            GestureSetupBanner(onEnable = vm::enableGestureService, onAppInfo = vm::openAuraAppInfo, onDismiss = vm::dismissGestureHint)
        }

        AnimatedVisibility(vm.diagnosticsOpen, enter = fadeIn(), exit = fadeOut()) {
            DiagnosticsSheet(vm.diagnostics, vm.diagnosticsRunning, onRunAgain = vm::runDiagnostics) { vm.diagnosticsOpen = false }
        }

        AnimatedVisibility(vm.settingsOpen, enter = fadeIn(), exit = fadeOut()) {
            SettingsScreen(
                prefs = prefs,
                signingIn = vm.signingIn,
                signInError = vm.signInError,
                onSignIn = { vm.signIn() },
                onSignOut = { vm.signOut() },
                onRunCheck = vm::runDiagnostics,
                requestPermissions = requestPermissions,
            ) { vm.settingsOpen = false }
        }
    }
}

/** Excludes a 200dp band on the right edge, centered vertically, from the system back gesture. */
@Composable
private fun Modifier.rightEdgeGestureZone(): Modifier {
    val density = LocalDensity.current
    val band = with(density) { 200.dp.toPx() }
    val edge = with(density) { 32.dp.toPx() }
    return systemGestureExclusion { coords ->
        val h = coords.size.height.toFloat()
        val top = ((h - band) / 2f).coerceAtLeast(0f)
        val w = coords.size.width.toFloat()
        Rect(w - edge, top, w, top + band)
    }
}
