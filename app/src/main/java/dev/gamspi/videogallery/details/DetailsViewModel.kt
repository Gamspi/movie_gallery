package dev.gamspi.videogallery.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.ui.components.ErrorKind
import dev.gamspi.videogallery.ui.components.toErrorKind
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface DetailsUiState {
    data object Loading : DetailsUiState
    data class Content(val details: MovieDetails) : DetailsUiState
    data class Error(val kind: ErrorKind) : DetailsUiState
}

fun formatRuntime(min: Int?): String? {
    if (min == null || min <= 0) return null
    val h = min / 60
    val m = min % 60
    return when {
        h == 0 -> "$m мин"
        m == 0 -> "$h ч"
        else -> "$h ч $m мин"
    }
}

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val id: Int = checkNotNull(savedStateHandle["id"])
    private val _state = MutableStateFlow<DetailsUiState>(DetailsUiState.Loading)
    val state: StateFlow<DetailsUiState> = _state
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
        _state.value = DetailsUiState.Loading
        _state.value = try {
            DetailsUiState.Content(catalog.details(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: CatalogException) {
            DetailsUiState.Error(e.toErrorKind())
        }
    }
}
