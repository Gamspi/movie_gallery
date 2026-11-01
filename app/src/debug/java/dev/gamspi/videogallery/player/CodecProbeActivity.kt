package dev.gamspi.videogallery.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local synthetic fixtures only; logs decoder/output evidence, never URLs or exception text. */
class CodecProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: ExoPlayer
    private var audioCounters: DecoderCounters? = null
    private var requestedAudio: Int? = null
    private var appliedAudio: Int? = null
    private var sample = "none"
    private var generation = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val mode = if (intent.getStringExtra("mode") == "prefer") {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        val renderers = DualTextRenderersFactory(this, {}, {}).setExtensionRendererMode(mode)
        player = ExoPlayer.Builder(this, renderers).build()
        setContentView(PlayerView(this).apply {
            useController = false
            player = this@CodecProbeActivity.player
        })
        Log.i(TAG, "created mode=$mode")
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                audioCounters = decoderCounters
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Log.i(TAG, "sample=$sample audioDecoder=$decoderName initMs=$initializationDurationMs")
            }

            override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                Log.i(TAG, "sample=$sample audioPositionAdvancing=true position=${player.currentPosition}")
            }

            override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                decoderCounters.ensureUpdated()
                Log.i(TAG, "sample=$sample audioDisabled rendered=${decoderCounters.renderedOutputBufferCount} skipped=${decoderCounters.skippedOutputBufferCount}")
            }
        })
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEachIndexed { groupIndex, group ->
                    for (index in 0 until group.length) {
                        Log.i(TAG, "sample=$sample audioGroup=$groupIndex track=$index mime=${group.getTrackFormat(index).sampleMimeType} channels=${group.getTrackFormat(index).channelCount} supported=${group.isTrackSupported(index)} selected=${group.isTrackSelected(index)}")
                    }
                }
                applyAudioSelection()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i(TAG, "sample=$sample state=$playbackState position=${player.currentPosition}")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "sample=$sample errorCode=${error.errorCode}")
            }
        })
        scope.launch {
            while (true) {
                audioCounters?.ensureUpdated()
                Log.i(TAG, "sample=$sample position=${player.currentPosition} playing=${player.isPlaying} rendered=${audioCounters?.renderedOutputBufferCount ?: 0}")
                delay(1_000)
            }
        }
        applyIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(command: Intent) {
        val nextSample = command.getStringExtra("sample")
        if (nextSample != null) {
            if (nextSample !in SAMPLES) { Log.e(TAG, "rejected sample"); return }
            player.stop()
            val request = ++generation
            sample = nextSample
            audioCounters = null
            requestedAudio = null
            appliedAudio = null
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
            scope.launch {
                val directory = withContext(Dispatchers.IO) { getExternalFilesDir("codec-probe") }
                if (directory == null || isFinishing || isDestroyed || request != generation) return@launch
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(directory, "$nextSample.mkv"))))
                player.prepare()
                player.play()
            }
        }
        if (command.hasExtra("audioIndex")) {
            val index = command.getIntExtra("audioIndex", -1)
            if (index in 0..3) { requestedAudio = index; applyAudioSelection() }
        }
        if (command.hasExtra("seekMs")) {
            val position = command.getLongExtra("seekMs", 0).coerceIn(0, 20_000)
            player.seekTo(position)
            Log.i(TAG, "sample=$sample seek=$position")
        }
        when (command.getStringExtra("command")) {
            "pause" -> { player.pause(); Log.i(TAG, "sample=$sample pause") }
            "play" -> { if (player.playerError != null) player.prepare(); player.play(); Log.i(TAG, "sample=$sample play") }
        }
    }

    private fun applyAudioSelection() {
        val requested = requestedAudio ?: return
        if (requested == appliedAudio) return
        val audio = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).map { index -> group to index } }
        val (group, index) = audio.getOrNull(requested) ?: return
        if (!group.isTrackSupported(index)) { Log.i(TAG, "sample=$sample rejectedAudio=$requested unsupported"); return }
        appliedAudio = requested
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
        Log.i(TAG, "sample=$sample requestedAudio=$requested")
    }

    override fun onStop() {
        player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        player.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "CodecProbe"
        val SAMPLES = setOf("aac", "ac3", "dts", "truehd", "truehd-surround", "all-codecs")
    }
}
