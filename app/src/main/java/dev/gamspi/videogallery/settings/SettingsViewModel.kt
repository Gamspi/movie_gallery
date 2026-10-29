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

    fun save(key: String) = repository.setTmdbApiKey(key)
}
