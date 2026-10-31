package dev.gamspi.videogallery.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTrackTest {
    @Test
    fun textAt_overlapping_joins_lines() {
        val track = SubtitleTrack(listOf(SubtitleCue(0, 2000, "A"), SubtitleCue(1000, 3000, "B")))
        assertEquals("A\nB", track.textAt(1500))
    }

    @Test
    fun textAt_end_exclusive() {
        val track = SubtitleTrack(listOf(SubtitleCue(1000, 2000, "A")))
        assertNull(track.textAt(2000))
        assertEquals("A", track.textAt(1999))
        assertNull(track.textAt(999))
    }

    @Test
    fun textAt_sorts_unsorted_input_and_finds_long_early_cue() {
        val track = SubtitleTrack(
            listOf(
                SubtitleCue(5000, 6000, "C"),
                SubtitleCue(0, 10000, "Long"),
                SubtitleCue(1000, 2000, "B"),
            ),
        )
        assertEquals("Long", track.textAt(3000))
        assertEquals("Long\nC", track.textAt(5500))
    }
}
