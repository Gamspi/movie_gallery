package dev.gamspi.videogallery.subs

import dev.gamspi.videogallery.player.TextTrack
import org.junit.Assert.*
import org.junit.Test

class SubtitleSourcingTest {
    private val en = TextTrack(1, 0, "eng", null, true)
    private val remote = RemoteSubtitle(7, "en-US", "release", true, 1)

    @Test fun embedded_preferred_over_remote() {
        assertEquals(LayerSource.Embedded(en), chooseSource("en", listOf(en), listOf(remote), null))
    }
    @Test fun iso3_matching_en_eng() {
        assertTrue(sameLanguage("en-US", "eng"))
        assertTrue(sameLanguage("rus", "ru"))
        assertFalse(sameLanguage("und", "und"))
        assertFalse(sameLanguage("invalid", "invalid"))
        assertFalse(sameLanguage("zz", "zz"))
        assertFalse(sameLanguage(null, null))
    }
    @Test fun remote_when_no_embedded() {
        assertEquals(LayerSource.Remote(remote), chooseSource("eng", emptyList(), listOf(remote), null))
    }
    @Test fun none_when_nothing() {
        assertEquals(LayerSource.None, chooseSource("en", emptyList(), emptyList(), null))
    }
    @Test fun same_language_both_layers() {
        assertEquals(LayerSource.Remote(remote), chooseSource("en", listOf(en), listOf(remote), en))
        assertEquals(LayerSource.None, chooseSource("en", listOf(en), emptyList(), en))
        assertEquals(LayerSource.None, chooseSource("en", listOf(en.copy(index = 1)), emptyList(), en))
    }
    @Test fun unsupported_embedded_is_skipped() {
        assertEquals(LayerSource.Remote(remote), chooseSource("en", listOf(en.copy(supported = false)), listOf(remote), null))
    }
}
