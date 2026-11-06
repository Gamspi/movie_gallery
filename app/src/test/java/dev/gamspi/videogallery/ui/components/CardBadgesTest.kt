package dev.gamspi.videogallery.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardBadgesTest {
    @Test
    fun formatRating_oneDecimalWithDot() {
        assertEquals("7.8", formatRating(7.8))
        assertEquals("8.0", formatRating(8.0))
        assertEquals("6.7", formatRating(6.66))
        assertEquals("10.0", formatRating(10.0))
    }

    @Test
    fun formatRating_absentOrInvalid_isNull() {
        assertNull(formatRating(null))
        assertNull(formatRating(0.0))
        assertNull(formatRating(-1.0))
        assertNull(formatRating(Double.NaN))
    }

    @Test
    fun visibleProgress_onlyStartedAndUnfinished() {
        assertEquals(0.5f, visibleProgress(0.5f))
        assertNull(visibleProgress(null))
        assertNull(visibleProgress(0f))
        assertNull(visibleProgress(1f))
        assertNull(visibleProgress(-0.1f))
    }
}
