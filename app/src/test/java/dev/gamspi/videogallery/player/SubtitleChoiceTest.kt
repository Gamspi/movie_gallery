package dev.gamspi.videogallery.player

import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.subs.Layer
import dev.gamspi.videogallery.subs.LayerSource
import dev.gamspi.videogallery.subs.RemoteSubtitle
import dev.gamspi.videogallery.translate.TranslateLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleChoiceTest {
    private val ruSdh = TextTrack(1, 0, "ru", "SDH", true)
    private val ruFull = TextTrack(2, 0, "rus", "Full", true)
    private val en = TextTrack(3, 0, "en", null, true)
    private fun remote(id: Long, language: String) = RemoteSubtitle(id, language, "release $id", false, 0)

    @Test fun choice_round_trips_through_encoding() {
        for (choice in listOf(
            SubtitleChoice.Off,
            SubtitleChoice.Embedded("ru", "SDH"),
            SubtitleChoice.Embedded(null, null),
            SubtitleChoice.Remote("en", 42, "Release.1080p"),
            SubtitleChoice.Translated(Layer.B, TranslateLang.EN),
        )) assertEquals(choice, decodeSubtitleChoice(encodeSubtitleChoice(choice)))
    }

    @Test fun broken_or_missing_value_decodes_to_null() {
        assertNull(decodeSubtitleChoice(null))
        assertNull(decodeSubtitleChoice(""))
        assertNull(decodeSubtitleChoice("{not json"))
        assertNull(decodeSubtitleChoice("""{"type":"unknown"}"""))
    }

    @Test fun none_is_not_stored_and_sources_map_to_descriptors() {
        assertNull(LayerSource.None.toChoice())
        assertEquals(SubtitleChoice.Off, LayerSource.Off.toChoice())
        assertEquals(SubtitleChoice.Embedded("ru", "SDH"), LayerSource.Embedded(ruSdh).toChoice())
        assertEquals(SubtitleChoice.Remote("en", 5, "release 5"), LayerSource.Remote(remote(5, "en")).toChoice())
        assertEquals(SubtitleChoice.Translated(Layer.A, TranslateLang.RU),
            LayerSource.Translated(Layer.A, TranslateLang.RU).toChoice())
    }

    @Test fun title_key_is_series_level_and_falls_back_to_playback_key() {
        val episode = PlaybackMeta(movieId = 9, source = "tmdb", kind = MediaKind.Series, season = 1, episode = 2)
        val next = episode.copy(season = 2, episode = 1)
        assertEquals("tmdb:Series:9", subtitleTitleKey(episode, "bt:a"))
        assertEquals(subtitleTitleKey(episode, "bt:a"), subtitleTitleKey(next, "bt:b"))
        assertEquals("tmdb:Movie:9", subtitleTitleKey(PlaybackMeta(movieId = 9, source = "tmdb"), "k"))
        assertEquals("imdb:Movie:tt1", subtitleTitleKey(PlaybackMeta(imdb = "tt1"), "k"))
        assertEquals("key:url:x", subtitleTitleKey(PlaybackMeta(), "url:x"))
    }

    @Test fun off_restores_to_off() =
        assertEquals(LayerSource.Off, restoreSource(SubtitleChoice.Off, emptyList(), emptyList(), null, emptyList()))

    @Test fun embedded_prefers_language_and_label_then_language() {
        val tracks = listOf(ruSdh, ruFull, en)
        assertEquals(LayerSource.Embedded(ruFull),
            restoreSource(SubtitleChoice.Embedded("rus", "Full"), tracks, emptyList(), null, emptyList()))
        // Same language, different label: first track of that language.
        assertEquals(LayerSource.Embedded(ruSdh),
            restoreSource(SubtitleChoice.Embedded("ru", "Forced"), tracks, emptyList(), null, emptyList()))
        assertNull(restoreSource(SubtitleChoice.Embedded("de", null), tracks, emptyList(), null, emptyList()))
    }

    @Test fun embedded_skips_group_of_other_layer_and_unsupported() {
        val tracks = listOf(ruSdh, ruFull)
        assertEquals(LayerSource.Embedded(ruFull),
            restoreSource(SubtitleChoice.Embedded("ru", "SDH"), tracks, emptyList(), ruSdh, emptyList()))
        assertNull(restoreSource(SubtitleChoice.Embedded("ru", "SDH"),
            listOf(ruSdh.copy(supported = false)), emptyList(), null, emptyList()))
    }

    @Test fun embedded_without_language_matches_only_exact_label() {
        val unnamed = TextTrack(4, 0, null, "Commentary", true)
        assertEquals(LayerSource.Embedded(unnamed), restoreSource(SubtitleChoice.Embedded(null, "Commentary"),
            listOf(en, unnamed), emptyList(), null, emptyList()))
        assertNull(restoreSource(SubtitleChoice.Embedded(null, "Other"), listOf(en, unnamed), emptyList(), null, emptyList()))
    }

    @Test fun remote_prefers_file_then_language_else_waits() {
        val results = listOf(remote(1, "en"), remote(2, "ru"), remote(3, "ru"))
        assertEquals(LayerSource.Remote(results[2]),
            restoreSource(SubtitleChoice.Remote("ru", 3, ""), emptyList(), results, null, emptyList()))
        assertEquals(LayerSource.Remote(results[1]),
            restoreSource(SubtitleChoice.Remote("ru", 99, ""), emptyList(), results, null, emptyList()))
        assertNull(restoreSource(SubtitleChoice.Remote("ru", 3, ""), emptyList(), emptyList(), null, emptyList()))
    }

    @Test fun translated_restores_only_when_offered() {
        val option = LayerSource.Translated(Layer.B, TranslateLang.EN)
        val choice = SubtitleChoice.Translated(Layer.B, TranslateLang.EN)
        assertEquals(option, restoreSource(choice, emptyList(), emptyList(), null, listOf(option)))
        assertNull(restoreSource(choice, emptyList(), emptyList(), null, emptyList()))
    }
}
