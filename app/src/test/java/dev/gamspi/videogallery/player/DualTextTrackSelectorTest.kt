package dev.gamspi.videogallery.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class DualTextTrackSelectorTest {
    @Test fun extra_audio_renderer_does_not_shift_the_two_selected_text_groups() = withSelector { selector ->
        fun format(mime: String, lang: String? = null) = Format.Builder().setSampleMimeType(mime).setLanguage(lang).build()
        val groups = TrackGroupArray(
            TrackGroup("v", format(MimeTypes.VIDEO_H264)),
            TrackGroup("a", format(MimeTypes.AUDIO_AAC, "en")),
            TrackGroup("en", format(MimeTypes.APPLICATION_MEDIA3_CUES, "en")),
            TrackGroup("ru", format(MimeTypes.APPLICATION_MEDIA3_CUES, "ru")),
        )
        fun selectWithAudio() = selector.selectTracks(arrayOf(
            FakeCapabilities(C.TRACK_TYPE_VIDEO, true),
            FakeCapabilities(C.TRACK_TYPE_AUDIO, true),
            FakeCapabilities(C.TRACK_TYPE_AUDIO, true),
            FakeCapabilities(C.TRACK_TYPE_TEXT, true),
            FakeCapabilities(C.TRACK_TYPE_TEXT, true),
        ), groups, MediaPeriodId("audio-extensions"), Timeline.EMPTY)
        val text = selectWithAudio().tracks.textTracks()
        selector.setTextSlots(text[0].group to text[0].index, text[1].group to text[1].index)
        val result = selectWithAudio()
        assertNotNull(result.rendererConfigurations[1])
        assertNull(result.rendererConfigurations[2])
        assertEquals("en", result.selections[3]!!.selectedFormat.language)
        assertEquals("ru", result.selections[4]!!.selectedFormat.language)
    }

    @Test fun second_renderer_selects_russian_group_mapped_to_first_renderer() = withSelector { selector ->
        val baseline = select(selector)
        val mapped = baseline.info as MappedTrackInfo
        assertEquals(2, mapped.getTrackGroups(1).length)
        assertEquals(0, mapped.getTrackGroups(2).length)
        val tracks = baseline.tracks.textTracks()
        val english = tracks.first { it.language == "en" }
        val russian = tracks.first { it.language == "ru" }

        selector.setTextSlots(english.group to english.index, russian.group to russian.index)
        val result = select(selector)

        assertEquals("en", result.selections[1]!!.selectedFormat.language)
        assertEquals("ru", result.selections[2]!!.selectedFormat.language)
        assertNotNull(result.rendererConfigurations[0])
        assertNotNull(result.rendererConfigurations[1])
        assertNotNull(result.rendererConfigurations[2])
    }

    @Test fun null_first_slot_disables_only_first_text_renderer() = withSelector { selector ->
        val russian = select(selector).tracks.textTracks().first { it.language == "ru" }
        selector.setTextSlots(null, russian.group to russian.index)

        val result = select(selector)

        assertNull(result.selections[1])
        assertNull(result.rendererConfigurations[1])
        assertEquals("ru", result.selections[2]!!.selectedFormat.language)
        assertNotNull(result.rendererConfigurations[2])
    }

    @Test fun unsupported_requested_track_does_not_enable_renderer() = withSelector { selector ->
        val tracks = select(selector, russianSupported = false).tracks.textTracks()
        val english = tracks.first { it.language == "en" }
        val russian = tracks.first { it.language == "ru" }
        selector.setTextSlots(english.group to english.index, russian.group to russian.index)

        val result = select(selector, russianSupported = false)

        assertEquals("en", result.selections[1]!!.selectedFormat.language)
        assertNull(result.selections[2])
        assertNull(result.rendererConfigurations[2])
    }

    @Test fun requesting_same_group_twice_keeps_second_renderer_disabled() = withSelector { selector ->
        val english = select(selector).tracks.textTracks().first { it.language == "en" }
        val slot = english.group to english.index
        selector.setTextSlots(slot, slot)

        val result = select(selector)

        assertEquals("en", result.selections[1]!!.selectedFormat.language)
        assertNull(result.selections[2])
        assertNull(result.rendererConfigurations[2])
    }

    private fun withSelector(test: (DualTextTrackSelector) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val selector = DualTextTrackSelector(context)
        selector.init({ _ -> }, DefaultBandwidthMeter.Builder(context).build())
        try {
            test(selector)
        } finally {
            selector.release()
        }
    }

    private fun select(selector: DualTextTrackSelector, russianSupported: Boolean = true): TrackSelectorResult {
        val video = TrackGroup("video", Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        fun text(language: String) = TrackGroup(language, Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setLanguage(language).build())
        return selector.selectTracks(
            arrayOf(
                FakeCapabilities(C.TRACK_TYPE_VIDEO, russianSupported),
                FakeCapabilities(C.TRACK_TYPE_TEXT, russianSupported),
                FakeCapabilities(C.TRACK_TYPE_TEXT, russianSupported),
            ),
            TrackGroupArray(video, text("en"), text("ru")),
            MediaPeriodId("period"),
            Timeline.EMPTY,
        )
    }

    private class FakeCapabilities(private val type: Int, private val russianSupported: Boolean) : RendererCapabilities {
        override fun getName() = "Fake$type"
        override fun getTrackType() = type
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
        override fun supportsFormat(format: Format): Int {
            val formatType = MimeTypes.getTrackType(format.sampleMimeType)
            val support = when {
                formatType != type -> C.FORMAT_UNSUPPORTED_TYPE
                !russianSupported && format.language == "ru" -> C.FORMAT_UNSUPPORTED_SUBTYPE
                else -> C.FORMAT_HANDLED
            }
            return RendererCapabilities.create(support)
        }
    }
}
