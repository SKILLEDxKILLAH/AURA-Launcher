package com.aura.launcher.gemini

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.net.Uri
import androidx.core.net.toUri
import com.aura.launcher.ActivityBridge
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.SettingShortcuts
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ToolResult(val ok: Boolean, val label: String, val detail: String)

/**
 * The phone capabilities Gemini can use from inside AURA. Every tool maps to a public Android
 * intent or API; anything that sends data to another person (calls, texts) is either gated by a
 * runtime permission the user grants, or opens a pre-filled composer the user confirms.
 */
class PhoneTools(private val context: Context, private val repo: AppRepository) {

    fun declarations(): JSONArray = JSONArray().apply {
        put(fn("open_app", "Open an installed app by name.", props("name" to str("App name, e.g. 'Maps' or 'Spotify'")), "name"))
        put(fn(
            "open_settings", "Open a system settings page or quick panel.",
            props("page" to enumStr("Which settings page", SettingShortcuts.all.map { it.id })), "page",
        ))
        put(fn(
            "call_contact", "Place a phone call to a contact name or phone number.",
            props("contact" to str("Contact name or phone number")), "contact",
        ))
        put(fn(
            "send_message", "Open an SMS/RCS composer to a contact with the message pre-filled; the user taps send.",
            props("contact" to str("Contact name or phone number"), "message" to str("Message text")), "contact", "message",
        ))
        put(fn(
            "set_alarm", "Set an alarm in the clock app.",
            props("hour" to int("Hour 0-23"), "minute" to int("Minute 0-59"), "label" to str("Optional label")), "hour", "minute",
        ))
        put(fn(
            "set_timer", "Start a countdown timer.",
            props("seconds" to int("Duration in seconds"), "label" to str("Optional label")), "seconds",
        ))
        put(fn("set_flashlight", "Turn the flashlight on or off.", props("on" to bool("true to turn on")), "on"))
        put(fn(
            "navigate", "Start turn-by-turn navigation to a destination.",
            props("destination" to str("Place or address"), "mode" to enumStr("Travel mode", listOf("driving", "walking", "bicycling", "transit"))),
            "destination",
        ))
        put(fn("web_search", "Search the web.", props("query" to str("Search query")), "query"))
        put(fn("play_media", "Play music, a podcast or a video by search.", props("query" to str("What to play")), "query"))
        put(fn(
            "create_calendar_event", "Open a new calendar event pre-filled for the user to save.",
            props(
                "title" to str("Event title"),
                "start" to str("Local start time, ISO-8601 like 2026-11-18T15:00"),
                "end" to str("Optional local end time, ISO-8601"),
                "location" to str("Optional location"),
            ),
            "title", "start",
        ))
        put(fn("open_camera", "Open the camera.", props("mode" to enumStr("Camera mode", listOf("photo", "video"))), "mode"))
        put(fn("open_url", "Open a web page.", props("url" to str("Absolute https URL")), "url"))
        put(fn("get_device_status", "Read battery level, charging state, connectivity, ringer mode and local time.", JSONObject()))
    }

    suspend fun execute(name: String, args: JSONObject): ToolResult = try {
        when (name) {
            "open_app" -> openApp(args.optString("name"))
            "open_settings" -> openSettings(args.optString("page"))
            "call_contact" -> call(args.optString("contact"))
            "send_message" -> message(args.optString("contact"), args.optString("message"))
            "set_alarm" -> setAlarm(args.optInt("hour"), args.optInt("minute"), args.optString("label"))
            "set_timer" -> setTimer(args.optInt("seconds"), args.optString("label"))
            "set_flashlight" -> flashlight(args.optBoolean("on", true))
            "navigate" -> navigate(args.optString("destination"), args.optString("mode", "driving"))
            "web_search" -> start(
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, args.optString("query")),
                "Searched “${args.optString("query")}”",
            )
            "play_media" -> start(
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(SearchManager.QUERY, args.optString("query"))
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"),
                "Playing “${args.optString("query")}”",
            )
            "create_calendar_event" -> calendar(args)
            "open_camera" -> start(
                Intent(if (args.optString("mode") == "video") MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                "Opened camera",
            )
            "open_url" -> {
                val url = args.optString("url")
                if (!url.startsWith("https://") && !url.startsWith("http://")) ToolResult(false, "Invalid link", "Only http(s) URLs are allowed")
                else start(Intent(Intent.ACTION_VIEW, url.toUri()), "Opened ${url.toUri().host ?: "link"}")
            }
            "get_device_status" -> status()
            else -> ToolResult(false, "Unknown action", "No tool named $name")
        }
    } catch (e: Exception) {
        ToolResult(false, "Action failed", e.message ?: e.javaClass.simpleName)
    }

    private fun start(intent: Intent, label: String): ToolResult =
        if (ActivityBridge.start(context, intent)) ToolResult(true, label, "done")
        else ToolResult(false, "No app can do that", "No activity handles ${intent.action}")

    private fun openApp(name: String): ToolResult {
        val match = repo.findApps(name, 3)
        val app = match.firstOrNull() ?: return ToolResult(false, "No app named “$name”", "No installed app matches \"$name\"")
        repo.launch(app)
        return ToolResult(true, "Opened ${app.label}", "Opened ${app.label}")
    }

    private fun openSettings(page: String): ToolResult {
        val s = SettingShortcuts.byId(page) ?: SettingShortcuts.byId("all")!!
        if (ActivityBridge.start(context, SettingShortcuts.intentFor(s))) return ToolResult(true, "Opened ${s.title}", "done")
        return start(SettingShortcuts.intentFor(SettingShortcuts.byId("all")!!), "Opened Settings")
    }

    private suspend fun call(contact: String): ToolResult {
        val (name, number) = resolveNumber(contact) ?: return ToolResult(false, "Couldn't find “$contact”", "No contact or number matches")
        val canCall = ActivityBridge.ensurePermission(context, Manifest.permission.CALL_PHONE)
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, "tel:${Uri.encode(number)}".toUri())
        return start(intent, if (canCall) "Calling $name" else "Dialer ready for $name")
    }

    private suspend fun message(contact: String, body: String): ToolResult {
        val (name, number) = resolveNumber(contact) ?: return ToolResult(false, "Couldn't find “$contact”", "No contact or number matches")
        val intent = Intent(Intent.ACTION_SENDTO, "smsto:${Uri.encode(number)}".toUri()).putExtra("sms_body", body)
        return start(intent, "Message to $name ready — tap send")
    }

    private suspend fun resolveNumber(query: String): Pair<String, String>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        if (q.count { it.isDigit() } >= 3 && q.all { it.isDigit() || it in "+-() ." }) return q to q
        if (!ActivityBridge.ensurePermission(context, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q))
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        )
        context.contentResolver.query(uri, projection, null, null, "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC")?.use { c ->
            if (c.moveToFirst()) return c.getString(0) to c.getString(1)
        }
        return null
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): ToolResult = start(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "AURA" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        "Alarm set for %d:%02d".format(hour, minute),
    )

    private fun setTimer(seconds: Int, label: String): ToolResult = start(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_400))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "AURA" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        "Timer started · ${formatDuration(seconds)}",
    )

    private fun flashlight(on: Boolean): ToolResult {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult(false, "No flashlight", "This device has no flash")
        cm.setTorchMode(id, on)
        return ToolResult(true, if (on) "Flashlight on" else "Flashlight off", "done")
    }

    private fun navigate(destination: String, mode: String): ToolResult {
        val m = when (mode) { "walking" -> "w"; "bicycling" -> "b"; "transit" -> "r"; else -> "d" }
        val nav = Intent(Intent.ACTION_VIEW, "google.navigation:q=${Uri.encode(destination)}&mode=$m".toUri())
        if (ActivityBridge.start(context, nav)) return ToolResult(true, "Navigating to $destination", "done")
        return start(Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(destination)}".toUri()), "Showing $destination")
    }

    private fun calendar(args: JSONObject): ToolResult {
        val zone = ZoneId.systemDefault()
        fun millis(s: String): Long? = runCatching {
            LocalDateTime.parse(s.removeSuffix("Z").take(19)).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
        val start = millis(args.optString("start")) ?: return ToolResult(false, "Bad time", "Unparseable start time")
        val end = millis(args.optString("end")) ?: (start + 60 * 60 * 1000)
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, args.optString("title"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, args.optString("location"))
        return start(intent, "Event draft: ${args.optString("title")}")
    }

    private fun status(): ToolResult {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "connected"
        }
        val ringer = when (context.getSystemService(AudioManager::class.java).ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a"))
        val detail = "battery=$level%, charging=$charging, network=$net, ringer=$ringer, time=$now"
        return ToolResult(true, "Checked device status", detail)
    }

    fun hasPermission(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // --- JSON schema helpers ---------------------------------------------------------------
    private fun fn(name: String, description: String, properties: JSONObject, vararg required: String) =
        JSONObject().put("name", name).put("description", description).put(
            "parameters",
            JSONObject().put("type", "OBJECT").put("properties", properties).apply {
                if (required.isNotEmpty()) put("required", JSONArray(required.toList()))
            },
        )

    private fun props(vararg p: Pair<String, JSONObject>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun str(d: String) = JSONObject().put("type", "STRING").put("description", d)
    private fun int(d: String) = JSONObject().put("type", "INTEGER").put("description", d)
    private fun bool(d: String) = JSONObject().put("type", "BOOLEAN").put("description", d)
    private fun enumStr(d: String, values: List<String>) = str(d).put("enum", JSONArray(values))

    companion object {
        fun formatDuration(seconds: Int): String {
            val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
            return listOfNotNull(h.takeIf { it > 0 }?.let { "${it}h" }, m.takeIf { it > 0 }?.let { "${it}m" }, s.takeIf { it > 0 }?.let { "${it}s" })
                .joinToString(" ").ifEmpty { "0s" }
        }
    }
}
