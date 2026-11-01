package dev.gamspi.videogallery.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.video.VideoRendererEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class AudioRendererIntegrationTest {
    @Test fun production_factory_keeps_platform_audio_first_and_two_text_renderers() {
        val factory = DualTextRenderersFactory(RuntimeEnvironment.getApplication(), {}, {})
        val renderers = factory.createRenderers(
            Handler(Looper.getMainLooper()), object : VideoRendererEventListener {},
            object : AudioRendererEventListener {}, {}, {},
        )
        try {
            val audio = renderers.filter { it.trackType == C.TRACK_TYPE_AUDIO }
            assertEquals(2, audio.size)
            assertTrue(audio.first().javaClass.simpleName.contains("MediaCodecAudioRenderer"))
            assertEquals("FfmpegAudioRenderer", audio.last().javaClass.simpleName)
            assertEquals(2, renderers.count { it.trackType == C.TRACK_TYPE_TEXT })
        } finally {
            renderers.forEach { it.release() }
        }
    }
}
