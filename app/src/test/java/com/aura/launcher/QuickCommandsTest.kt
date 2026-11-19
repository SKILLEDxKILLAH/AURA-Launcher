package com.aura.launcher

import com.aura.launcher.gemini.QuickCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

/** AURA's own command understanding, used when Gemini runs on the phone. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [37])
class QuickCommandsTest {
    private val apps = setOf("maps", "spotify", "google maps", "camera", "messages")
    private fun parse(s: String, now: LocalTime = LocalTime.of(9, 0)) = QuickCommands.parse(s, isApp = { it in apps }, now = now)

    private fun check(input: String, tool: String, vararg args: Pair<String, Any>) {
        val c = parse(input) ?: throw AssertionError("“$input” wasn't understood")
        assertEquals("tool for “$input”", tool, c.tool)
        args.forEach { (k, v) -> assertEquals("$k for “$input”", v, c.args.get(k)) }
    }

    @Test fun flashlight() {
        check("Turn on the flashlight", "set_flashlight", "on" to true)
        check("flashlight off", "set_flashlight", "on" to false)
        check("Can you switch the torch off please", "set_flashlight", "on" to false)
        check("flashlight", "set_flashlight", "on" to true)
    }

    @Test fun timers() {
        check("Set a timer for 10 minutes", "set_timer", "seconds" to 600)
        check("timer for 1 hour and 30 minutes", "set_timer", "seconds" to 5400)
        check("5 minute timer", "set_timer", "seconds" to 300)
        check("set a timer for ten seconds", "set_timer", "seconds" to 10)
        check("timer for half an hour", "set_timer", "seconds" to 1800)
    }

    @Test fun alarms() {
        check("Wake me up at 6:30", "set_alarm", "hour" to 6, "minute" to 30)
        check("set an alarm for 7 pm", "set_alarm", "hour" to 19, "minute" to 0)
        check("alarm at 12 am", "set_alarm", "hour" to 0, "minute" to 0)
        check("set an alarm for 21:15", "set_alarm", "hour" to 21, "minute" to 15)
        // No am/pm: the next time that hour comes round (it's 9 AM now, so 8 means 8 PM).
        assertEquals(20, parse("set an alarm for 8")?.args?.getInt("hour") ?: parse("alarm for 8:00")?.args?.getInt("hour"))
    }

    @Test fun people() {
        check("Call Mom", "call_contact", "contact" to "mom")
        val text = parse("Text Sarah saying I'm running 10 minutes late")!!
        assertEquals("send_message", text.tool)
        assertEquals("Sarah", text.args.getString("contact"))
        assertEquals("I'm running 10 minutes late", text.args.getString("message"))
    }

    @Test fun placesMediaSearchApps() {
        check("Navigate to the airport", "navigate", "destination" to "the airport", "mode" to "driving")
        check("take me home", "navigate", "destination" to "home")
        check("play Taylor Swift on Spotify", "play_media", "query" to "taylor swift")
        check("search for pizza near me", "web_search", "query" to "pizza near me")
        check("Open Spotify", "open_app", "name" to "spotify")
        check("launch google maps", "open_app", "name" to "google maps")
        check("take a selfie", "open_camera", "mode" to "photo")
    }

    @Test fun settingsAndStatus() {
        check("turn on bluetooth", "open_settings", "page" to "bluetooth")
        check("wifi settings", "open_settings", "page" to "wifi")
        check("turn off do not disturb", "open_settings", "page" to "dnd")
        check("how much battery do I have", "get_device_status")
        check("battery", "get_device_status")
    }

    @Test fun questionsAreLeftForGemini() {
        listOf(
            "What's the capital of France?",
            "Open the pod bay doors",          // not an installed app
            "Tell me a joke",
            "How do I make pancakes",
            "What time is it in Tokyo",
            "Write a poem about the sea",
            "Who called the meeting",
        ).forEach { assertNull("“$it” should go to Gemini", parse(it)) }
    }
}
