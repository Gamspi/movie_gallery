package dev.gamspi.videogallery.player

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Player heading: «Название (год)» from movie meta, else the release title. */
fun playerHeading(movieTitle: String, movieYear: Int?, fallback: String): String {
    if (movieTitle.isBlank()) return fallback
    return if (movieYear != null && movieYear > 0) "$movieTitle ($movieYear)" else movieTitle
}

/** Wall-clock end of playback at 1x speed; null while the duration is unknown. */
fun playbackEndAt(nowMs: Long, positionMs: Long, durationMs: Long): Long? {
    if (durationMs <= 0) return null
    return nowMs + (durationMs - positionMs.coerceAtLeast(0)).coerceAtLeast(0)
}

/** HH:mm of an epoch time in [zone]. */
fun formatClock(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    CLOCK_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))
