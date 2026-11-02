package dev.gamspi.videogallery.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackTrackersTest {
    @Test
    fun mergeTrackersKeepsOrderAndDropsDuplicates() {
        val result = mergeTrackers(listOf("http://a", "http://b"), listOf("http://b", "", "udp://c"))

        assertEquals(listOf("http://a", "http://b", "udp://c") + FALLBACK_TRACKERS, result)
        assertEquals(result.size, result.distinct().size)
    }

    @Test
    fun mergeTrackersKeepsExistingFallbackInItsFirstPosition() {
        val result = mergeTrackers(
            listOf("", FALLBACK_TRACKERS[2], "http://a"),
            listOf("http://a", FALLBACK_TRACKERS[2]),
        )

        assertEquals(
            listOf(FALLBACK_TRACKERS[2], "http://a") + FALLBACK_TRACKERS.filterNot { it == FALLBACK_TRACKERS[2] },
            result,
        )
    }

    @Test
    fun mergeTrackersWithNoTrackersGivesAllFallbacks() {
        assertEquals(FALLBACK_TRACKERS, mergeTrackers())
        assertEquals(FALLBACK_TRACKERS, mergeTrackers(emptyList(), listOf("")))
    }

    @Test
    fun emptyInputGivesAllFallbacks() {
        assertEquals(FALLBACK_TRACKERS, withFallbackTrackers(emptyList()))
    }

    @Test
    fun existingComeFirstAndAreKept() {
        val own = listOf("udp://a.example:1337/announce")
        val result = withFallbackTrackers(own)
        assertEquals(own + FALLBACK_TRACKERS, result)
    }

    @Test
    fun duplicatesAreSkipped() {
        val own = listOf(FALLBACK_TRACKERS[2], "udp://a.example:1337/announce", FALLBACK_TRACKERS[2])
        val result = withFallbackTrackers(own)
        assertEquals(result.size, result.distinct().size)
        assertEquals(own.distinct().first(), result.first())
        assertEquals(true, result.containsAll(FALLBACK_TRACKERS))
        assertEquals(2 + FALLBACK_TRACKERS.size - 1, result.size)
    }
}
