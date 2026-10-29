package dev.gamspi.videogallery.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.ui.components.ErrorKind
import dev.gamspi.videogallery.ui.components.toErrorKind
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class MovieRow(val title: String, val movies: List<Movie>)

sealed interface GalleryUiState {
    data object Loading : GalleryUiState
    data class Content(val rows: List<MovieRow>) : GalleryUiState
    data class Error(val kind: ErrorKind) : GalleryUiState
}

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<GalleryUiState>(GalleryUiState.Loading)
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()
    private val retryTick = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(settings.activeCatalog, retryTick) { active, tick -> active to tick }
                .collectLatest { load() }
        }
    }

    fun retry() {
        retryTick.value++
    }

    private suspend fun load() {
        _state.value = GalleryUiState.Loading
        _state.value = try {
            GalleryUiState.Content(fetchRows())
        } catch (e: CancellationException) {
            throw e
        } catch (e: CatalogException) {
            GalleryUiState.Error(e.toErrorKind())
        }
    }

    private suspend fun fetchRows(): List<MovieRow> = coroutineScope {
        val popular = async { catalog.popular() }
        val genres = catalog.genres().take(GENRE_ROWS)
        val genreRows = genres.map { genre ->
            async {
                try {
                    MovieRow(genre.name, catalog.byGenre(genre.id))
                } catch (e: CatalogException) {
                    null // failed genre row is skipped; CancellationException is not caught here
                }
            }
        }
        listOf(MovieRow("Популярное", popular.await())) + genreRows.mapNotNull { it.await() }.filter { it.movies.isNotEmpty() }
    }

    private companion object {
        const val GENRE_ROWS = 8
    }
}
