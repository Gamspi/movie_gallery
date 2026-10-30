package dev.gamspi.videogallery.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeTest {
    private val twoHours = 7_200_000L

    @Test fun under60s_null() = assertNull(resumeFrom(59_000, twoHours))
    @Test fun nullSaved_null() = assertNull(resumeFrom(null, twoHours))
    @Test fun over95_null() = assertNull(resumeFrom(6_900_000, twoHours))
    @Test fun middle_returnsSaved() = assertEquals(5_025_000L, resumeFrom(5_025_000, twoHours))
    @Test fun unknownDuration_onlyMinCheck() {
        assertEquals(100_000L, resumeFrom(100_000, Long.MIN_VALUE + 1))
        assertNull(resumeFrom(30_000, 0))
    }

    @Test fun isFinished_threshold() {
        assertTrue(isFinished(6_900_000, twoHours))
        assertFalse(isFinished(6_800_000, twoHours))
        assertFalse(isFinished(6_900_000, -1))
    }

    @Test fun formatTime_hours() = assertEquals("1:23:45", formatTime(5_025_000, twoHours))
    @Test fun formatTime_minutes() = assertEquals("4:05", formatTime(245_000, 600_000))
}
