package dev.gamspi.videogallery.stream

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
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

    /** Movie imdb id (blank when unknown), forwarded to the player route. */
    val imdb: String = savedState.get<String>("imdb").orEmpty()

    init {
        manager.start(
            savedState.get<String>("magnet").orEmpty(),
            savedState.get<String>("title").orEmpty(),
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
