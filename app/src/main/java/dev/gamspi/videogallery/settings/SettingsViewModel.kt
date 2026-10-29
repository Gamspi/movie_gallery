package dev.gamspi.videogallery.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {
    val tmdbApiKey: StateFlow<String?> = repository.tmdbApiKey
    val catalogSource: StateFlow<CatalogSource> = repository.catalogSource

    fun save(key: String) = repository.setTmdbApiKey(key)
}
