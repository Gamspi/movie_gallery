package dev.gamspi.videogallery.settings

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class SettingsRepository @Inject constructor(private val store: SecretStore) {
    private val _tmdbApiKey = MutableStateFlow(store.get(KEY_TMDB))
    val tmdbApiKey: StateFlow<String?> = _tmdbApiKey

    fun setTmdbApiKey(key: String) {
        val value = key.trim().ifEmpty { null }
        store.put(KEY_TMDB, value)
        _tmdbApiKey.value = value
    }

    private companion object {
        const val KEY_TMDB = "tmdb_api_key"
    }
}
