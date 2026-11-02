package dev.gamspi.videogallery.player

import android.content.Context
import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class TrueHdAudioSinkTest {
    private fun encoded(mime: String) = Format.Builder()
        .setSampleMimeType(mime).setChannelCount(6).setSampleRate(48_000).build()

    private val pcm = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setChannelCount(6).setSampleRate(48_000).build()

    // Mirrors the TV: HDMI reports both AC3 and TrueHD passthrough.
    @Suppress("DEPRECATION")
    private fun tvSink(): AudioSink = DefaultAudioSink.Builder()
        .setAudioCapabilities(
            AudioCapabilities(intArrayOf(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_DOLBY_TRUEHD), 8),
        )
        .build()

    @Test fun truehd_is_rejected_while_ac3_and_pcm_stay_as_delegate() {
        val tv = tvSink()
        val trueHd = encoded(MimeTypes.AUDIO_TRUEHD)
        val ac3 = encoded(MimeTypes.AUDIO_AC3)
        // Precondition: without the wrapper TrueHD would go passthrough.
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, tv.getFormatSupport(trueHd))

        val sink = NoTrueHdPassthroughAudioSink(tv)
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(trueHd))
        assertFalse(sink.supportsFormat(trueHd))
        assertFalse(sink.getFormatOffloadSupport(trueHd).isFormatSupported)
        assertEquals(tv.getFormatSupport(ac3), sink.getFormatSupport(ac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(ac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm))
        assertTrue(sink.supportsFormat(pcm))
    }

    @Test fun production_factory_wraps_audio_sink() {
        val context = RuntimeEnvironment.getApplication()
        val factory = DualTextRenderersFactory(context, {}, {})
        val build = DefaultRenderersFactory::class.java.getDeclaredMethod(
            "buildAudioSink", Context::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val sink = build.invoke(factory, context, false, false) as AudioSink
        try {
            assertTrue(sink is NoTrueHdPassthroughAudioSink)
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(encoded(MimeTypes.AUDIO_TRUEHD)))
            assertTrue(sink.supportsFormat(pcm))
        } finally {
            sink.release()
        }
    }
}
