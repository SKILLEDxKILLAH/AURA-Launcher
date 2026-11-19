package com.aura.launcher.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.util.Locale

/** How hard Gemini thinks before answering. AUTO lets the model decide. */
enum class ThinkingLevel(val label: String, val blurb: String) {
    AUTO("Auto", "The model decides how much to think"),
    MINIMAL("Minimal", "Fastest replies, least reasoning"),
    LOW("Low", "Quick, light reasoning"),
    MEDIUM("Medium", "Balanced speed and depth"),
    HIGH("High", "Deepest reasoning, slowest replies"),
}

/** How the app drawer lists apps. */
enum class DrawerLayout(val label: String) { LIST("List"), COMPACT("Compact"), GRID("Grid") }

/** What sits at the top-left of the home screen. */
enum class TopLineMode { LABEL, WEATHER }

/** How AURA reaches Gemini. All of them run inside the launcher. */
enum class GeminiAuthMode {
    /** Google sign-in through the developer's Google Cloud OAuth client. */
    GOOGLE_ACCOUNT,
    /** The person's own free key from Google AI Studio. */
    API_KEY,
    /** Gemini Nano on the phone itself: no account, no key, nothing leaves the device. */
    ON_DEVICE,
}

/** Small observable preference store. Every property is Compose state, so UI recomposes on change. */
class AuraPrefs(context: Context) {
    private val sp = context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)
    /** Kept apart from settings so it can be left out of cloud backup. */
    private val secrets = context.getSharedPreferences("aura_secrets", Context.MODE_PRIVATE)

    init {
        // Older versions kept the API key with the settings: move it.
        sp.getString(K_API_KEY, null)?.let { old ->
            if (secrets.getString(K_API_KEY, null) == null) secrets.edit { putString(K_API_KEY, old) }
            sp.edit { remove(K_API_KEY) }
        }
    }

    var useAuraBackdrop by mutableStateOf(sp.getBoolean(K_BACKDROP, true))
        private set
    var showWeather by mutableStateOf(sp.getBoolean(K_WEATHER, true))
        private set
    var fahrenheit by mutableStateOf(
        sp.getBoolean(K_FAHRENHEIT, Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW"))
    )
        private set
    var authMode by mutableStateOf(
        runCatching { GeminiAuthMode.valueOf(sp.getString(K_AUTH_MODE, null) ?: "") }.getOrDefault(GeminiAuthMode.GOOGLE_ACCOUNT)
    )
        private set
    var accountEmail by mutableStateOf(sp.getString(K_ACCOUNT_EMAIL, null))
        private set
    var accountName by mutableStateOf(sp.getString(K_ACCOUNT_NAME, null))
        private set
    var apiKey by mutableStateOf(secrets.getString(K_API_KEY, "") ?: "")
        private set
    var model by mutableStateOf(sp.getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL)
        private set
    /** Gemini bar at the bottom of the app drawer. */
    var drawerGeminiBar by mutableStateOf(sp.getBoolean(K_DRAWER_BAR, true))
        private set
    /** Round sparkle Gemini button in the app drawer. */
    var drawerGeminiButton by mutableStateOf(sp.getBoolean(K_DRAWER_BUTTON, true))
        private set
    var thinking by mutableStateOf(
        runCatching { ThinkingLevel.valueOf(sp.getString(K_THINKING, null) ?: "") }.getOrDefault(ThinkingLevel.AUTO)
    )
        private set
    var topLineMode by mutableStateOf(
        runCatching { TopLineMode.valueOf(sp.getString(K_TOP_MODE, null) ?: "") }.getOrDefault(TopLineMode.LABEL)
    )
        private set
    /** Custom top label; null means the default "Android <version>". */
    var topLabel by mutableStateOf(sp.getString(K_TOP_LABEL, null))
        private set
    var favorites by mutableStateOf(sp.getStringSet(K_FAVORITES, emptySet())?.toSet() ?: emptySet())
        private set

    // ---- App drawer ----
    var drawerLayout by mutableStateOf(
        runCatching { DrawerLayout.valueOf(sp.getString(K_DRAWER_LAYOUT, null) ?: "") }.getOrDefault(DrawerLayout.LIST)
    )
        private set
    /** Apps left out of the drawer list and suggestions; still found by search. */
    var hiddenApps by mutableStateOf(sp.getStringSet(K_HIDDEN, emptySet())?.toSet() ?: emptySet())
        private set
    /** Opening the drawer goes straight to typing in search. */
    var drawerKeyboard by mutableStateOf(sp.getBoolean(K_DRAWER_KEYBOARD, false))
        private set
    var drawerTipSeen by mutableStateOf(sp.getBoolean(K_DRAWER_TIP, false))
        private set

    /** Apps you chose for the dock, in order; empty means AURA picks your default apps. */
    var dockKeys by mutableStateOf(sp.getString(K_DOCK, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList())
        private set
    fun updateDockKeys(v: List<String>) { dockKeys = v.distinct().take(MAX_DOCK); sp.edit { putString(K_DOCK, dockKeys.joinToString("\n")) } }

    /** First-run welcome has been shown (or skipped). */
    var welcomeDone by mutableStateOf(sp.getBoolean(K_WELCOME, false))
        private set
    fun markWelcomeDone() { welcomeDone = true; sp.edit { putBoolean(K_WELCOME, true) } }

    fun updateDrawerLayout(v: DrawerLayout) { drawerLayout = v; sp.edit { putString(K_DRAWER_LAYOUT, v.name) } }
    fun toggleHidden(key: String) {
        hiddenApps = if (key in hiddenApps) hiddenApps - key else hiddenApps + key
        sp.edit { putStringSet(K_HIDDEN, hiddenApps) }
    }
    fun updateDrawerKeyboard(v: Boolean) { drawerKeyboard = v; sp.edit { putBoolean(K_DRAWER_KEYBOARD, v) } }
    fun markDrawerTipSeen() { drawerTipSeen = true; sp.edit { putBoolean(K_DRAWER_TIP, true) } }
    fun resetDrawerTip() { drawerTipSeen = false; sp.edit { putBoolean(K_DRAWER_TIP, false) } }

    // ---- Look ----
    var themeMode by mutableStateOf(
        runCatching { com.aura.launcher.ui.theme.ThemeMode.valueOf(sp.getString(K_THEME_MODE, null) ?: "") }
            .getOrDefault(com.aura.launcher.ui.theme.ThemeMode.DARK)
    )
        private set
    var colorSource by mutableStateOf(
        runCatching { com.aura.launcher.ui.theme.ColorSource.valueOf(sp.getString(K_COLOR_SOURCE, null) ?: "") }
            .getOrDefault(com.aura.launcher.ui.theme.ColorSource.AURA)
    )
        private set
    /** Single-color app icons tinted to the theme, for apps that provide one (like Pixel's themed icons). */
    var themedIcons by mutableStateOf(sp.getBoolean(K_THEMED_ICONS, false))
        private set

    fun updateThemeMode(v: com.aura.launcher.ui.theme.ThemeMode) { themeMode = v; sp.edit { putString(K_THEME_MODE, v.name) } }
    fun updateColorSource(v: com.aura.launcher.ui.theme.ColorSource) { colorSource = v; sp.edit { putString(K_COLOR_SOURCE, v.name) } }
    fun updateThemedIcons(v: Boolean) { themedIcons = v; sp.edit { putBoolean(K_THEMED_ICONS, v) } }

    fun updateUseAuraBackdrop(v: Boolean) { useAuraBackdrop = v; sp.edit { putBoolean(K_BACKDROP, v) } }
    fun updateShowWeather(v: Boolean) { showWeather = v; sp.edit { putBoolean(K_WEATHER, v) } }
    fun updateFahrenheit(v: Boolean) { fahrenheit = v; sp.edit { putBoolean(K_FAHRENHEIT, v) } }
    fun updateAuthMode(v: GeminiAuthMode) { authMode = v; sp.edit { putString(K_AUTH_MODE, v.name) } }
    fun updateApiKey(v: String) { apiKey = v.trim(); secrets.edit { putString(K_API_KEY, apiKey) } }
    fun updateModel(v: String) {
        model = v.trim().ifEmpty { DEFAULT_MODEL }
        sp.edit { putString(K_MODEL, model) }
    }

    fun updateAccount(email: String?, name: String?) {
        accountEmail = email
        accountName = name
        sp.edit { putString(K_ACCOUNT_EMAIL, email); putString(K_ACCOUNT_NAME, name) }
    }

    fun updateDrawerGeminiBar(v: Boolean) { drawerGeminiBar = v; sp.edit { putBoolean(K_DRAWER_BAR, v) } }
    fun updateDrawerGeminiButton(v: Boolean) { drawerGeminiButton = v; sp.edit { putBoolean(K_DRAWER_BUTTON, v) } }

    fun updateThinking(v: ThinkingLevel) { thinking = v; sp.edit { putString(K_THINKING, v.name) } }

    fun updateTopLineMode(v: TopLineMode) { topLineMode = v; sp.edit { putString(K_TOP_MODE, v.name) } }

    /** Blank resets to the default label. */
    fun updateTopLabel(v: String) {
        topLabel = v.trim().take(MAX_LABEL).ifEmpty { null }
        sp.edit { putString(K_TOP_LABEL, topLabel) }
    }

    fun toggleFavorite(key: String) {
        favorites = if (key in favorites) favorites - key else favorites + key
        sp.edit { putStringSet(K_FAVORITES, favorites) }
    }

    /** True when Gemini can run right now without asking the user for anything. */
    val geminiReady: Boolean
        get() = when (authMode) {
            GeminiAuthMode.GOOGLE_ACCOUNT -> accountEmail != null
            GeminiAuthMode.API_KEY -> apiKey.isNotBlank()
            GeminiAuthMode.ON_DEVICE -> true
        }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        private const val K_BACKDROP = "aura_backdrop"
        private const val K_WEATHER = "show_weather"
        private const val K_FAHRENHEIT = "fahrenheit"
        private const val K_AUTH_MODE = "gemini_auth_mode"
        private const val K_ACCOUNT_EMAIL = "google_account_email"
        private const val K_ACCOUNT_NAME = "google_account_name"
        private const val K_API_KEY = "gemini_api_key"
        private const val K_MODEL = "gemini_model"
        private const val K_FAVORITES = "favorites"
        private const val K_TOP_MODE = "top_line_mode"
        private const val K_THINKING = "gemini_thinking"
        private const val K_DRAWER_BAR = "drawer_gemini_bar"
        private const val K_DRAWER_BUTTON = "drawer_gemini_button"
        private const val K_TOP_LABEL = "top_label"
        private const val K_THEME_MODE = "theme_mode"
        private const val K_DRAWER_LAYOUT = "drawer_layout"
        private const val K_DOCK = "dock_keys"
        private const val K_WELCOME = "welcome_done"
        const val MAX_DOCK = 5
        private const val K_HIDDEN = "hidden_apps"
        private const val K_DRAWER_KEYBOARD = "drawer_keyboard"
        private const val K_DRAWER_TIP = "drawer_tip_seen"
        private const val K_COLOR_SOURCE = "color_source"
        private const val K_THEMED_ICONS = "themed_icons"
        const val MAX_LABEL = 40
    }
}
