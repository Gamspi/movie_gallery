package dev.gamspi.videogallery.releases

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.Quality
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.TorrentSearch
import dev.gamspi.videogallery.ui.components.ErrorKind
import dev.gamspi.videogallery.ui.components.toErrorKind
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ReleasesUiState {
    data object Loading : ReleasesUiState
    data class Content(
        val movieTitle: String,
        val all: List<TorrentResult>,
        val filter: Quality?,
        val unavailable: List<ProviderId>,
        val noProviders: Boolean = false,
        val imdbId: String? = null,
    ) :ReleasesUiState {
        val visible: List<TorrentResult>
            get() = if (filter == null) all else all.filter { it.quality == filter }
    }

    data class Error(val kind: ErrorKind) : ReleasesUiState
}

@HiltViewModel
class ReleasesViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val search: TorrentSearch,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val id: Int = checkNotNull(savedStateHandle["id"])
    private val _state = MutableStateFlow<ReleasesUiState>(ReleasesUiState.Loading)
    val state: StateFlow<ReleasesUiState> = _state
    private var job: Job? = null

    init {
        load()
    }

    fun setFilter(q: Quality?) {
        val s = _state.value
        if (s is ReleasesUiState.Content) _state.value = s.copy(filter = q)
    }

    fun retry() = load()

    private fun load() {
        job?.cancel()
        _state.value = ReleasesUiState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                val details = catalog.details(id)
                val noProviders = ProviderId.entries.none { settings.tracker(it).value.enabled }
                val outcome = search.search(details)
                ReleasesUiState.Content(
                    movieTitle = details.movie.title,
                    all = outcome.results,
                    filter = null,
                    unavailable = outcome.unavailable,
                    noProviders = noProviders,
                    imdbId = details.imdbId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: CatalogException) {
                ReleasesUiState.Error(e.toErrorKind())
            }
        }
    }
}
