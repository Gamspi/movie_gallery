package dev.gamspi.videogallery.stream

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class StreamViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val manager: StreamManager,
) : ViewModel() {
    val state: StateFlow<StreamState> = manager.state
    private val sessionId: Long

    init {
        manager.start(
            savedState.get<String>("magnet").orEmpty(),
            savedState.get<String>("title").orEmpty(),
        )
        sessionId = manager.currentSessionId()
    }

    fun chooseFile(index: Int) = manager.chooseFile(index)

    fun cancel() = manager.stop()

    /** After Ready the player owns the session; before that, leaving the screen cancels it. */
    override fun onCleared() {
        if (manager.state.value !is StreamState.Ready) manager.stopIf(sessionId)
    }
}
