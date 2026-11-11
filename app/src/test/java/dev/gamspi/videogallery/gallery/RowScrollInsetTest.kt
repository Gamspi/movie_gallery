package dev.gamspi.videogallery.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class RowScrollInsetTest {
    // Container 1000px, window [104, 952].
    private fun dist(offset: Float, size: Float = 150f) = insetScrollDistance(offset, size, 1000f, 104f, 48f)

    @Test
    fun cardInsideWindowDoesNotScroll() {
        assertEquals(0f, dist(104f), 0f)
        assertEquals(0f, dist(802f), 0f)
    }

    @Test
    fun cardUnderRailScrollsBackToWindowStart() {
        assertEquals(-84f, dist(20f), 0f)
    }

    @Test
    fun cardPastEndScrollsToWindowEnd() {
        assertEquals(98f, dist(900f), 0f)
    }

    @Test
    fun cardWiderThanWindowStays() {
        assertEquals(0f, dist(50f, size = 1000f), 0f)
    }
}
