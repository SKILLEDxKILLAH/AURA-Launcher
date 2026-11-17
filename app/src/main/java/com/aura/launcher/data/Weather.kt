package com.aura.launcher.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

data class WeatherNow(
    val temperature: Int,
    val unit: String,
    val description: String,
    val sky: Sky,
    val isDay: Boolean,
    val high: Int? = null,
    val low: Int? = null,
)

/** Current conditions from Open-Meteo (free, no API key) using the coarse last-known location. */
object WeatherService {

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun fetch(context: Context, fahrenheit: Boolean): WeatherNow? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null
        val location = location(context) ?: return@withContext null
        runCatching {
            val url = URL(
                String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                        "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto&temperature_unit=%s",
                    location.latitude, location.longitude, if (fahrenheit) "fahrenheit" else "celsius",
                )
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val root = JSONObject(body)
            val current = root.getJSONObject("current")
            val daily = root.optJSONObject("daily")
            val code = current.getInt("weather_code")
            val (desc, sky) = describe(code)
            WeatherNow(
                temperature = Math.round(current.getDouble("temperature_2m")).toInt(),
                unit = if (fahrenheit) "°F" else "°C",
                description = desc,
                sky = sky,
                isDay = current.optInt("is_day", 1) == 1,
                high = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeIf { !it.isNaN() }?.let { Math.round(it).toInt() },
                low = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeIf { !it.isNaN() }?.let { Math.round(it).toInt() },
            )
        }.getOrNull()
    }

    @Suppress("MissingPermission")
    private suspend fun location(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { return it }

        val provider = providers.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        return withTimeoutOrNull(6000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    lm.getCurrentLocation(provider, null, context.mainExecutor) { loc -> cont.resume(loc) }
                }.onFailure { cont.resume(null) }
            }
        }
    }

    private fun describe(code: Int): Pair<String, Sky> = when (code) {
        0 -> "Clear" to Sky.CLEAR
        1 -> "Mostly clear" to Sky.CLEAR
        2 -> "Partly cloudy" to Sky.PARTLY
        3 -> "Cloudy" to Sky.CLOUDY
        45, 48 -> "Foggy" to Sky.FOG
        in 51..57 -> "Drizzle" to Sky.RAIN
        in 61..67 -> "Rain" to Sky.RAIN
        in 71..77 -> "Snow" to Sky.SNOW
        in 80..82 -> "Showers" to Sky.RAIN
        85, 86 -> "Snow showers" to Sky.SNOW
        in 95..99 -> "Thunderstorms" to Sky.STORM
        else -> "—" to Sky.CLOUDY
    }
}
