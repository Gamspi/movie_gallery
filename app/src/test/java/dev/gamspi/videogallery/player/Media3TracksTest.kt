package dev.gamspi.videogallery.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class Media3TracksTest {
    @Test fun textTracks_lists_text_groups_only() {
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        val english = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("en").setLabel("English SDH").build()
        val russian = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("ru").setLabel("Русский").build()
        val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val tracks = Tracks(listOf(
            group("audio", audio),
            group("subtitles", english, russian),
            group("video", video),
        ))

        assertEquals(listOf(
            TextTrack(1, 0, "en", "English SDH", true),
            TextTrack(1, 1, "ru", "Русский", true),
        ), tracks.textTracks())
    }

    @Test fun textTracks_preserves_unsupported_tracks_and_null_metadata() {
        val subtitle = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()
        val tracks = Tracks(listOf(Tracks.Group(
            TrackGroup("unsupported", subtitle),
            false,
            intArrayOf(C.FORMAT_UNSUPPORTED_TYPE),
            booleanArrayOf(false),
        )))

        assertEquals(listOf(TextTrack(0, 0, null, null, false)), tracks.textTracks())
        assertEquals(emptyList<TextTrack>(), Tracks.EMPTY.textTracks())
    }

    private fun group(id: String, vararg formats: Format) = Tracks.Group(
        TrackGroup(id, *formats),
        false,
        IntArray(formats.size) { C.FORMAT_HANDLED },
        BooleanArray(formats.size),
    )

    @Test fun codecName_mapsCommonMimes() {
        assertEquals("AC3", codecName("audio/ac3"))
        assertEquals("EAC3", codecName("audio/eac3"))
        assertEquals("DTS", codecName("audio/vnd.dts"))
        assertEquals("DTS", codecName("audio/vnd.dts.hd"))
        assertEquals("TrueHD", codecName("audio/true-hd"))
        assertEquals("AAC", codecName("audio/mp4a-latm"))
        assertEquals("opus", codecName("audio/opus"))
        assertNull(codecName(null))
    }
}
