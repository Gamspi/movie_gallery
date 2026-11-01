package dev.gamspi.videogallery.player

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Debug-only probe; start with --es url, then send --es command pause/play or --el seekMs. */
class DualTextProbeActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var selector: DualTextTrackSelector
    private lateinit var layerA: TextView
    private lateinit var layerB: TextView
    private var selectedSlots: Pair<Pair<Int, Int>, Pair<Int, Int>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layerA = layerView(Color.WHITE)
        layerB = layerView(Color.YELLOW)
        selector = DualTextTrackSelector(this)
        player = ExoPlayer.Builder(
            this,
            DualTextRenderersFactory(this, { showCues("A", layerA, it) }, { showCues("B", layerB, it) }),
        ).setTrackSelector(selector).build()
        val video = PlayerView(this).apply {
            useController = false
            this.player = this@DualTextProbeActivity.player
            subtitleView?.visibility = View.GONE
        }
        val layers = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 0, 32, 48)
            addView(layerA)
            addView(layerB)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(layers, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val text = tracks.textTracks()
                Log.i(TAG, "tracks text=${text.size} supported=${text.count { it.supported }}")
                val a = text.firstOrNull { it.supported && it.language in listOf("en", "eng", "en-US") }
                val b = text.firstOrNull { it.supported && it.language in listOf("ru", "rus", "ru-RU") }
                if (a == null || b == null) return
                val slots = (a.group to a.index) to (b.group to b.index)
                if (selectedSlots == slots) return
                selectedSlots = slots
                Log.i(TAG, "slots A=${slots.first} B=${slots.second}")
                selector.setTextSlots(slots.first, slots.second)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "playerError=${error.errorCodeName}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i(TAG, "state=$playbackState")
            }
        })
        val url = intent.getStringExtra("url")
        if (url.isNullOrBlank()) {
            Log.e(TAG, "missing url")
            finish()
            return
        }
        playUrl(url)
        applyCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("url")?.takeIf { it.isNotBlank() }?.let(::playUrl)
        applyCommand(intent)
    }

    private fun playUrl(url: String) {
        selectedSlots = null
        selector.setTextSlots(null, null)
        layerA.text = ""
        layerB.text = ""
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    private fun applyCommand(intent: Intent) {
        if (intent.hasExtra("seekMs")) {
            val position = intent.getLongExtra("seekMs", 0L).coerceAtLeast(0)
            Log.i(TAG, "seek=$position")
            player.seekTo(position)
        }
        when (intent.getStringExtra("command")) {
            "pause" -> { player.pause(); Log.i(TAG, "pause") }
            "play" -> { player.play(); Log.i(TAG, "play") }
        }
    }

    private fun layerView(color: Int) = TextView(this).apply {
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(color)
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    private fun showCues(layer: String, view: TextView, cues: List<Cue>) {
        val text = cues.mapNotNull { it.text }.joinToString("\n")
        view.text = text
        Log.i(TAG, "cue $layer position=${player.currentPosition} text=$text")
    }

    override fun onStop() {
        player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DualTextProbe"
    }
}
