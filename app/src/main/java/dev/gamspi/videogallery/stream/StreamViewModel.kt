package dev.gamspi.videogallery.stream

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.history.PlaybackMeta
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

@HiltViewModel
class StreamViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val manager: StreamManager,
) : ViewModel() {
    val state: StateFlow<StreamState> = manager.state
    private val sessionId: Long

    /** Movie and release data, forwarded to the player route. */
    val meta: PlaybackMeta = PlaybackMeta.from { savedState.get<String>(it) }

    /** Movie imdb id (blank when unknown). */
    val imdb: String get() = meta.imdb

    init {
        manager.start(
            savedState.get<String>("magnet").orEmpty(),
            savedState.get<String>("title").orEmpty(),
            savedState.get<String>("torrent")?.takeIf { it.isNotBlank() },
        )
        sessionId = manager.currentSessionId()
    }

    val stallWarning = manager.stallWarning.map { warning ->
        warning?.takeIf { it.sessionId == sessionId }
    }

    fun chooseFile(index: Int) = manager.chooseFile(index)

    fun cancel() = manager.stopIf(sessionId)

    /** After Ready the player owns the session; before that, leaving the screen cancels it. */
    override fun onCleared() {
        if (manager.state.value !is StreamState.Ready) manager.stopIf(sessionId)
    }
}
