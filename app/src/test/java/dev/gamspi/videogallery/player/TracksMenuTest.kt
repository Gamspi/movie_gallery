package dev.gamspi.videogallery.player

import dev.gamspi.videogallery.subs.LayerSource
import dev.gamspi.videogallery.subs.RemoteSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TracksMenuTest {
    @Test fun layerOptions_orders_off_embedded_remote_and_limits_remote_to_five() {
        val embedded = TextTrack(2, 0, "ru", "SDH", true)
        val remotes = (1L..7L).map { remote(it, "en") }
        val options = layerOptions("eng", listOf(embedded), listOf(remote(99, "ru")) + remotes)

        assertEquals(LayerSource.Off, options[0].source)
        assertEquals(LayerSource.Embedded(embedded), options[1].source)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), options.drop(2).map {
            (it.source as LayerSource.Remote).sub.fileId
        })
    }

    @Test fun layerOptions_empty_shows_disabled_reason_and_keeps_off_available() {
        val options = layerOptions("en", emptyList(), emptyList())
        assertEquals("Выкл", options[0].label)
        assertEquals("нет субтитров", options[1].label)
        assertFalse(options[1].enabled)
        assertEquals("нужен ключ OpenSubtitles",
            layerOptions("en", emptyList(), emptyList(), "нужен ключ OpenSubtitles").last().label)
    }

    @Test fun layerOptions_disables_unsupported_and_deduplicates_remote_files() {
        val options = layerOptions("en", listOf(TextTrack(1, 0, null, null, false)),
            listOf(remote(1, "eng"), remote(1, "en-US"), remote(2, "ru")))
        assertFalse(options[1].enabled)
        assertEquals("Дорожка 1", options[1].label)
        assertEquals(3, options.size)
    }

    @Test fun formatOffset_formats_russian_seconds() {
        assertEquals("+1,5 с", formatOffset(1500))
        assertEquals("−0,5 с", formatOffset(-500))
        assertEquals("0 с", formatOffset(0))
        assertEquals("+10 с", formatOffset(10000))
        assertEquals("−10 с", formatOffset(-10000))
    }

    private fun remote(id: Long, language: String) = RemoteSubtitle(id, language, "release $id", false, 0)
}
