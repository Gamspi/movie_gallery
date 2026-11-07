package dev.gamspi.videogallery.stream

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.history.SeasonRelease
import dev.gamspi.videogallery.history.SeriesStore
import dev.gamspi.videogallery.history.seriesKey
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class StreamViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val manager: StreamManager,
    private val series: SeriesStore,
) : ViewModel() {
    val state: StateFlow<StreamState> = manager.state
    private val sessionId: Long

    /** Movie and release data, forwarded to the player route. */
    val meta: PlaybackMeta = PlaybackMeta.from { savedState.get<String>(it) }

    /** Movie imdb id (blank when unknown). */
    val imdb: String get() = meta.imdb

    /** Requested episode for a series stream, null for movies. */
    private val episode: EpisodeRef? = meta.takeIf { it.kind == MediaKind.Series }?.let { m ->
        val season = m.season ?: return@let null
        m.episode?.let { EpisodeRef(season, it) }
    }

    init {
        val magnet = savedState.get<String>("magnet").orEmpty()
        val title = savedState.get<String>("title").orEmpty()
        val torrent = savedState.get<String>("torrent")?.takeIf { it.isNotBlank() }
        manager.start(magnet, title, torrent, episode)
        sessionId = manager.currentSessionId()
        if (episode != null) saveReleaseOnReady(episode, SeasonRelease(magnet, torrent.orEmpty(), title, emptyList()))
    }

    /** Remembers this release for the season once it plays, so later episodes reuse it. */
    private fun saveReleaseOnReady(episode: EpisodeRef, release: SeasonRelease) {
        val source = meta.source?.takeIf { it.isNotBlank() } ?: return
        if (meta.movieId <= 0) return
        viewModelScope.launch {
            manager.state.first { it is StreamState.Ready }
            if (manager.currentSessionId() != sessionId) return@launch
            val files = manager.episodeFiles()
            // Finish the write even if the screen is cleared on navigation to the player.
            withContext(Dispatchers.IO + NonCancellable) {
                series.awaitLoaded()
                series.saveRelease(seriesKey(source, meta.movieId), episode.season, release.copy(files = files))
            }
        }
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
