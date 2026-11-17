package com.aura.launcher.data

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import androidx.core.net.toUri

/** A settings destination reachable from search, the "Change a setting" chip, and Gemini. */
data class SettingShortcut(val id: String, val title: String, val keywords: List<String>, val action: String)

object SettingShortcuts {
    val all = listOf(
        SettingShortcut("internet", "Internet & Wi‑Fi", listOf("wifi", "wi-fi", "internet", "network", "mobile data"), Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
        SettingShortcut("wifi", "Wi‑Fi settings", listOf("wifi", "wi-fi", "wireless"), Settings.ACTION_WIFI_SETTINGS),
        SettingShortcut("bluetooth", "Bluetooth", listOf("bluetooth", "headphones", "pair"), Settings.ACTION_BLUETOOTH_SETTINGS),
        SettingShortcut("volume", "Volume", listOf("volume", "sound", "ringer", "mute"), Settings.Panel.ACTION_VOLUME),
        SettingShortcut("sound", "Sound & vibration", listOf("sound", "vibration", "ringtone"), Settings.ACTION_SOUND_SETTINGS),
        SettingShortcut("display", "Display & brightness", listOf("display", "brightness", "dark theme", "font size"), Settings.ACTION_DISPLAY_SETTINGS),
        SettingShortcut("night_light", "Night Light", listOf("night light", "blue light", "eye comfort"), "android.settings.NIGHT_DISPLAY_SETTINGS"),
        SettingShortcut("battery", "Battery", listOf("battery", "power", "battery saver"), Intent.ACTION_POWER_USAGE_SUMMARY),
        SettingShortcut("nfc", "NFC", listOf("nfc", "tap to pay", "contactless"), Settings.Panel.ACTION_NFC),
        SettingShortcut("airplane", "Airplane mode", listOf("airplane", "flight mode"), Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        SettingShortcut("location", "Location", listOf("location", "gps"), Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        SettingShortcut("dnd", "Do Not Disturb & modes", listOf("do not disturb", "dnd", "focus", "modes", "silence"), "android.settings.ZEN_MODE_SETTINGS"),
        SettingShortcut("apps", "Apps", listOf("apps", "applications", "default apps"), Settings.ACTION_APPLICATION_SETTINGS),
        SettingShortcut("storage", "Storage", listOf("storage", "space", "free up"), Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        SettingShortcut("security", "Security", listOf("security", "lock screen", "fingerprint", "face unlock"), Settings.ACTION_SECURITY_SETTINGS),
        SettingShortcut("privacy", "Privacy", listOf("privacy", "permissions"), Settings.ACTION_PRIVACY_SETTINGS),
        SettingShortcut("accessibility", "Accessibility", listOf("accessibility", "talkback", "magnify"), Settings.ACTION_ACCESSIBILITY_SETTINGS),
        SettingShortcut("date", "Date & time", listOf("date", "time", "time zone"), Settings.ACTION_DATE_SETTINGS),
        SettingShortcut("assistant", "Default digital assistant", listOf("assistant", "gemini", "voice"), Settings.ACTION_VOICE_INPUT_SETTINGS),
        SettingShortcut("home", "Default home app", listOf("launcher", "home app", "default home"), Settings.ACTION_HOME_SETTINGS),
        SettingShortcut("wallpaper", "Wallpaper & style", listOf("wallpaper", "style", "theme", "colors"), Intent.ACTION_SET_WALLPAPER),
        SettingShortcut("all", "All settings", listOf("settings", "preferences"), Settings.ACTION_SETTINGS),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    fun search(query: String): List<SettingShortcut> {
        val q = query.lowercase().trim()
        if (q.length < 2) return emptyList()
        return all.filter { s -> s.title.lowercase().contains(q) || s.keywords.any { it.contains(q) || q.contains(it) } }.take(4)
    }

    fun intentFor(shortcut: SettingShortcut) = Intent(shortcut.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Default apps for the dock, resolved from the device's actual role holders. */
data class DockItem(val label: String, val packageName: String, val intent: Intent)

object DockResolver {
    fun resolve(context: Context): List<DockItem> {
        val pm = context.packageManager
        fun pkgFor(intent: Intent): String? =
            pm.resolveActivity(intent, 0)?.activityInfo?.packageName?.takeIf { it != "android" }

        fun installed(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess

        fun launchItem(label: String, pkg: String?): DockItem? {
            pkg ?: return null
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return null
            return DockItem(label, pkg, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        val phone = launchItem("Phone", pkgFor(Intent(Intent.ACTION_DIAL)))
        val messages = launchItem("Messages", Telephony.Sms.getDefaultSmsPackage(context))
        val browserPkg = pkgFor(Intent(Intent.ACTION_VIEW, "https://www.google.com".toUri()))
            ?: "com.android.chrome".takeIf(::installed)
        val browser = launchItem("Browser", browserPkg)
        val photos = launchItem(
            "Photos",
            "com.google.android.apps.photos".takeIf(::installed)
                ?: pkgFor(Intent(Intent.ACTION_VIEW).setType("image/*")),
        )
        val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val camera = pkgFor(cameraIntent)?.let { DockItem("Camera", it, cameraIntent) }

        return listOfNotNull(phone, messages, browser, photos, camera)
    }

    fun clockIntent() = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
