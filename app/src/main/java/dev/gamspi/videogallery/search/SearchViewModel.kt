package dev.gamspi.videogallery.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.ui.components.ErrorKind
import dev.gamspi.videogallery.ui.components.toErrorKind
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

/** Result kind filter; applied locally to the fetched results. */
enum class KindFilter { All, Movies, Series }

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val movies: List<Movie>) : SearchUiState
    data object Empty : SearchUiState
    data class Error(val kind: ErrorKind) : SearchUiState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val retryTick = MutableStateFlow(0)

    // Eagerly: the VM lives with its back-stack entry; a restart on return from the details
    // screen would re-search and wipe the grid (and the restored card focus).
    private val rawState: StateFlow<SearchUiState> = combine(
        query.map { it.trim() }.debounce(DEBOUNCE_MS).distinctUntilChanged(),
        retryTick,
        settings.activeCatalog,
    ) { q, _, _ -> q }
        .flatMapLatest { q -> if (q.isEmpty()) flowOf(SearchUiState.Idle) else search(q) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState.Idle)

    private val _kind = MutableStateFlow(KindFilter.All)
    val kind: StateFlow<KindFilter> = _kind

    // The filter never refetches: it only narrows the already loaded results.
    val state: StateFlow<SearchUiState> = combine(rawState, _kind) { s, k ->
        if (s !is SearchUiState.Results || k == KindFilter.All) {
            s
        } else {
            val want = if (k == KindFilter.Series) MediaKind.Series else MediaKind.Movie
            s.movies.filter { it.kind == want }.let { if (it.isEmpty()) SearchUiState.Empty else SearchUiState.Results(it) }
        }
    }.stateIn(viewModelScope + Dispatchers.Unconfined, SharingStarted.Eagerly, SearchUiState.Idle) // Unconfined: runs on the emitter thread so Loading/Results are not conflated by a re-dispatch

    fun setKind(k: KindFilter) {
        _kind.value = k
    }

    fun onQueryChange(q: String) {
        query.value = q
    }

    fun retry() {
        retryTick.value++
    }

    private fun search(q: String) = flow {
        emit(SearchUiState.Loading)
        emit(
            try {
                val movies = catalog.search(q)
                if (movies.isEmpty()) SearchUiState.Empty else SearchUiState.Results(movies)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CatalogException) {
                SearchUiState.Error(e.toErrorKind())
            },
        )
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
    }
}
