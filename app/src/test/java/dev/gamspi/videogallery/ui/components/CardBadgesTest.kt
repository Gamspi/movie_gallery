package dev.gamspi.videogallery.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun watchedMark_onlyCompletedEpisodes() {
        assertFalse(watchedMark(null))
        assertFalse(watchedMark(0f))
        assertFalse(watchedMark(0.5f))
        assertFalse(watchedMark(0.99f))
        assertTrue(watchedMark(1f))
        assertTrue(watchedMark(1.1f))
    }

    @Test
    fun cardProgressFill_raisesSmallProgressToMinimum() {
        // 1:30 of 2:32:13 is about 1%: still drawn with the minimum share.
        assertEquals(MIN_CARD_PROGRESS, cardProgressFill(90f / 9133f))
        assertEquals(0.5f, cardProgressFill(0.5f))
        assertNull(cardProgressFill(null))
        assertNull(cardProgressFill(0f))
        assertNull(cardProgressFill(1f))
    }
}
