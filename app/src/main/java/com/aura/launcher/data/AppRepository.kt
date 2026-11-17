package com.aura.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(
    val key: String,
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val subtitle: String,
    val letter: String,
    val isWork: Boolean,
)

/**
 * Source of truth for launchable apps. Uses the LauncherApps service so work-profile apps,
 * badges and live package changes are handled the same way the system launcher does.
 */
class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val infos = ConcurrentHashMap<String, LauncherActivityInfo>()
    private val iconCache = LruCache<String, ImageBitmap>(400)
    private val countsPrefs = context.getSharedPreferences("aura_launch_counts", Context.MODE_PRIVATE)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _launchCounts = MutableStateFlow(readCounts())
    val launchCounts: StateFlow<Map<String, Int>> = _launchCounts

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        scope.launch { load() }
    }

    private fun load() {
        val self = context.packageName
        val primary = Process.myUserHandle()
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val entries = launcherApps.profiles.flatMap { user ->
            runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList()).map { user to it }
        }.filter { (_, info) -> info.componentName.packageName != self }
            .map { (user, info) ->
                val key = info.componentName.flattenToShortString() + "#" + userManager.getSerialNumberForUser(user)
                infos[key] = info
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName }
                AppEntry(
                    key = key,
                    label = label,
                    packageName = info.componentName.packageName,
                    component = info.componentName,
                    user = user,
                    subtitle = AppDescriptions.subtitleFor(info.componentName.packageName, info.applicationInfo.category),
                    letter = letterOf(label),
                    isWork = user != primary,
                )
            }
            .sortedWith(compareBy<AppEntry> { if (it.letter == "#") 1 else 0 }.thenBy(collator) { it.label })
        _apps.value = entries
    }

    fun cachedIcon(key: String): ImageBitmap? = iconCache.get(key)

    suspend fun icon(entry: AppEntry): ImageBitmap? = withContext(Dispatchers.IO) {
        iconCache.get(entry.key) ?: run {
            val info = infos[entry.key] ?: return@run null
            runCatching { info.getBadgedIcon(0).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }
                .getOrNull()?.also { iconCache.put(entry.key, it) }
        }
    }

    suspend fun packageIcon(pkg: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "pkg:$pkg"
        iconCache.get(key) ?: runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        }.getOrNull()?.also { iconCache.put(key, it) }
    }

    fun launch(entry: AppEntry, bounds: Rect? = null) {
        try {
            launcherApps.startMainActivity(entry.component, entry.user, bounds, null)
            bump(entry.key)
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't open ${entry.label}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openAppInfo(entry: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null) }
    }

    fun uninstall(entry: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, "package:${entry.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_USER, entry.user)
        runCatching { context.startActivity(intent) }
    }

    /** Fuzzy best match used by search and by the Gemini agent's open_app tool. */
    fun findApps(query: String, limit: Int = 8): List<AppEntry> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val counts = _launchCounts.value
        return _apps.value.mapNotNull { app ->
            val l = normalize(app.label)
            val score = when {
                l == q -> 100
                l.startsWith(q) -> 80
                l.split(' ').any { it.startsWith(q) } -> 60
                l.contains(q) -> 40
                app.packageName.contains(q.replace(" ", ""), ignoreCase = true) -> 20
                else -> 0
            }
            if (score == 0) null else app to score * 1000 + (counts[app.key] ?: 0)
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun bump(key: String) {
        val next = (_launchCounts.value[key] ?: 0) + 1
        _launchCounts.value = _launchCounts.value + (key to next)
        countsPrefs.edit { putInt(key, next) }
    }

    private fun readCounts(): Map<String, Int> =
        countsPrefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()

    companion object {
        const val ICON_PX = 192

        fun letterOf(label: String): String {
            val first = label.trimStart().firstOrNull() ?: return "#"
            val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first().uppercaseChar()
            return if (base in 'A'..'Z') base.toString() else "#"
        }

        private fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    }
}
