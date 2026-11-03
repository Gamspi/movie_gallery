package dev.gamspi.videogallery.player

import dev.gamspi.videogallery.subs.Layer
import dev.gamspi.videogallery.subs.LayerSource
import dev.gamspi.videogallery.subs.RemoteSubtitle
import org.junit.Assert.assertEquals
import dev.gamspi.videogallery.translate.TranslateLang
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TracksMenuTest {
    @Test fun layerOptions_orders_off_embedded_remote_and_limits_remote_to_five() {
        val embedded = TextTrack(2, 0, "ru", "SDH", true)
        val remotes = (1L..7L).map { remote(it, "en") }
        val options = layerOptions("eng", listOf(embedded), listOf(remote(99, "ru")) + remotes)

        assertEquals(LayerSource.Off, options[0].source)
        assertEquals(LayerSource.Embedded(embedded), options[1].source)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), options.drop(2).dropLast(1).map {
            (it.source as LayerSource.Remote).sub.fileId
        })
        assertEquals("Нет субтитров для перевода", options.last().label)
    }

    @Test fun layerOptions_empty_shows_disabled_reason_and_keeps_off_available() {
        val options = layerOptions("en", emptyList(), emptyList())
        assertEquals("Выкл", options[0].label)
        assertEquals("нет субтитров", options[1].label)
        assertFalse(options[1].enabled)
        assertEquals("нужен ключ OpenSubtitles",
            layerOptions("en", emptyList(), emptyList(), "нужен ключ OpenSubtitles")[1].label)
    }

    @Test fun layerOptions_disables_unsupported_and_deduplicates_remote_files() {
        val options = layerOptions("en", listOf(TextTrack(1, 0, null, null, false)),
            listOf(remote(1, "eng"), remote(1, "en-US"), remote(2, "ru")))
        assertFalse(options[1].enabled)
        assertEquals("Дорожка 1", options[1].label)
        // Off, track, one remote file and the translation reason.
        assertEquals(4, options.size)
    }

    @Test fun layerOptions_adds_translations_after_remote() {
        val translations = listOf(LayerSource.Translated(Layer.A, TranslateLang.EN),
            LayerSource.Translated(Layer.A, TranslateLang.RU))
        val options = layerOptions("ru", emptyList(), listOf(remote(1, "ru")), translations = translations)
        assertEquals(listOf("Выкл", "OpenSubtitles · release 1", "Перевод с en", "Перевод с ru"),
            options.map { it.label })
        assertEquals(translations, options.drop(2).map { it.source })
        assertTrue(options.all { it.enabled })
    }

    @Test fun layerOptions_without_translations_shows_disabled_reason_once() {
        val track = TextTrack(1, 0, "ru", null, true)
        val options = layerOptions("ru", listOf(track), emptyList())
        assertEquals("Нет субтитров для перевода", options.last().label)
        assertNull(options.last().source)
        assertFalse(options.last().enabled)
        // A layer note with the same reason is not repeated.
        val noted = layerOptions("ru", listOf(track), emptyList(), "Нет субтитров для перевода")
        assertEquals(1, noted.count { it.label == "Нет субтитров для перевода" })
    }

    @Test fun formatOffset_formats_russian_seconds() {
        assertEquals("+1,5 с", formatOffset(1500))
        assertEquals("−0,5 с", formatOffset(-500))
        assertEquals("0 с", formatOffset(0))
        assertEquals("+10 с", formatOffset(10000))
        assertEquals("−10 с", formatOffset(-10000))
    }

    @Test fun menuSectionsOrder() {
        assertEquals(listOf("Субтитры", "Аудиодорожка", "Прочее"), PlayerMenuSection.entries.map { it.title })
    }

    private fun remote(id: Long, language: String) = RemoteSubtitle(id, language, "release $id", false, 0)
}
