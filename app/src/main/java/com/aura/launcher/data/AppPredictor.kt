package com.aura.launcher.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/** One app opening: which app ([key]) and when ([time], epoch millis). */
data class LaunchEvent(val key: String, val time: Long)

/**
 * Predicts which apps you're likely to open next from your own history, entirely on-device.
 *
 * Each past launch votes for its app with a weight that is higher when it was
 *  - recent (half-life of 7 days),
 *  - at a similar time of day (bell curve, ±1.5 h; wraps around midnight),
 *  - on the same kind of day (weekday vs weekend).
 * Apps opened in the last few minutes get a small boost so you can jump back to them.
 */
object AppPredictor {
    private const val HALF_LIFE_DAYS = 7.0
    private const val HOUR_SIGMA = 1.5
    private const val MAX_AGE_DAYS = 45.0

    fun suggest(
        events: List<LaunchEvent>,
        now: ZonedDateTime,
        candidates: Set<String>,
        limit: Int = 5,
    ): List<String> {
        val scores = HashMap<String, Double>()
        val nowMs = now.toInstant().toEpochMilli()
        val nowHour = now.hour + now.minute / 60.0
        val nowWeekend = now.dayOfWeek.isWeekend()
        for (e in events) {
            if (e.key !in candidates || e.time > nowMs) continue
            val ageDays = (nowMs - e.time) / 86_400_000.0
            if (ageDays > MAX_AGE_DAYS) continue
            val t = Instant.ofEpochMilli(e.time).atZone(now.zone)
            val recency = 0.5.pow(ageDays / HALF_LIFE_DAYS)
            val dh = hourDistance(nowHour, t.hour + t.minute / 60.0)
            val timeOfDay = 0.3 + 0.7 * exp(-(dh * dh) / (2 * HOUR_SIGMA * HOUR_SIGMA))
            val dayKind = if (t.dayOfWeek.isWeekend() == nowWeekend) 1.0 else 0.35
            val justUsed = if (nowMs - e.time < 15 * 60_000) 0.8 else 0.0
            scores.merge(e.key, recency * timeOfDay * dayKind + justUsed, Double::plus)
        }
        return scores.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** Most recently opened distinct apps, newest first. */
    fun recent(events: List<LaunchEvent>, candidates: Set<String>, limit: Int = 5): List<String> =
        events.asSequence().filter { it.key in candidates }.sortedByDescending { it.time }
            .map { it.key }.distinct().take(limit).toList()

    private fun hourDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 24.0
        return min(d, 24.0 - d)
    }

    private fun DayOfWeek.isWeekend() = this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

    fun now(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())
}
