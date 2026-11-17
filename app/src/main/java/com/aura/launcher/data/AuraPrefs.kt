package com.aura.launcher.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.util.Locale

enum class GeminiEngine { GEMINI_APP, AURA_AGENT }

/** Small observable preference store. Every property is Compose state, so UI recomposes on change. */
class AuraPrefs(context: Context) {
    private val sp = context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)

    var useAuraBackdrop by mutableStateOf(sp.getBoolean(K_BACKDROP, true))
        private set
    var showWeather by mutableStateOf(sp.getBoolean(K_WEATHER, true))
        private set
    var fahrenheit by mutableStateOf(
        sp.getBoolean(K_FAHRENHEIT, Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW"))
    )
        private set
    var engine by mutableStateOf(
        runCatching { GeminiEngine.valueOf(sp.getString(K_ENGINE, null) ?: "") }.getOrDefault(GeminiEngine.GEMINI_APP)
    )
        private set
    var apiKey by mutableStateOf(sp.getString(K_API_KEY, "") ?: "")
        private set
    var model by mutableStateOf(sp.getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL)
        private set
    var favorites by mutableStateOf(sp.getStringSet(K_FAVORITES, emptySet())?.toSet() ?: emptySet())
        private set

    fun updateUseAuraBackdrop(v: Boolean) { useAuraBackdrop = v; sp.edit { putBoolean(K_BACKDROP, v) } }
    fun updateShowWeather(v: Boolean) { showWeather = v; sp.edit { putBoolean(K_WEATHER, v) } }
    fun updateFahrenheit(v: Boolean) { fahrenheit = v; sp.edit { putBoolean(K_FAHRENHEIT, v) } }
    fun updateEngine(v: GeminiEngine) { engine = v; sp.edit { putString(K_ENGINE, v.name) } }
    fun updateApiKey(v: String) { apiKey = v.trim(); sp.edit { putString(K_API_KEY, apiKey) } }
    fun updateModel(v: String) {
        model = v.trim().ifEmpty { DEFAULT_MODEL }
        sp.edit { putString(K_MODEL, model) }
    }

    fun toggleFavorite(key: String) {
        favorites = if (key in favorites) favorites - key else favorites + key
        sp.edit { putStringSet(K_FAVORITES, favorites) }
    }

    /** The in-launcher agent is usable only when the user chose it and supplied a key. */
    val agentReady: Boolean get() = engine == GeminiEngine.AURA_AGENT && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        private const val K_BACKDROP = "aura_backdrop"
        private const val K_WEATHER = "show_weather"
        private const val K_FAHRENHEIT = "fahrenheit"
        private const val K_ENGINE = "gemini_engine"
        private const val K_API_KEY = "gemini_api_key"
        private const val K_MODEL = "gemini_model"
        private const val K_FAVORITES = "favorites"
    }
}
