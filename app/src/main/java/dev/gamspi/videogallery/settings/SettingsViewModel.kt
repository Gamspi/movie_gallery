package dev.gamspi.videogallery.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.torrents.ProviderId
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {
    val tmdbApiKey: StateFlow<String?> = repository.tmdbApiKey
    val kinopoiskApiKey: StateFlow<String?> = repository.kinopoiskApiKey
    val catalogSource: StateFlow<CatalogSource> = repository.catalogSource

    fun tracker(id: ProviderId): StateFlow<TrackerConfig> = repository.tracker(id)

    fun save(
        source: CatalogSource,
        kinopoiskKey: String,
        tmdbKey: String,
        trackers: Map<ProviderId, TrackerConfig> = emptyMap(),
    ) {
        repository.update(source, kinopoiskKey, tmdbKey)
        if (trackers.isNotEmpty()) repository.setTrackers(trackers)
    }
}
