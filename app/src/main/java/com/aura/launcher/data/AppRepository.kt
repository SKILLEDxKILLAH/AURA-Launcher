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

/** One of an app's shortcuts ("New message", "Navigate home"), shown when you hold its icon. */
data class AppShortcut(val id: String, val label: String, val packageName: String, val user: UserHandle, val info: android.content.pm.ShortcutInfo?)

/** Colors for themed (monochrome) app icons. */
data class IconTheme(val background: Int, val foreground: Int)

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

    /** Colors for themed icons, or null for the apps' own icons. */
    @Volatile private var iconTheme: IconTheme? = null
    private val _iconGeneration = MutableStateFlow(0)
    /** Changes whenever icons need to be drawn again (theme switched). */
    val iconGeneration: StateFlow<Int> = _iconGeneration

    fun setIconTheme(theme: IconTheme?) {
        if (theme == iconTheme) return
        iconTheme = theme
        iconCache.evictAll()
        _iconGeneration.value++
    }
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

    /** For screenshots and tests: show [bitmap] as the icon for an app key or "pkg:<package>". */
    @androidx.annotation.VisibleForTesting
    fun putIcon(key: String, bitmap: ImageBitmap) { iconCache.put(key, bitmap) }

    suspend fun icon(entry: AppEntry): ImageBitmap? = withContext(Dispatchers.IO) {
        iconCache.get(entry.key) ?: run {
            val info = infos[entry.key] ?: return@run null
            runCatching {
                val themed = iconTheme?.let { t -> themedIcon(info.getIcon(0), t)?.let { context.packageManager.getUserBadgedIcon(it, entry.user) } }
                (themed ?: info.getBadgedIcon(0)).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
            }.getOrNull()?.also { iconCache.put(entry.key, it) }
        }
    }

    suspend fun packageIcon(pkg: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "pkg:$pkg"
        iconCache.get(key) ?: runCatching {
            val icon = context.packageManager.getApplicationIcon(pkg)
            (iconTheme?.let { themedIcon(icon, it) } ?: icon).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        }.getOrNull()?.also { iconCache.put(key, it) }
    }

    fun launch(entry: AppEntry, bounds: Rect? = null) {
        try {
            launcherApps.startMainActivity(entry.component, entry.user, bounds, null)
            bump(entry.key)
            record(entry.key)
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't open ${entry.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- App shortcuts --------------------------------------------------------------------------

    /**
     * The app's shortcuts, best first (at most [limit]). Android only gives these to the
     * default home app, so this is empty until AURA is set as home.
     */
    suspend fun shortcuts(entry: AppEntry, limit: Int = 5): List<AppShortcut> = withContext(Dispatchers.IO) {
        if (!runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) return@withContext emptyList()
        // The app's own and its dynamic shortcuts: the same set the Pixel launcher shows.
        val query = LauncherApps.ShortcutQuery()
            .setPackage(entry.packageName)
            .setActivity(entry.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        val found = runCatching { launcherApps.getShortcuts(query, entry.user) }.getOrNull().orEmpty()
        orderShortcuts(found.filter { it.isEnabled }).take(limit).map {
            AppShortcut(it.id, (it.shortLabel ?: it.longLabel ?: it.id).toString(), it.`package`, it.userHandle, it)
        }
    }

    suspend fun shortcutIcon(shortcut: AppShortcut): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "sc:${shortcut.packageName}/${shortcut.id}#${shortcut.user.hashCode()}"
        iconCache.get(key) ?: runCatching {
            val info = shortcut.info ?: return@runCatching null
            launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
                ?.toBitmap(SHORTCUT_PX, SHORTCUT_PX)?.asImageBitmap()
        }.getOrNull()?.also { iconCache.put(key, it) }
    }

    fun startShortcut(shortcut: AppShortcut) {
        runCatching { launcherApps.startShortcut(shortcut.packageName, shortcut.id, null, null, shortcut.user) }
            .onFailure { Toast.makeText(context, "Couldn't open “${shortcut.label}”", Toast.LENGTH_SHORT).show() }
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

    // ---- Launch history (for Suggested / Recent) -------------------------------------------------

    private val logPrefs = context.getSharedPreferences("aura_launch_log", Context.MODE_PRIVATE)
    private val _launchLog = MutableStateFlow(readLog())
    /** AURA's own record of app openings, newest last; capped and kept only on this device. */
    val launchLog: StateFlow<List<LaunchEvent>> = _launchLog

    private fun record(key: String) {
        val next = (_launchLog.value + LaunchEvent(key, System.currentTimeMillis())).takeLast(MAX_LOG)
        _launchLog.value = next
        logPrefs.edit { putString("log", next.joinToString("\n") { "${it.time}\t${it.key}" }) }
    }

    /** Records a launch that happened outside the drawer (e.g. the dock) by package. */
    fun recordPackage(pkg: String) {
        _apps.value.firstOrNull { it.packageName == pkg && !it.isWork }?.let { record(it.key) }
    }

    private fun readLog(): List<LaunchEvent> =
        logPrefs.getString("log", null).orEmpty().lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab).toLongOrNull()?.let { LaunchEvent(line.substring(tab + 1), it) }
        }.toList()

    /** True when Android's usage access is granted to AURA (Settings → Usage access). */
    fun hasUsageAccess(): Boolean = hasUsageAccess(context)

    /**
     * App openings from Android's usage history (last 21 days), mapped to drawer entries, so
     * apps opened from notifications or links count too. Empty without usage access.
     */
    suspend fun systemLaunches(): List<LaunchEvent> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) return@withContext emptyList()
        val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
        val byPackage = _apps.value.filter { !it.isWork }.associateBy { it.packageName }
        val end = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(end - 21L * 86_400_000L, end) }.getOrNull() ?: return@withContext emptyList()
        val out = ArrayList<LaunchEvent>()
        val e = android.app.usage.UsageEvents.Event()
        var lastPkg: String? = null
        var lastTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType != android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = e.packageName
            if (pkg == context.packageName) continue
            // Collapse activity switches inside one app into a single opening.
            if (pkg == lastPkg && e.timeStamp - lastTime < 5 * 60_000) { lastTime = e.timeStamp; continue }
            lastPkg = pkg
            lastTime = e.timeStamp
            byPackage[pkg]?.let { out += LaunchEvent(it.key, e.timeStamp) }
        }
        out
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
        private const val SHORTCUT_PX = 96

        /** The app's own (manifest) shortcuts first, then its dynamic ones, each by the app's rank. */
        fun orderShortcuts(list: List<android.content.pm.ShortcutInfo>) =
            list.sortedWith(compareBy<android.content.pm.ShortcutInfo>({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))

        /**
         * The app's single-color (monochrome) icon on a theme-colored background, the way Pixel
         * draws themed icons. Null when the app doesn't provide one, so its normal icon is used.
         */
        fun themedIcon(icon: android.graphics.drawable.Drawable, theme: IconTheme): android.graphics.drawable.Drawable? {
            val mono = (icon as? android.graphics.drawable.AdaptiveIconDrawable)?.monochrome ?: return null
            val fg = mono.constantState?.newDrawable()?.mutate() ?: mono.mutate()
            fg.setTint(theme.foreground)
            return android.graphics.drawable.AdaptiveIconDrawable(android.graphics.drawable.ColorDrawable(theme.background), fg)
        }
        private const val MAX_LOG = 600

        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(android.app.AppOpsManager::class.java)
            val mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
            return mode == android.app.AppOpsManager.MODE_ALLOWED
        }

        fun letterOf(label: String): String {
            val first = label.trimStart().firstOrNull() ?: return "#"
            val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first().uppercaseChar()
            return if (base in 'A'..'Z') base.toString() else "#"
        }

        private fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    }
}
