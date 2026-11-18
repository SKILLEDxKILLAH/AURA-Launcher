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
import com.aura.launcher.data.AppPredictor
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.AuraPrefs
import com.aura.launcher.data.DockItem
import com.aura.launcher.data.DockResolver
import com.aura.launcher.data.GeminiAuthMode
import com.aura.launcher.data.NewsService
import com.aura.launcher.data.NewsStory
import com.aura.launcher.data.NewsTopic
import com.aura.launcher.data.ThinkingLevel
import com.aura.launcher.data.TopLineMode
import com.aura.launcher.data.WeatherNow
import com.aura.launcher.data.WeatherService
import com.aura.launcher.gemini.AuraAgent
import com.aura.launcher.gemini.CheckResult
import com.aura.launcher.gemini.GeminiApi
import com.aura.launcher.gemini.GeminiCredential
import com.aura.launcher.gemini.GeminiDiagnostics
import com.aura.launcher.gemini.GeminiModel
import com.aura.launcher.gemini.GoogleAccountAuth
import com.aura.launcher.gemini.PhoneTools
import com.aura.launcher.gemini.SignInRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

enum class ChatRole { USER, GEMINI, ACTION, ERROR, THOUGHT, NOTICE }

/** One line in the Gemini chat. [streaming] is true while Gemini is still writing it. */
data class ChatItem(val role: ChatRole, val text: String, val ok: Boolean = true, val millis: Long = 0, val streaming: Boolean = false)

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

    /** Home-screen widgets. */
    val widgets = com.aura.launcher.data.AuraWidgets(app)
    var widgetPickerOpen by mutableStateOf(false)

    fun addWidget(choice: com.aura.launcher.data.WidgetChoice) {
        widgetPickerOpen = false
        viewModelScope.launch { ActivityBridge.activity?.addWidget(choice.info) }
    }

    // Overlays
    private var geminiSheetShown by mutableStateOf(false)

    /**
     * Whether the Gemini panel is showing. Each time it opens it starts a new chat: whatever was
     * left from the last time (a finished reply, a request still running, a prompt waiting for
     * sign-in) is cleared first, so closing the panel ends that conversation.
     */
    var geminiSheetOpen: Boolean
        get() = geminiSheetShown
        set(open) {
            if (open && !geminiSheetShown) {
                clearChat()
                pendingPrompt = null
            }
            geminiSheetShown = open
        }
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
        refreshSuggestions()
    }

    fun launchShortcut(shortcut: com.aura.launcher.data.AppShortcut) {
        repo.startShortcut(shortcut)
        resetWhenStopped = true
    }

    fun launchDock(item: DockItem) {
        if (ActivityBridge.start(context, item.intent)) repo.recordPackage(item.packageName)
    }

    // ---- Suggested / Recent ---------------------------------------------------------------------

    var suggested by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var recent by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var usageAccess by mutableStateOf(false)
        private set

    /** Recomputes predictions for the current time of day from AURA's log + Android usage history. */
    fun refreshSuggestions() {
        viewModelScope.launch {
            usageAccess = repo.hasUsageAccess()
            val all = apps.value
            if (all.isEmpty()) return@launch
            val byKey = all.associateBy { it.key }
            val events = repo.launchLog.value + repo.systemLaunches()
            val keys = byKey.keys
            val predicted = AppPredictor.suggest(events, AppPredictor.now(), keys, limit = 5).toMutableList()
            // Cold start: fill with most-launched apps so the row is useful from day one.
            if (predicted.size < 5) {
                launchCounts.value.entries.sortedByDescending { it.value }.map { it.key }
                    .filter { it in keys && it !in predicted }.take(5 - predicted.size).let(predicted::addAll)
            }
            suggested = predicted.mapNotNull(byKey::get)
            recent = AppPredictor.recent(events, keys, limit = 5).mapNotNull(byKey::get)
        }
    }

    fun openUsageAccessSettings() {
        ActivityBridge.start(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    fun onHomeIntent() {
        _resetToHome.tryEmit(Unit)
        widgetPickerOpen = false
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
        widgets.pruneMissing()
        refreshSuggestions()
        dock = DockResolver.resolve(context)
        weatherNeedsPermission = !WeatherService.hasPermission(context)
        val now = System.currentTimeMillis()
        if ((prefs.showWeather || prefs.topLineMode == TopLineMode.WEATHER) && now - lastWeatherAt > 20 * 60_000) refreshWeather()
    }

    // ---- Home gestures -------------------------------------------------------------------------

    /** Shown when the system needs the AURA gestures accessibility service turned on. */
    var gestureSetupNeeded by mutableStateOf(false)
    private var gestureHintDismissed = false

    fun pullDown(fingers: Int) {
        val result = if (fingers >= 2) SystemPanels.quickSettings(context) else SystemPanels.notifications(context)
        if (result == SystemPanels.Result.NEEDS_SETUP && !gestureHintDismissed) gestureSetupNeeded = true
    }

    /**
     * Google Play requires a separate, explicit disclosure before a non-accessibility app sends
     * people to turn on its accessibility service. "Turn on" anywhere shows it first.
     */
    var gestureDisclosureOpen by mutableStateOf(false)

    fun enableGestureService() {
        gestureSetupNeeded = false
        gestureDisclosureOpen = true
    }

    fun acceptGestureDisclosure() {
        gestureDisclosureOpen = false
        ActivityBridge.start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun declineGestureDisclosure() {
        gestureDisclosureOpen = false
    }

    /** AURA's App info page, where ⋮ → "Allow restricted settings" lives for sideloaded apps. */
    fun openAuraAppInfo() {
        ActivityBridge.start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)),
        )
    }

    fun dismissGestureHint() {
        gestureSetupNeeded = false
        gestureHintDismissed = true
    }

    /** Opens weather the way the Pixel launcher does: Pixel Weather, then Google's weather, then search. */
    fun openWeatherApp() {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage("com.google.android.apps.weather")?.let { if (ActivityBridge.start(context, it)) return }
        val googleWeather = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("dynact://velour/weather/ProxyActivity"))
            .setComponent(android.content.ComponentName(DockResolver.GOOGLE_PACKAGE, "com.google.android.apps.gsa.velour.DynamicActivityTrampoline"))
        if (ActivityBridge.start(context, googleWeather)) return
        ActivityBridge.start(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, "weather"))
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

    /** True when the system wallpaper is light enough for dark text (Android's own hint). */
    var wallpaperIsLight by mutableStateOf(readWallpaperIsLight())
        private set

    private fun readWallpaperIsLight(): Boolean = runCatching {
        val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        colors != null && (colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
    }.getOrDefault(false)

    private val wallpaperColorsListener = WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) wallpaperIsLight = readWallpaperIsLight()
    }

    init {
        runCatching {
            WallpaperManager.getInstance(context).addOnColorsChangedListener(wallpaperColorsListener, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    override fun onCleared() {
        runCatching { WallpaperManager.getInstance(context).removeOnColorsChangedListener(wallpaperColorsListener) }
        super.onCleared()
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
            GeminiCredential.GoogleAccount(widenScopes = { googleAuth.widenScopes() }) { force -> googleAuth.accessToken(force) }
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
            // Where the thought and reply being written right now sit in the chat, so each new
            // piece updates them in place instead of adding lines.
            var liveThought = -1
            var liveReply = -1
            fun put(index: Int, item: ChatItem): Int =
                if (index in chat.indices) { chat[index] = item; index } else { chat += item; chat.lastIndex }
            try {
                agent.send(text, credential, prefs.model, prefs.thinking) { event ->
                    when (event) {
                        is AuraAgent.Event.Partial -> {
                            if (event.thought.isNotEmpty()) liveThought = put(liveThought, ChatItem(ChatRole.THOUGHT, event.thought, streaming = true))
                            if (event.reply.isNotEmpty()) liveReply = put(liveReply, ChatItem(ChatRole.GEMINI, event.reply, streaming = true))
                        }
                        is AuraAgent.Event.Thought -> {
                            put(liveThought, ChatItem(ChatRole.THOUGHT, event.summary, millis = event.millis))
                            liveThought = -1
                        }
                        is AuraAgent.Event.Reply -> {
                            put(liveReply, ChatItem(ChatRole.GEMINI, event.text))
                            liveReply = -1
                        }
                        is AuraAgent.Event.Action -> {
                            // A step that ended in a tool call: whatever was streamed is now final.
                            liveThought = -1; liveReply = -1
                            chat += ChatItem(ChatRole.ACTION, event.label, event.ok)
                        }
                        is AuraAgent.Event.Notice -> chat += ChatItem(ChatRole.NOTICE, event.text)
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
                // Stopped or failed part-way: keep what was written, but as finished lines.
                for (i in chat.indices) if (chat[i].streaming) chat[i] = chat[i].copy(streaming = false)
                agentBusy = false
            }
        }
    }

    // ---- Models ---------------------------------------------------------------------------------

    var models by mutableStateOf<List<GeminiModel>>(emptyList())
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var modelsError by mutableStateOf<String?>(null)
        private set

    /** Fetches the models this account can use (once per session unless [force]). */
    fun loadModels(force: Boolean = false) {
        if (modelsLoading || (models.isNotEmpty() && !force)) return
        val credential = credential() ?: return
        modelsLoading = true
        modelsError = null
        viewModelScope.launch {
            try {
                models = GeminiApi.listModels(credential)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                modelsError = e.message ?: "Couldn't load models"
            } finally {
                modelsLoading = false
            }
        }
    }

    fun selectModel(id: String) {
        if (id == prefs.model) return
        prefs.updateModel(id)
        // Model-specific reasoning state can't be mixed across models, so start a fresh context.
        agentJob?.cancel()
        agent.reset()
        if (chat.isNotEmpty()) chat += ChatItem(ChatRole.ACTION, "Switched to ${models.firstOrNull { it.id == id }?.displayName ?: id}")
    }

    fun selectThinking(level: ThinkingLevel) {
        prefs.updateThinking(level)
    }

    // ---- News feed ------------------------------------------------------------------------------

    var newsTopic by mutableStateOf(NewsTopic.TOP)
        private set
    var news by mutableStateOf<Map<NewsTopic, List<NewsStory>>>(emptyMap())
        private set
    var newsLoading by mutableStateOf(false)
        private set
    var newsError by mutableStateOf<String?>(null)
        private set
    private val newsFetchedAt = mutableMapOf<NewsTopic, Long>()

    /** Loads a section; cached for 15 minutes unless [force]. */
    fun loadNews(topic: NewsTopic = newsTopic, force: Boolean = false) {
        newsTopic = topic
        val fresh = System.currentTimeMillis() - (newsFetchedAt[topic] ?: 0L) < 15 * 60_000
        if (newsLoading || (!force && fresh && news[topic] != null)) return
        newsLoading = true
        newsError = null
        viewModelScope.launch {
            try {
                val stories = NewsService.fetch(topic)
                news = news + (topic to stories)
                newsFetchedAt[topic] = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                newsError = e.message ?: "Couldn't load the news"
            } finally {
                newsLoading = false
            }
        }
    }

    fun openStory(story: NewsStory) {
        ActivityBridge.start(context, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(story.link)))
    }

    /** Asks Gemini for a short briefing on the headlines currently shown. */
    fun briefMe() {
        val stories = news[newsTopic].orEmpty().take(12)
        if (stories.isEmpty()) return
        val list = stories.joinToString("\n") { "- ${it.title} (${it.source})" }
        ask("Give me a quick spoken-style briefing of today's ${newsTopic.label.lowercase()} in 4 short bullet points, based only on these headlines:\n$list")
    }

    // ---- Diagnostics ----------------------------------------------------------------------------

    val diagnostics = mutableStateListOf<CheckResult>()
    var diagnosticsOpen by mutableStateOf(false)
    var diagnosticsRunning by mutableStateOf(false)
        private set

    fun runDiagnostics() {
        if (diagnosticsRunning) return
        diagnosticsOpen = true
        diagnostics.clear()
        diagnosticsRunning = true
        viewModelScope.launch {
            try {
                GeminiDiagnostics.run(context, prefs, credential(), tools) { diagnostics += it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics += CheckResult("Unexpected error", false, e.message ?: e.javaClass.simpleName)
            } finally {
                diagnosticsRunning = false
            }
        }
    }

    /** Sends a report about a Gemini reply to the developer and confirms it in the chat. */
    fun reportReply(item: ChatItem, reason: com.aura.launcher.gemini.ReportReason) {
        val reports = com.aura.launcher.gemini.ReplyReports
        if (!reports.hasEndpoint) {
            ActivityBridge.start(context, reports.issueIntent(item.text, reason, prefs.model))
            return
        }
        viewModelScope.launch {
            val ok = reports.send(item.text, reason, prefs.model)
            chat += ChatItem(ChatRole.NOTICE, if (ok) "Thanks — your report was sent." else "Couldn't send the report. Check your connection and try again.")
        }
    }

    fun clearChat() {
        agentJob?.cancel()
        agent.reset()
        chat.clear()
    }
}
