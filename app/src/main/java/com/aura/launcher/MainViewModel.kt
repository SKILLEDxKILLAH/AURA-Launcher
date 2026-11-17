package com.aura.launcher

import android.app.Application
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
import com.aura.launcher.data.WeatherNow
import com.aura.launcher.data.WeatherService
import com.aura.launcher.gemini.AuraAgent
import com.aura.launcher.gemini.GeminiBridge
import com.aura.launcher.gemini.PhoneTools
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

    var dock by mutableStateOf<List<DockItem>>(emptyList())
        private set
    var weather by mutableStateOf<WeatherNow?>(null)
        private set
    var weatherNeedsPermission by mutableStateOf(!WeatherService.hasPermission(app))
        private set

    // Overlays
    var geminiSheetOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)

    // Gemini conversation
    val chat = mutableStateListOf<ChatItem>()
    var agentBusy by mutableStateOf(false)
        private set
    private val tools = PhoneTools(app, repo)
    private val agent = AuraAgent(tools)
    private var agentJob: Job? = null

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
        dock = DockResolver.resolve(context)
        weatherNeedsPermission = !WeatherService.hasPermission(context)
        val now = System.currentTimeMillis()
        if (prefs.showWeather && now - lastWeatherAt > 20 * 60_000) refreshWeather()
    }

    fun refreshWeather() {
        lastWeatherAt = System.currentTimeMillis()
        viewModelScope.launch {
            weatherNeedsPermission = !WeatherService.hasPermission(context)
            WeatherService.fetch(context, prefs.fahrenheit)?.let { weather = it }
        }
    }

    // ---- Gemini -----------------------------------------------------------------------------

    fun openGeminiApp() = GeminiBridge.open(context)
    fun geminiVoice() = GeminiBridge.voice(context)

    /** Routes a prompt to the in-launcher agent when configured, otherwise into the Gemini app. */
    fun ask(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty()) return
        if (!prefs.agentReady) {
            GeminiBridge.ask(context, text)
            return
        }
        geminiSheetOpen = true
        chat += ChatItem(ChatRole.USER, text)
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            agentBusy = true
            try {
                agent.send(text, prefs.apiKey, prefs.model, apps.value.map { it.label }.distinct()) { event ->
                    when (event) {
                        is AuraAgent.Event.Reply -> chat += ChatItem(ChatRole.GEMINI, event.text)
                        is AuraAgent.Event.Action -> chat += ChatItem(ChatRole.ACTION, event.label, event.ok)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
