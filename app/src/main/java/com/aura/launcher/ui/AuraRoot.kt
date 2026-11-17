package com.aura.launcher.ui

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.launcher.ActivityBridge
import com.aura.launcher.MainViewModel
import com.aura.launcher.data.DockResolver
import com.aura.launcher.data.SettingShortcut
import com.aura.launcher.data.SettingShortcuts
import com.aura.launcher.gemini.GeminiBridge
import com.aura.launcher.ui.theme.Aura
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
    var settingsPicker by remember { mutableStateOf(false) }

    val favorites = remember(apps, prefs.favorites, counts) {
        val pinned = apps.filter { it.key in prefs.favorites }
        val frequent = apps.filter { (counts[it.key] ?: 0) >= 3 && it.key !in prefs.favorites }
            .sortedByDescending { counts[it.key] ?: 0 }
        (pinned + frequent).take(8)
    }

    LaunchedEffect(prefs.fahrenheit, prefs.showWeather) { if (prefs.showWeather) vm.refreshWeather() }

    // Contact picker shared by "Call a contact" and "Send a message".
    var pickForMessage by remember { mutableStateOf(false) }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        val number = context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@rememberLauncherForActivityResult
        val intent = if (pickForMessage) {
            Intent(Intent.ACTION_SENDTO, "smsto:${Uri.encode(number)}".toUri())
        } else {
            val canCall = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, "tel:${Uri.encode(number)}".toUri())
        }
        ActivityBridge.start(context, intent)
    }
    fun pickContact(forMessage: Boolean) {
        pickForMessage = forMessage
        runCatching {
            contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        fun settle(velocity: Float) {
            val target = when {
                velocity < -900f -> 1f
                velocity > 900f -> 0f
                drawer > 0.5f -> 1f
                else -> 0f
            }
            settleJob?.cancel()
            settleJob = scope.launch {
                animate(drawer, target, initialVelocity = -velocity / heightPx, animationSpec = spring(dampingRatio = 0.86f, stiffness = 360f)) { v, _ ->
                    drawer = v
                }
                if (target == 0f) {
                    query = ""
                    focusManager.clearFocus()
                }
            }
        }

        fun dragBy(dy: Float) {
            settleJob?.cancel()
            drawer = (drawer - dy / (heightPx * 0.55f)).coerceIn(0f, 1f)
        }

        fun openDrawer(focusSearch: Boolean = false) {
            settle(-3000f)
            if (focusSearch) focusToken++
        }

        fun closeDrawer() = settle(3000f)

        LaunchedEffect(Unit) {
            vm.resetToHome.collect {
                settleJob?.cancel()
                drawer = 0f
                query = ""
                settingsPicker = false
                focusManager.clearFocus()
            }
        }

        BackHandler(enabled = settingsPicker) { settingsPicker = false }
        BackHandler(enabled = drawer > 0f && !vm.geminiSheetOpen && !vm.settingsOpen && !settingsPicker) {
            if (query.isNotEmpty()) query = "" else closeDrawer()
        }
        BackHandler(enabled = vm.geminiSheetOpen) { vm.geminiSheetOpen = false }
        BackHandler(enabled = vm.settingsOpen) { vm.settingsOpen = false }

        // Drawer pull-down-to-close: the list hands leftover downward scroll to the drawer.
        val drawerNestedScroll = remember(heightPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (drawer < 1f && available.y < 0f && source == NestedScrollSource.UserInput) {
                        dragBy(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0f && source == NestedScrollSource.UserInput) {
                        dragBy(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (drawer < 1f) {
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }
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
                    callContact = { pickContact(forMessage = false) },
                    sendMessage = { pickContact(forMessage = true) },
                    changeSetting = { settingsPicker = true },
                    openAppSearch = { openDrawer(focusSearch = true) },
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
                        alpha = (1f - drawer * 1.6f).coerceIn(0f, 1f)
                        translationY = -drawer * 160f
                        val s = 1f - 0.05f * drawer
                        scaleX = s; scaleY = s
                    }
                    .draggable(dragState, Orientation.Vertical, onDragStopped = { v -> settle(v) }),
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
                nestedScrollConnection = drawerNestedScroll,
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
                        alpha = drawer
                        translationY = (1f - drawer) * heightPx * 0.16f
                    }
                    .draggable(dragState, Orientation.Vertical, onDragStopped = { v -> settle(v) }),
            )
        }

        // ---- Overlays ----------------------------------------------------------------------
        AnimatedVisibility(settingsPicker, enter = fadeIn(), exit = fadeOut()) {
            SettingsPicker(
                onPick = { settingsPicker = false; ActivityBridge.start(context, SettingShortcuts.intentFor(it)) },
                onDismiss = { settingsPicker = false },
            )
        }

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

@Composable
private fun SettingsPicker(onPick: (SettingShortcut) -> Unit, onDismiss: () -> Unit) {
    val ids = listOf("internet", "bluetooth", "volume", "display", "battery", "dnd", "nfc", "location", "wallpaper", "all")
    val shortcuts = ids.mapNotNull { SettingShortcuts.byId(it) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color(0x99100E0C))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(16.dp).glass(RoundedCornerShape(32.dp), fill = Color(0xEB2A2724)).padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuraGlyph(Glyph.Gear, size = 22.dp)
                Spacer(Modifier.width(12.dp))
                Text("Change a setting", color = Aura.Ink, fontSize = 19.sp, fontFamily = Aura.Sans)
            }
            Spacer(Modifier.height(14.dp))
            shortcuts.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    pair.forEach { s ->
                        Text(
                            s.title, color = Aura.Ink, fontSize = 15.sp, fontFamily = Aura.Sans, maxLines = 1,
                            modifier = Modifier.weight(1f).glass(RoundedCornerShape(22.dp)).clickable { onPick(s) }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
