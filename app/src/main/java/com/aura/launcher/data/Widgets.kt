package com.aura.launcher.data

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.util.SizeF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** A widget on the home screen: its Android widget id and how tall it is. */
data class PlacedWidget(val id: Int, val heightDp: Int)

/** An installable widget, for the picker. */
data class WidgetChoice(
    val info: AppWidgetProviderInfo,
    val label: String,
    val appLabel: String,
    val packageName: String,
    val user: UserHandle,
    /** Size in home-screen cells, e.g. "4 × 2", when the app says. */
    val cells: String?,
)

/**
 * AURA's home-screen widgets: hosts them (so they keep updating), remembers which ones you
 * placed and their sizes, and lists every widget installed apps offer.
 */
class AuraWidgets(private val context: Context) {
    val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
    val host: AppWidgetHost = AppWidgetHost(context, HOST_ID)
    private val sp = context.getSharedPreferences("aura_widgets", Context.MODE_PRIVATE)

    var placed by mutableStateOf(read())
        private set

    fun startListening() = runCatching { host.startListening() }
    fun stopListening() = runCatching { host.stopListening() }

    /** Every home-screen widget offered by apps in your profiles (personal and work), by app. */
    fun choices(): List<WidgetChoice> {
        val pm = context.packageManager
        val profiles = runCatching { context.getSystemService(UserManager::class.java).userProfiles }.getOrNull()
            ?: listOf(android.os.Process.myUserHandle())
        return profiles.flatMap { user ->
            runCatching { manager.getInstalledProvidersForProfile(user) }.getOrNull().orEmpty().map { user to it }
        }.filter { (_, info) ->
            info.provider.packageName != context.packageName &&
                (info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0
        }.map { (user, info) ->
            val pkg = info.provider.packageName
            val appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val cells = if (info.targetCellWidth > 0 && info.targetCellHeight > 0) "${info.targetCellWidth} × ${info.targetCellHeight}" else null
            WidgetChoice(info, runCatching { info.loadLabel(pm) }.getOrNull() ?: appLabel, appLabel, pkg, user, cells)
        }.sortedWith(compareBy(java.text.Collator.getInstance()) { it: WidgetChoice -> it.appLabel }.thenBy { it.label })
    }

    fun allocateId(): Int = host.allocateAppWidgetId()

    /** Binds without asking when Android allows it; false means the user must be asked. */
    fun bindIfAllowed(id: Int, info: AppWidgetProviderInfo): Boolean = runCatching {
        val options = sizeOptions(defaultHeightDp(info))
        val profile = info.profile
        if (profile == null || profile == android.os.Process.myUserHandle()) manager.bindAppWidgetIdIfAllowed(id, info.provider, options)
        else manager.bindAppWidgetIdIfAllowed(id, profile, info.provider, options) // work-profile widget
    }.getOrDefault(false)

    /** True when the widget wants its setup screen before it can be shown. */
    fun needsConfiguration(info: AppWidgetProviderInfo): Boolean =
        info.configure != null && (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0

    fun defaultHeightDp(info: AppWidgetProviderInfo): Int {
        val density = context.resources.displayMetrics.density
        val min = (info.minHeight / density).toInt()
        return (min + 16).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
    }

    fun add(id: Int, heightDp: Int) {
        placed = placed.filter { it.id != id } + PlacedWidget(id, heightDp.coerceIn(MIN_HEIGHT, MAX_HEIGHT))
        save()
    }

    fun remove(id: Int) {
        runCatching { host.deleteAppWidgetId(id) }
        placed = placed.filter { it.id != id }
        save()
    }

    /** Gives back an id that was allocated but never placed (setup cancelled). */
    fun discard(id: Int) {
        runCatching { host.deleteAppWidgetId(id) }
    }

    fun resize(id: Int, deltaDp: Int) {
        placed = placed.map { if (it.id == id) it.copy(heightDp = (it.heightDp + deltaDp).coerceIn(MIN_HEIGHT, MAX_HEIGHT)) else it }
        save()
    }

    fun move(id: Int, by: Int) {
        val i = placed.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in placed.indices) return
        placed = placed.toMutableList().also { list -> list.add(j, list.removeAt(i)) }
        save()
    }

    fun info(id: Int): AppWidgetProviderInfo? = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()

    /** The live widget view, or null if its app was uninstalled. */
    fun createView(context: Context, id: Int): AppWidgetHostView? {
        val info = info(id) ?: return null
        return runCatching { host.createView(context, id, info) }.getOrNull()
    }

    /** Tells the widget how much room it has, so it can choose its layout. */
    fun sizeOptions(heightDp: Int, widthDp: Int = defaultWidthDp()): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
    }

    private fun defaultWidthDp(): Int {
        val m = context.resources.displayMetrics
        return (m.widthPixels / m.density).toInt() - 32
    }

    /** Removes widgets whose app is gone, so they don't leave empty gaps. */
    fun pruneMissing() {
        val keep = placed.filter { info(it.id) != null }
        if (keep.size != placed.size) {
            placed.filter { it !in keep }.forEach { runCatching { host.deleteAppWidgetId(it.id) } }
            placed = keep
            save()
        }
    }

    private fun read(): List<PlacedWidget> = runCatching {
        val arr = JSONArray(sp.getString(K_PLACED, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> PlacedWidget(o.getInt("id"), o.getInt("h")) } }
    }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray()
        placed.forEach { arr.put(JSONObject().put("id", it.id).put("h", it.heightDp)) }
        sp.edit { putString(K_PLACED, arr.toString()) }
    }

    companion object {
        const val HOST_ID = 0xA0A
        const val MIN_HEIGHT = 72
        const val MAX_HEIGHT = 420
        private const val K_PLACED = "placed"
    }
}
