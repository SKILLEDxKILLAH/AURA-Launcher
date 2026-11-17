package com.aura.launcher

import android.app.Application
import android.app.WallpaperManager
import android.content.Intent
import android.provider.Settings
import com.aura.launcher.system.SystemPanels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.AuraPrefs
import com.aura.launcher.data.DockItem
import com.aura.launcher.data.DockResolver
import com.aura.launcher.data.GeminiAuthMode
import com.aura.launcher.data.WeatherNow
import com.aura.launcher.data.WeatherService
import com.aura.launcher.gemini.AuraAgent
import com.aura.launcher.gemini.GeminiCredential
import com.aura.launcher.gemini.GoogleAccountAuth
import com.aura.launcher.gemini.PhoneTools
import com.aura.launcher.gemini.SignInRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

enum class ChatRole { USER, GEMINI, ACTION, ERROR }

data class ChatItem(val role: ChatRole, val text: String, val ok: Boolean = true)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    val prefs = AuraPrefs(app)
    val repo = AppRepository(app)
    val apps = repo.apps
    val launchCounts = repo.launchCounts
    val googleAuth = GoogleAccountAuth(app)

    var dock by mutableStateOf<List<DockItem>>(emptyList())
        private set
    var weather by mutableStateOf<WeatherNow?>(null)
        private set
    var weatherNeedsPermission by mutableStateOf(!WeatherService.hasPermission(app))
        private set

    // Overlays
    var geminiSheetOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)

    /** Incremented to ask the Gemini panel to start listening as soon as it opens. */
    var listenRequest by mutableStateOf(0)
        private set

    // Gemini conversation
    val chat = mutableStateListOf<ChatItem>()
    var agentBusy by mutableStateOf(false)
        private set
    var signingIn by mutableStateOf(false)
        private set
    var signInError by mutableStateOf<String?>(null)
        private set
    private val tools = PhoneTools(app, repo)
    private val agent = AuraAgent(tools)
    private var agentJob: Job? = null
    private var pendingPrompt: String? = null

    private val _resetToHome = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resetToHome: SharedFlow<Unit> = _resetToHome
    private var resetWhenStopped = false
    private var lastWeatherAt = 0L

    init {
        dock = DockResolver.resolve(app)
    }

    fun launch(entry: AppEntry) {
        repo.launch(entry)
        resetWhenStopped = true
    }

    fun launchDock(item: DockItem) {
        ActivityBridge.start(context, item.intent)
    }

    fun onHomeIntent() {
        _resetToHome.tryEmit(Unit)
        geminiSheetOpen = false
        settingsOpen = false
    }

    fun onStopped() {
        if (resetWhenStopped) {
            resetWhenStopped = false
            onHomeIntent()
        }
    }

    fun onResumed() {
        checkWallpaperChanged()
        dock = DockResolver.resolve(context)
        weatherNeedsPermission = !WeatherService.hasPermission(context)
        val now = System.currentTimeMillis()
        if (prefs.showWeather && now - lastWeatherAt > 20 * 60_000) refreshWeather()
    }

    // ---- Home gestures -------------------------------------------------------------------------

    /** Shown when the system needs the AURA gestures accessibility service turned on. */
    var gestureSetupNeeded by mutableStateOf(false)
    private var gestureHintDismissed = false

    fun pullDown(fingers: Int) {
        val result = if (fingers >= 2) SystemPanels.quickSettings(context) else SystemPanels.notifications(context)
        if (result == SystemPanels.Result.NEEDS_SETUP && !gestureHintDismissed) gestureSetupNeeded = true
    }

    fun enableGestureService() {
        gestureSetupNeeded = false
        ActivityBridge.start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun dismissGestureHint() {
        gestureSetupNeeded = false
        gestureHintDismissed = true
    }

    // ---- Wallpaper ------------------------------------------------------------------------------

    private var wallpaperIdBeforePick: Int? = null

    /** Opens the system wallpaper picker; if a new wallpaper is applied, AURA shows it automatically. */
    fun openWallpaperPicker() {
        val wm = WallpaperManager.getInstance(context)
        wallpaperIdBeforePick = runCatching { wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
        val pixelPicker = Intent(Intent.ACTION_SET_WALLPAPER).setPackage("com.google.android.apps.wallpaper")
        if (!ActivityBridge.start(context, pixelPicker)) {
            ActivityBridge.start(context, Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper & style"))
        }
    }

    private fun checkWallpaperChanged() {
        val before = wallpaperIdBeforePick ?: return
        wallpaperIdBeforePick = null
        val now = runCatching { WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
        if (now != null && now != before) prefs.updateUseAuraBackdrop(false)
    }

    fun refreshWeather() {
        lastWeatherAt = System.currentTimeMillis()
        viewModelScope.launch {
            weatherNeedsPermission = !WeatherService.hasPermission(context)
            WeatherService.fetch(context, prefs.fahrenheit)?.let { weather = it }
        }
    }

    // ---- Gemini (runs inside AURA) -------------------------------------------------------------

    fun openGemini(listen: Boolean = false) {
        geminiSheetOpen = true
        if (listen) listenRequest++
    }

    /** Google account picker + consent, then Gemini is ready. Sends any prompt typed before sign-in. */
    fun signIn() {
        if (signingIn) return
        signingIn = true
        signInError = null
        viewModelScope.launch {
            try {
                val profile = googleAuth.signIn()
                prefs.updateAuthMode(GeminiAuthMode.GOOGLE_ACCOUNT)
                prefs.updateAccount(profile.email, profile.name)
                pendingPrompt?.let { pendingPrompt = null; ask(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                signInError = e.message ?: "Sign-in failed"
            } finally {
                signingIn = false
            }
        }
    }

    fun signOut() {
        val email = prefs.accountEmail
        prefs.updateAccount(null, null)
        clearChat()
        viewModelScope.launch { googleAuth.signOut(email) }
    }

    private fun credential(): GeminiCredential? = when (prefs.authMode) {
        GeminiAuthMode.GOOGLE_ACCOUNT -> if (prefs.accountEmail != null) {
            GeminiCredential.GoogleAccount { force -> googleAuth.accessToken(force) }
        } else null
        GeminiAuthMode.API_KEY -> prefs.apiKey.takeIf { it.isNotBlank() }?.let { GeminiCredential.ApiKey(it) }
    }

    fun ask(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty()) return
        geminiSheetOpen = true
        val credential = credential()
        if (credential == null) {
            // Not signed in yet: keep the prompt and send it right after sign-in.
            pendingPrompt = text
            if (prefs.authMode == GeminiAuthMode.GOOGLE_ACCOUNT) signIn()
            return
        }
        chat += ChatItem(ChatRole.USER, text)
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            agentBusy = true
            try {
                agent.send(text, credential, prefs.model, apps.value.map { it.label }.distinct()) { event ->
                    when (event) {
                        is AuraAgent.Event.Reply -> chat += ChatItem(ChatRole.GEMINI, event.text)
                        is AuraAgent.Event.Action -> chat += ChatItem(ChatRole.ACTION, event.label, event.ok)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                // Consent was withdrawn or expired; ask again in place.
                prefs.updateAccount(null, null)
                chat += ChatItem(ChatRole.ERROR, "Please sign in with Google again to keep using Gemini.")
            } catch (e: Exception) {
                chat += ChatItem(ChatRole.ERROR, e.message ?: "Something went wrong")
            } finally {
                agentBusy = false
            }
        }
    }

    fun clearChat() {
        agentJob?.cancel()
        agent.reset()
        chat.clear()
    }
}
