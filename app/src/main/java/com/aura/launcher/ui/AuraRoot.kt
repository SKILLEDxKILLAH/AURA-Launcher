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
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun AuraRoot(
    vm: MainViewModel,
    homePalette: com.aura.launcher.ui.theme.AuraPalette = Aura.colors,
    requestPermissions: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val panelPalette = Aura.colors
    val prefs = vm.prefs
    val apps by vm.apps.collectAsStateWithLifecycle()
    val counts by vm.launchCounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var drawer by remember { mutableFloatStateOf(0f) }
    /** News feed progress: 0 = closed, 1 = open (slides in from the left). */
    var feed by remember { mutableFloatStateOf(0f) }
    var feedJob by remember { mutableStateOf<Job?>(null) }
    /** Which panel the current horizontal drag drives; decided by the first movement. */
    var dragTarget by remember { mutableStateOf<String?>(null) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var focusToken by remember { mutableIntStateOf(0) }
    var homeMenuAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val view = androidx.compose.ui.platform.LocalView.current

    val favorites = remember(apps, prefs.favorites, counts, prefs.hiddenApps) {
        val visible = apps.filter { it.key !in prefs.hiddenApps }
        val pinned = visible.filter { it.key in prefs.favorites }
        val frequent = visible.filter { (counts[it.key] ?: 0) >= 3 && it.key !in prefs.favorites }
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
            val wasOpen = drawer >= 0.999f
            settleJob = scope.launch {
                animate(drawer, target, initialVelocity = velocity / widthPx, animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f)) { v, _ ->
                    drawer = v
                }
                // "Open keyboard with drawer": go straight to search when the drawer opens.
                if (target == 1f && !wasOpen && prefs.drawerKeyboard) focusToken++
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

        // Feed: positive dx (rightward) opens it.
        fun settleFeed(velocity: Float) {
            val target = when {
                velocity > 700f -> 1f
                velocity < -700f -> 0f
                feed > 0.4f -> 1f
                else -> 0f
            }
            feedJob?.cancel()
            feedJob = scope.launch {
                animate(feed, target, initialVelocity = velocity / widthPx, animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f)) { v, _ ->
                    feed = v
                }
            }
            if (target == 1f) {
                vm.loadNews()
                if (vm.weather == null) vm.refreshWeather()
            }
        }

        fun feedDragBy(dx: Float) {
            feedJob?.cancel()
            feed = (feed + dx / (widthPx * 0.85f)).coerceIn(0f, 1f)
        }

        fun closeFeed() = settleFeed(-3000f)

        /** Swipe left from home → app drawer (from the right); swipe right → news feed (from the left). */
        fun routeDrag(dx: Float) {
            val target = dragTarget ?: when {
                drawer > 0f -> "drawer"
                feed > 0f -> "feed"
                dx < 0f -> "drawer"
                dx > 0f -> "feed"
                else -> return
            }.also { dragTarget = it }
            if (target == "drawer") dragBy(-dx) else feedDragBy(dx)
        }

        fun routeStop(velocity: Float) {
            when (dragTarget) {
                "drawer" -> settle(-velocity)
                "feed" -> settleFeed(velocity)
            }
            dragTarget = null
        }

        fun openDrawer(focusSearch: Boolean = false) {
            settle(3000f)
            if (focusSearch) focusToken++
        }

        fun closeDrawer() = settle(-3000f)

        LaunchedEffect(Unit) {
            vm.resetToHome.collect {
                settleJob?.cancel()
                feedJob?.cancel()
                drawer = 0f
                feed = 0f
                query = ""
                homeMenuAt = null
                focusManager.clearFocus()
            }
        }

        BackHandler(enabled = drawer > 0f && !vm.geminiSheetOpen && !vm.settingsOpen) {
            if (query.isNotEmpty()) query = "" else closeDrawer()
        }
        BackHandler(enabled = homeMenuAt != null) { homeMenuAt = null }
        BackHandler(enabled = feed > 0f && !vm.geminiSheetOpen && !vm.settingsOpen) { closeFeed() }
        BackHandler(enabled = vm.geminiSheetOpen) { vm.geminiSheetOpen = false }
        BackHandler(enabled = vm.settingsOpen && !vm.diagnosticsOpen) { vm.settingsOpen = false }
        BackHandler(enabled = vm.diagnosticsOpen) { vm.diagnosticsOpen = false }
        BackHandler(enabled = vm.widgetPickerOpen) { vm.widgetPickerOpen = false }
        BackHandler(enabled = vm.gestureDisclosureOpen) { vm.declineGestureDisclosure() }
        BackHandler(enabled = !prefs.welcomeDone) { prefs.markWelcomeDone() }

        val dragState = rememberDraggableState { routeDrag(it) }

        // Status bar icons: dark on light screens. Panels use the theme; home uses the wallpaper's.
        val window = (context as? android.app.Activity)?.window
        val panelUp = vm.settingsOpen || vm.diagnosticsOpen || vm.widgetPickerOpen || feed > 0.5f
        val lightBars = if (panelUp) panelPalette.isLight else homePalette.isLight
        androidx.compose.runtime.SideEffect {
            window?.let { w ->
                val c = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
                c.isAppearanceLightStatusBars = lightBars
                c.isAppearanceLightNavigationBars = lightBars
            }
        }

        // ---- Background --------------------------------------------------------------------
        com.aura.launcher.ui.theme.ProvidePalette(homePalette) {
        if (prefs.useAuraBackdrop) {
            AuraBackdrop(drift = drawer)
        } else {
            // Keep text legible over any system wallpaper.
            val wallScrim = if (Aura.colors.isLight) Color.White else Color.Black
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to wallScrim.copy(alpha = 0.4f), 0.35f to wallScrim.copy(alpha = 0.1f),
                        0.6f to wallScrim.copy(alpha = 0.1f), 1f to wallScrim.copy(alpha = 0.5f),
                    )
                )
            )
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = maxOf(drawer, feed) }.background(Aura.colors.veil))
        }

        // Press-and-hold menu on any app (drawer, suggestions, search, dock).
        val menuActions = AppMenuActions(
            launchShortcut = vm::launchShortcut,
            toggleFavorite = { prefs.toggleFavorite(it.key) },
            toggleHidden = { prefs.toggleHidden(it.key) },
            appInfo = vm.repo::openAppInfo,
            uninstall = vm.repo::uninstall,
            isFavorite = { it.key in prefs.favorites },
            isHidden = { it.key in prefs.hiddenApps },
            toggleDock = vm::toggleDock,
            isInDock = vm::isInDock,
        )
        val dockEntry = { item: com.aura.launcher.data.DockItem ->
            item.entryKey?.let { key -> apps.firstOrNull { it.key == key } } ?: apps.firstOrNull { it.packageName == item.packageName && !it.isWork }
        }

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
        if (drawer < 0.999f && feed < 0.999f) com.aura.launcher.ui.theme.ProvidePalette(homePalette) {
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
                dockEntry = dockEntry,
                menuActions = menuActions,
                widgets = vm.widgets,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = (1f - maxOf(drawer, feed) * 2.2f).coerceIn(0f, 1f)
                        translationX = (feed - drawer) * widthPx * 0.25f
                        val s = 1f - 0.06f * drawer
                        scaleX = s; scaleY = s
                    }
                    // Lets a swipe that starts right at the right edge reach AURA instead of
                    // the system back gesture (Android caps this at 200dp per edge).
                    .rightEdgeGestureZone()
                    .leftEdgeGestureZone()
                    // Press and hold → menu; swipe down with 1 finger → notifications, 2 → Quick Settings.
                    .homeGestures(
                        onLongPress = { pos ->
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            homeMenuAt = pos
                        },
                        onSwipeDown = { fingers -> vm.pullDown(fingers) },
                    )
                    .draggable(dragState, Orientation.Horizontal, onDragStarted = { dragTarget = null }, onDragStopped = { v -> routeStop(v) }),
            )
        }

        // ---- News feed (from the left) -----------------------------------------------------
        if (feed > 0.001f) {
            FeedScreen(
                state = FeedState(
                    topic = vm.newsTopic,
                    stories = vm.news[vm.newsTopic].orEmpty(),
                    loading = vm.newsLoading,
                    error = vm.newsError,
                    weather = vm.weather,
                    weatherNeedsPermission = vm.weatherNeedsPermission,
                ),
                actions = FeedActions(
                    selectTopic = { vm.loadNews(it) },
                    refresh = { vm.loadNews(force = true); vm.refreshWeather() },
                    openStory = vm::openStory,
                    briefMe = vm::briefMe,
                    onWeather = {
                        if (vm.weatherNeedsPermission) requestPermissions(listOf(Manifest.permission.ACCESS_COARSE_LOCATION))
                        else vm.openWeatherApp()
                    },
                ),
                modifier = Modifier
                    .graphicsLayer {
                        alpha = (feed * 1.5f).coerceIn(0f, 1f)
                        translationX = -(1f - feed) * widthPx
                    }
                    .draggable(dragState, Orientation.Horizontal, onDragStarted = { dragTarget = null }, onDragStopped = { v -> routeStop(v) }),
            )
        }

        // ---- App drawer --------------------------------------------------------------------
        if (drawer > 0.001f) com.aura.launcher.ui.theme.ProvidePalette(homePalette) {
            val hidden = prefs.hiddenApps
            AppDrawer(
                apps = if (hidden.isEmpty()) apps else apps.filter { it.key !in hidden },
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
                suggested = vm.suggested.filter { it.key !in hidden },
                recent = vm.recent.filter { it.key !in hidden },
                showGeminiBar = prefs.drawerGeminiBar,
                showGeminiButton = prefs.drawerGeminiButton,
                layout = prefs.drawerLayout,
                menuActions = menuActions,
                showTip = !prefs.drawerTipSeen,
                onTipDismiss = prefs::markDrawerTipSeen,
                dockEntry = dockEntry,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = (drawer * 1.5f).coerceIn(0f, 1f)
                        translationX = (1f - drawer) * widthPx
                    }
                    .draggable(dragState, Orientation.Horizontal, onDragStarted = { dragTarget = null }, onDragStopped = { v -> routeStop(v) }),
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
                    needsSignIn = !prefs.geminiReady || vm.geminiSetupOpen,
                    signingIn = vm.signingIn,
                    signInError = vm.signInError,
                    accountLabel = when (prefs.authMode) {
                        com.aura.launcher.data.GeminiAuthMode.API_KEY -> "Your Gemini key"
                        com.aura.launcher.data.GeminiAuthMode.ON_DEVICE -> "On this phone"
                        else -> prefs.accountEmail
                    },
                    model = prefs.model,
                    listenRequest = vm.listenRequest,
                    models = vm.models,
                    modelsLoading = vm.modelsLoading,
                    modelsError = vm.modelsError,
                    thinking = prefs.thinking,
                    setup = GeminiSetupState(
                        nanoStatus = vm.nanoStatus, nanoWorking = vm.nanoWorking, nanoError = vm.nanoError,
                        keyChecking = vm.keyChecking, keyError = vm.keyError,
                        signingIn = vm.signingIn, signInError = vm.signInError,
                    ),
                    onDeviceName = vm.nanoName.takeIf { prefs.authMode == com.aura.launcher.data.GeminiAuthMode.ON_DEVICE },
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
                onReport = vm::reportReply,
                setupActions = GeminiSetupActions(
                    useNano = vm::useOnDevice, openKeyPage = vm::openKeyPage, pasteKey = vm::pasteApiKey,
                    signIn = vm::signIn, openSettings = { vm.geminiSheetOpen = false; vm.settingsOpen = true },
                ),
                onChangeSetup = { vm.geminiSetupOpen = true; vm.checkNano() },
            )
        }

        homeMenuAt?.let { anchor ->
            HomeMenu(
                anchor = anchor,
                usingSystemWallpaper = !prefs.useAuraBackdrop,
                onWallpaper = { homeMenuAt = null; vm.openWallpaperPicker() },
                onUseAuraBackdrop = { homeMenuAt = null; prefs.updateUseAuraBackdrop(true) },
                onWidgets = { homeMenuAt = null; vm.widgetPickerOpen = true },
                onSettings = { homeMenuAt = null; vm.settingsOpen = true },
                onDismiss = { homeMenuAt = null },
            )
        }

        AnimatedVisibility(vm.gestureSetupNeeded, enter = fadeIn(), exit = fadeOut()) {
            GestureSetupBanner(onEnable = vm::enableGestureService, onAppInfo = vm::openAuraAppInfo, onDismiss = vm::dismissGestureHint)
        }

        AnimatedVisibility(vm.widgetPickerOpen, enter = fadeIn() + slideInVertically { it / 4 }, exit = fadeOut()) {
            WidgetPicker(vm.widgets, vm.repo, onPick = vm::addWidget, onClose = { vm.widgetPickerOpen = false })
        }

        AnimatedVisibility(vm.gestureDisclosureOpen, enter = fadeIn(), exit = fadeOut()) {
            GestureDisclosure(onAgree = vm::acceptGestureDisclosure, onDecline = vm::declineGestureDisclosure)
        }

        AnimatedVisibility(vm.diagnosticsOpen, enter = fadeIn(), exit = fadeOut()) {
            DiagnosticsSheet(vm.diagnostics, vm.diagnosticsRunning, onRunAgain = vm::runDiagnostics) { vm.diagnosticsOpen = false }
        }

        // First launch. People who already set AURA up (home app + Gemini) don't need it.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (!prefs.welcomeDone && vm.isDefaultHome && prefs.geminiReady) prefs.markWelcomeDone()
        }
        AnimatedVisibility(!prefs.welcomeDone, enter = fadeIn(), exit = fadeOut()) {
            WelcomeScreen(
                prefs = prefs,
                isDefaultHome = vm.isDefaultHome,
                onSetHome = vm::requestDefaultHome,
                onSetUpGemini = { vm.openGemini(); vm.geminiSetupOpen = !prefs.geminiReady; vm.checkNano() },
                onFinish = prefs::markWelcomeDone,
            )
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
                apps = apps,
                onAddWidget = { vm.settingsOpen = false; vm.widgetPickerOpen = true },
                onEnableGestures = { vm.settingsOpen = false; vm.enableGestureService() },
                onResetDock = vm::resetDock,
                onChangeGemini = { vm.settingsOpen = false; vm.openGemini(); vm.geminiSetupOpen = true; vm.checkNano() },
            ) { vm.settingsOpen = false }
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
