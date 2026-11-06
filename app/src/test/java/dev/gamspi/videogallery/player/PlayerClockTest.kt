package dev.gamspi.videogallery.player

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerClockTest {
    @Test
    fun heading_titleWithYear_orFallback() {
        assertEquals("Матрица (1999)", playerHeading("Матрица", 1999, "Release.1080p"))
        assertEquals("Матрица", playerHeading("Матрица", null, "Release.1080p"))
        assertEquals("Release.1080p", playerHeading(" ", 1999, "Release.1080p"))
    }

    @Test
    fun endAt_nowPlusRemaining() {
        assertEquals(1_000L + 70_000L, playbackEndAt(1_000L, 30_000L, 100_000L))
    }

    @Test
    fun endAt_unknownDuration_null() {
        assertNull(playbackEndAt(1_000L, 30_000L, 0L))
        assertNull(playbackEndAt(1_000L, 30_000L, -1L))
    }

    @Test
    fun endAt_positionPastEndOrNegative_clamped() {
        assertEquals(1_000L, playbackEndAt(1_000L, 200_000L, 100_000L))
        assertEquals(101_000L, playbackEndAt(1_000L, -5L, 100_000L))
    }

    @Test
    fun formatClock_hoursMinutesInZone() {
        val ms = ((21 * 60 + 5) * 60_000L) // 21:05 UTC
        assertEquals("21:05", formatClock(ms, ZoneOffset.UTC))
        assertEquals("00:05", formatClock(ms, ZoneOffset.ofHours(3)))
    }
}
