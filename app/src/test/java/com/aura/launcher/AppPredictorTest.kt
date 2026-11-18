package com.aura.launcher

import com.aura.launcher.data.AppPredictor
import com.aura.launcher.data.LaunchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AppPredictorTest {
    private val zone = ZoneId.of("America/Chicago")
    // A Wednesday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)
    private fun ev(key: String, t: ZonedDateTime) = LaunchEvent(key, t.toInstant().toEpochMilli())
    private val all = setOf("news", "spotify", "maps", "slack", "games")

    @Test
    fun timeOfDayHabitsWin() {
        // Every weekday of the past two weeks: News at 7 AM, Spotify at 6 PM.
        val events = (4..17).filter { it !in setOf(7, 8, 14, 15) }.flatMap { d ->
            listOf(ev("news", at(d, 7, 5)), ev("spotify", at(d, 18, 10)))
        }
        val morning = AppPredictor.suggest(events, at(18, 7, 30), all)
        val evening = AppPredictor.suggest(events, at(18, 18, 30), all)
        assertEquals("news", morning.first())
        assertEquals("spotify", evening.first())
    }

    @Test
    fun recentUseOutweighsOldHabits() {
        // Maps was used a lot a month ago; Slack a few times this week at the same hour.
        val events = (1..10).map { ev("maps", at(1, 9).minusDays(25L + it)) } +
            (15..17).map { ev("slack", at(it, 9)) }
        assertEquals("slack", AppPredictor.suggest(events, at(18, 9), all).first())
    }

    @Test
    fun weekendsAreKeptSeparateFromWeekdays() {
        // Games every weekend afternoon; Slack every weekday afternoon.
        val events = listOf(7, 8, 14, 15).map { ev("games", at(it, 14)) } +
            listOf(9, 10, 11, 12, 13, 16, 17).map { ev("slack", at(it, 14)) }
        val saturday = ZonedDateTime.of(2026, 11, 21, 14, 0, 0, 0, zone)
        assertEquals("games", AppPredictor.suggest(events, saturday, all).first())
        assertEquals("slack", AppPredictor.suggest(events, at(18, 14), all).first())
    }

    @Test
    fun midnightWrapsAround() {
        val events = (10..16).map { ev("games", at(it, 23, 50)) } + (10..16).map { ev("news", at(it, 12)) }
        assertEquals("games", AppPredictor.suggest(events, at(18, 0, 15), all).first())
    }

    @Test
    fun ignoresUninstalledAndFutureAndKeepsLimit() {
        val now = at(18, 12)
        val events = listOf(ev("deleted-app", now.minusHours(1)), ev("maps", now.plusHours(1))) +
            all.map { ev(it, now.minusHours(2)) }
        val out = AppPredictor.suggest(events, now, all, limit = 3)
        assertEquals(3, out.size)
        assertTrue("deleted-app" !in out)
    }

    @Test
    fun recentIsNewestFirstAndDistinct() {
        val now = at(18, 12)
        val events = listOf(ev("maps", now.minusHours(3)), ev("news", now.minusHours(2)), ev("maps", now.minusHours(1)), ev("gone", now))
        assertEquals(listOf("maps", "news"), AppPredictor.recent(events, all))
    }
}
