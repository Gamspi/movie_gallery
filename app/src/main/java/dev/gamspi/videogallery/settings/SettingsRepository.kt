package dev.gamspi.videogallery.settings

import dev.gamspi.videogallery.torrents.ProviderId
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
        refreshActive()
    }

    private val _kinopoiskApiKey = MutableStateFlow(store.get(KEY_KINOPOISK))
    val kinopoiskApiKey: StateFlow<String?> = _kinopoiskApiKey

    fun setKinopoiskApiKey(key: String) {
        val value = key.trim().ifEmpty { null }
        store.put(KEY_KINOPOISK, value)
        _kinopoiskApiKey.value = value
        refreshActive()
    }

    private val _catalogSource = MutableStateFlow(
        CatalogSource.entries.firstOrNull { it.name == store.get(KEY_SOURCE) } ?: CatalogSource.Kinopoisk,
    )
    val catalogSource: StateFlow<CatalogSource> = _catalogSource

    fun setCatalogSource(source: CatalogSource) {
        store.put(KEY_SOURCE, source.name)
        _catalogSource.value = source
        refreshActive()
    }

    fun update(source: CatalogSource, kinopoiskKey: String, tmdbKey: String) {
        val kp = kinopoiskKey.trim().ifEmpty { null }
        val tm = tmdbKey.trim().ifEmpty { null }
        store.put(KEY_KINOPOISK, kp)
        store.put(KEY_TMDB, tm)
        store.put(KEY_SOURCE, source.name)
        _kinopoiskApiKey.value = kp
        _tmdbApiKey.value = tm
        _catalogSource.value = source
        refreshActive()
    }

    private val trackerFlows: Map<ProviderId, MutableStateFlow<TrackerConfig>> =
        ProviderId.entries.associateWith { id ->
            val def = defaultTracker(id)
            MutableStateFlow(
                TrackerConfig(
                    enabled = store.get(enabledKey(id))?.let { it == "true" } ?: def.enabled,
                    baseUrl = store.get(urlKey(id))?.takeIf { it.isNotBlank() } ?: def.baseUrl,
                ),
            )
        }

    fun tracker(id: ProviderId): StateFlow<TrackerConfig> = trackerFlows.getValue(id)

    fun setTrackers(configs: Map<ProviderId, TrackerConfig>) {
        val normalized = configs.mapValues { (id, c) -> TrackerConfig(c.enabled, normalizeTrackerUrl(id, c.baseUrl)) }
        for ((id, c) in normalized) {
            store.put(enabledKey(id), c.enabled.toString())
            store.put(urlKey(id), c.baseUrl)
        }
        for ((id, c) in normalized) trackerFlows.getValue(id).value = c
    }

    private fun enabledKey(id: ProviderId) = "tracker_${id.name}_enabled"
    private fun urlKey(id: ProviderId) = "tracker_${id.name}_url"

    private val _activeCatalog = MutableStateFlow(computeActive())
    val activeCatalog: StateFlow<ActiveCatalog> = _activeCatalog

    private fun computeActive(): ActiveCatalog {
        val source = _catalogSource.value
        val key = if (source == CatalogSource.Kinopoisk) _kinopoiskApiKey.value else _tmdbApiKey.value
        return ActiveCatalog(source, key)
    }

    private fun refreshActive() {
        _activeCatalog.value = computeActive()
    }

    private companion object {
        const val KEY_TMDB = "tmdb_api_key"
        const val KEY_KINOPOISK = "kinopoisk_api_key"
        const val KEY_SOURCE = "catalog_source"
    }
}
