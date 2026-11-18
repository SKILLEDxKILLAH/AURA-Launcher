package com.aura.launcher

import android.content.Intent
import android.content.IntentSender
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.aura.launcher.ui.AuraRoot
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import com.aura.launcher.data.IconTheme
import com.aura.launcher.ui.theme.AuraPalette
import com.aura.launcher.ui.theme.AuraTheme
import com.aura.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermission?.complete(granted)
            pendingPermission = null
        }
    private var pendingIntentSender: CompletableDeferred<Intent?>? = null
    private val intentSenderLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            pendingIntentSender?.complete(if (result.resultCode == RESULT_OK) result.data else null)
            pendingIntentSender = null
        }
    private var pendingBind: CompletableDeferred<Boolean>? = null
    private val bindLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingBind?.complete(result.resultCode == RESULT_OK)
            pendingBind = null
        }
    private var pendingConfigure: CompletableDeferred<Boolean>? = null
    private val multiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.onResumed()
            vm.refreshWeather()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ActivityBridge.attach(this)
        com.aura.launcher.ui.NetImages.installDiskCache(this)
        setContent {
            val prefs = vm.prefs
            val systemDark = isSystemInDarkTheme()
            // Panels follow the chosen theme; the home screen and drawer sit on the wallpaper, so
            // with a system wallpaper their text follows the wallpaper (dark text on light ones).
            val palette = remember(prefs.themeMode, prefs.colorSource, systemDark) {
                AuraPalette.resolve(this, prefs.themeMode, prefs.colorSource, systemDark)
            }
            val homePalette = remember(palette, prefs.useAuraBackdrop, vm.wallpaperIsLight, prefs.colorSource, systemDark) {
                if (prefs.useAuraBackdrop) palette
                else AuraPalette.resolve(this, if (vm.wallpaperIsLight) ThemeMode.LIGHT else ThemeMode.DARK, prefs.colorSource, systemDark)
            }
            LaunchedEffect(prefs.themedIcons, palette) {
                vm.repo.setIconTheme(
                    if (prefs.themedIcons) IconTheme(palette.iconBackground.toArgb(), palette.iconForeground.toArgb()) else null
                )
            }
            AuraTheme(palette) {
                AuraRoot(vm = vm, homePalette = homePalette, requestPermissions = { multiPermissionLauncher.launch(it.toTypedArray()) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) vm.onHomeIntent()
    }

    override fun onResume() {
        super.onResume()
        ActivityBridge.attach(this)
        vm.onResumed()
    }

    override fun onStart() {
        super.onStart()
        vm.widgets.startListening()
    }

    override fun onStop() {
        super.onStop()
        vm.widgets.stopListening()
        vm.onStopped()
    }

    /**
     * Puts a widget on the home screen: binds it (asking "Allow AURA to create widgets?" the
     * first time), opens the widget's own setup screen if it has one, then places it.
     * Returns false if the user cancelled along the way.
     */
    suspend fun addWidget(info: android.appwidget.AppWidgetProviderInfo): Boolean {
        val widgets = vm.widgets
        val id = widgets.allocateId()
        if (!widgets.bindIfAllowed(id, info)) {
            pendingBind?.complete(false)
            val bind = CompletableDeferred<Boolean>().also { pendingBind = it }
            bindLauncher.launch(
                Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            )
            if (!bind.await()) { widgets.discard(id); return false }
        }
        if (widgets.needsConfiguration(info)) {
            pendingConfigure?.complete(false)
            val configured = CompletableDeferred<Boolean>().also { pendingConfigure = it }
            val started = runCatching {
                widgets.host.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE_WIDGET, null)
            }.isSuccess
            if (!started || !configured.await()) { widgets.discard(id); return false }
        }
        widgets.add(id, widgets.defaultHeightDp(info))
        return true
    }

    @Deprecated("Widget setup screens report back here; there is no ActivityResult API for them.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CONFIGURE_WIDGET) {
            pendingConfigure?.complete(resultCode == RESULT_OK)
            pendingConfigure = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        private const val REQUEST_CONFIGURE_WIDGET = 0xA1
    }

    override fun onDestroy() {
        ActivityBridge.detach(this)
        super.onDestroy()
    }

    /** Runs a Google Play services resolution (account picker / consent) and returns its result data. */
    suspend fun resolveIntentSender(sender: IntentSender): Intent? {
        pendingIntentSender?.complete(null)
        val deferred = CompletableDeferred<Intent?>()
        pendingIntentSender = deferred
        intentSenderLauncher.launch(IntentSenderRequest.Builder(sender).build())
        return deferred.await()
    }

    suspend fun requestPermissionAsync(permission: String): Boolean {
        pendingPermission?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        permissionLauncher.launch(permission)
        return deferred.await()
    }
}
