package dev.gamspi.videogallery.player

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dev.gamspi.videogallery.stream.StreamManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PlayerUiState(
    val title: String,
    val url: String,
    val resumeOffer: Long? = null,
    val error: String? = null,
)

private const val SAVE_INTERVAL_MS = 10_000L

@HiltViewModel
class PlayerViewModel internal constructor(
    savedState: SavedStateHandle,
    private val store: PlaybackStore,
    private val manager: StreamManager,
    private val clock: () -> Long,
) : ViewModel() {
    @Inject
    constructor(savedState: SavedStateHandle, store: PlaybackStore, manager: StreamManager) :
        this(savedState, store, manager, SystemClock::elapsedRealtime)

    private val url: String = savedState.get<String>("url").orEmpty()
    private val key: String = savedState.get<String>("key").orEmpty().ifEmpty { "url:$url" }
    private val _state = MutableStateFlow(
        PlayerUiState(title = savedState.get<String>("title").orEmpty(), url = url),
    )
    val state: StateFlow<PlayerUiState> = _state

    private val isTorrent = key.startsWith("bt:")

    /** Download status line; only for torrent sessions. */
    val statusLine: StateFlow<String?> = if (isTorrent) manager.statusLine else MutableStateFlow(null)

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    public override fun onCleared() {
        if (isTorrent) manager.stop()
    }

    private var resumeComputed = false
    private var lastSaveAt: Long? = null

    fun onDuration(durationMs: Long) {
        if (resumeComputed || durationMs <= 0) return
        resumeComputed = true
        _state.value = _state.value.copy(resumeOffer = resumeFrom(store.position(key), durationMs))
    }

    fun dismissResume() {
        _state.value = _state.value.copy(resumeOffer = null)
    }

    fun onProgress(positionMs: Long, durationMs: Long) {
        val now = clock()
        val last = lastSaveAt
        if (last != null && now - last < SAVE_INTERVAL_MS) return
        if (positionMs <= 0) return
        lastSaveAt = now
        store.savePosition(key, positionMs)
    }

    fun onPauseOrExit(positionMs: Long, durationMs: Long) {
        if (positionMs <= 0) return
        lastSaveAt = clock()
        if (isFinished(positionMs, durationMs)) store.clearPosition(key) else store.savePosition(key, positionMs)
    }

    fun chooseInitialAudio(tracks: List<AudioTrack>): AudioTrack? =
        chooseAudio(tracks, store.audioPreference())

    fun onAudioSelected(t: AudioTrack) {
        val lang = t.language?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) } ?: return
        store.saveAudioPreference(AudioPreference(lang, t.label))
    }

    fun onError(message: String) {
        _state.value = _state.value.copy(error = message)
    }
}
