package com.aura.launcher.gemini

import org.json.JSONObject
import java.time.LocalTime
import java.util.Locale

/** A phone action AURA recognized by itself, ready for [PhoneTools.execute]. */
data class QuickCommand(val tool: String, val args: JSONObject)

/**
 * Understands the everyday things people ask a phone to do ("flashlight on", "timer for 10
 * minutes", "open Spotify", "call Mom"...) without any AI. The on-device Gemini Nano mode
 * uses this for actions, because Nano can't operate the phone on most devices yet.
 *
 * Deliberately strict: anything that isn't clearly a command returns null and goes to Gemini.
 */
object QuickCommands {

    /** [isApp] says whether a name matches an installed app, so "open the door" isn't treated as an app. */
    fun parse(input: String, isApp: (String) -> Boolean = { true }, now: LocalTime = LocalTime.now()): QuickCommand? {
        val t = clean(input)
        if (t.isEmpty()) return null
        return flashlight(t) ?: timer(t) ?: alarm(t, now) ?: settings(t) ?: status(t) ?: camera(t)
            ?: message(clean(input, keepCase = true)) ?: call(t) ?: navigate(t) ?: search(t) ?: play(t) ?: open(t, isApp)
    }

    private val politeness = Regex(
        "^(hey gemini|ok gemini|hey|ok|okay|please|can you|could you|would you|will you|i want to|i'd like to|go ahead and|just),?\\s+",
        RegexOption.IGNORE_CASE,
    )

    internal fun clean(input: String, keepCase: Boolean = false): String {
        var t = input.trim().replace(Regex("[.!?]+$"), "").replace('’', '\'').trim()
        if (!keepCase) t = t.lowercase(Locale.ROOT)
        repeat(3) { t = t.replace(politeness, "") }
        return t.removeSuffix(" please").removeSuffix(", please").trim()
    }

    private fun cmd(tool: String, vararg args: Pair<String, Any>) = QuickCommand(tool, JSONObject().apply { args.forEach { (k, v) -> put(k, v) } })

    // ---- Flashlight ----
    private val torch = "(?:the )?(?:flash ?light|torch)"
    private fun flashlight(t: String): QuickCommand? {
        Regex("^(?:turn|switch|put) (on|off) $torch$").find(t)?.let { return cmd("set_flashlight", "on" to (it.groupValues[1] == "on")) }
        Regex("^(?:turn|switch|put) $torch (on|off)$").find(t)?.let { return cmd("set_flashlight", "on" to (it.groupValues[1] == "on")) }
        Regex("^$torch(?: (on|off))?$").find(t)?.let { return cmd("set_flashlight", "on" to (it.groupValues[1] != "off")) }
        return null
    }

    // ---- Timer ----
    private val words = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty five" to 45, "half an" to 0,
    )
    private const val NUM = "(\\d+(?:\\.\\d+)?|a|an|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty five|half an)"
    private const val UNIT = "(seconds?|secs?|minutes?|mins?|hours?|hrs?)"

    private fun seconds(n: String, unit: String): Int? {
        val value = if (n == "half an") 0.5 else n.toDoubleOrNull() ?: words[n]?.toDouble() ?: return null
        val per = when {
            unit.startsWith("s") -> 1
            unit.startsWith("m") -> 60
            else -> 3600
        }
        return (value * per).toInt().takeIf { it > 0 }
    }

    private fun timer(t: String): QuickCommand? {
        val forms = listOf(
            Regex("^(?:set |start )?(?:a )?timer (?:for )?$NUM $UNIT(?: and $NUM $UNIT)?$"),
            Regex("^(?:set |start )?(?:a |an )?$NUM[ -]$UNIT timer$"),
            Regex("^(?:remind me in|time) $NUM $UNIT$"),
        )
        for (f in forms) {
            val m = f.find(t) ?: continue
            val g = m.groupValues
            var total = seconds(g[1], g[2]) ?: continue
            if (g.size > 4 && g[3].isNotEmpty()) total += seconds(g[3], g[4]) ?: 0
            return cmd("set_timer", "seconds" to total)
        }
        return null
    }

    // ---- Alarm ----
    private fun alarm(t: String, now: LocalTime): QuickCommand? {
        val m = Regex("^(?:set )?(?:an )?(?:alarm|wake me up|wake me)(?: up)? (?:for|at) (\\d{1,2})(?::(\\d{2}))? ?(am|pm|a\\.m|p\\.m|in the morning|tonight|in the evening)?$").find(t)
            ?: Regex("^(?:set )?(?:an )?alarm (?:for )?(\\d{1,2})(?::(\\d{2}))? ?(am|pm|a\\.m|p\\.m)$").find(t)
            ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (hour > 23 || minute > 59) return null
        val half = m.groupValues[3]
        when {
            half.startsWith("p") || half == "tonight" || half == "in the evening" -> if (hour < 12) hour += 12
            half.startsWith("a") || half == "in the morning" -> if (hour == 12) hour = 0
            // "Wake me up at 6:30" means the morning.
            t.startsWith("wake") -> Unit
            hour in 1..11 -> {
                // No am/pm: the next time that hour comes round.
                val morning = LocalTime.of(hour, minute)
                if (morning.isBefore(now) && LocalTime.of(hour + 12, minute).isAfter(now)) hour += 12
            }
        }
        return cmd("set_alarm", "hour" to hour, "minute" to minute)
    }

    // ---- Settings & quick panels ----
    private val settingWords = linkedMapOf(
        "wi-?fi|wifi|wireless" to "wifi", "internet|mobile data|data" to "internet", "bluetooth" to "bluetooth",
        "airplane mode|flight mode" to "airplane", "do not disturb|dnd" to "dnd", "location|gps" to "location",
        "nfc" to "nfc", "night light|eye comfort" to "night_light", "volume|sound" to "volume", "brightness|display" to "display",
        "battery saver|battery settings" to "battery", "wallpaper" to "wallpaper",
    )

    private fun settings(t: String): QuickCommand? {
        for ((pattern, id) in settingWords) {
            val p = "(?:the )?(?:$pattern)"
            if (Regex("^(?:turn|switch) (?:on|off) $p$").matches(t) || Regex("^(?:turn|switch) $p (?:on|off)$").matches(t) ||
                Regex("^(?:open )?$p settings$").matches(t) || Regex("^(?:open|show) $p$").matches(t)
            ) return cmd("open_settings", "page" to id)
        }
        if (t == "settings" || t == "open settings") return cmd("open_settings", "page" to "all")
        return null
    }

    private fun status(t: String): QuickCommand? =
        if (Regex("^(?:what's |what is |how much )?(?:my )?battery(?: level| left| percentage)?$").matches(t) ||
            Regex("^how much (?:battery|charge) (?:do i have|is left)(?: left)?$").matches(t)
        ) cmd("get_device_status") else null

    private fun camera(t: String): QuickCommand? = when {
        Regex("^(?:record|take) a video$").matches(t) || Regex("^open (?:the )?video camera$").matches(t) -> cmd("open_camera", "mode" to "video")
        Regex("^(?:open (?:the )?camera|take a (?:photo|picture|pic|selfie))$").matches(t) -> cmd("open_camera", "mode" to "photo")
        else -> null
    }

    // ---- People ----
    private fun message(t: String): QuickCommand? {
        val m = Regex("^(?:text|message|send (?:a )?(?:text|message) to|tell) (.+?)(?: saying| that|:) (.+)$", RegexOption.IGNORE_CASE).find(t)
            ?: return null
        return cmd("send_message", "contact" to m.groupValues[1].trim(), "message" to restoreCase(m.groupValues[2].trim()))
    }

    private fun call(t: String): QuickCommand? {
        val m = Regex("^(?:call|phone|dial|ring) (.+?)(?: on speaker| now)?$").find(t) ?: return null
        val who = m.groupValues[1].trim()
        if (who.isEmpty() || who in setOf("me", "it", "them back")) return null
        return cmd("call_contact", "contact" to who)
    }

    // ---- Places, search, media ----
    private fun navigate(t: String): QuickCommand? {
        val m = Regex("^(?:navigate|directions|get directions|take me|drive|walk me|give me directions) (?:me )?(?:to|home)\\s*(.*)$").find(t) ?: return null
        val dest = m.groupValues[1].trim().ifEmpty { if (t.endsWith("home")) "home" else return null }
        val mode = if (t.startsWith("walk")) "walking" else "driving"
        return cmd("navigate", "destination" to dest, "mode" to mode)
    }

    private fun search(t: String): QuickCommand? {
        val m = Regex("^(?:search(?: the web)?(?: for)?|google|look up) (.+)$").find(t) ?: return null
        return cmd("web_search", "query" to m.groupValues[1])
    }

    private fun play(t: String): QuickCommand? {
        val m = Regex("^play (.+?)(?: on (?:spotify|youtube music|youtube))?$").find(t) ?: return null
        return cmd("play_media", "query" to m.groupValues[1])
    }

    private fun open(t: String, isApp: (String) -> Boolean): QuickCommand? {
        val m = Regex("^(?:open|launch|start|go to|show me) (?:the |my )?(.+?)(?: app)?$").find(t) ?: return null
        val name = m.groupValues[1].trim()
        return if (name.isNotEmpty() && isApp(name)) cmd("open_app", "name" to name) else null
    }

    /** Messages keep sentence case even though matching is done in lower case. */
    private fun restoreCase(s: String) = s.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
