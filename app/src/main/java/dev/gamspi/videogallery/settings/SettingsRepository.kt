package dev.gamspi.videogallery.settings

import dev.gamspi.videogallery.subs.SubtitleSettingsSource
import dev.gamspi.videogallery.torrents.ProviderId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class SettingsRepository @Inject constructor(private val store: SecretStore) : SubtitleSettingsSource {
    private val _torrentStoragePrefs = MutableStateFlow(
        TorrentStoragePrefs(
            keepAfterPlayback = store.get(KEY_TORRENT_KEEP)?.toBooleanStrictOrNull() ?: false,
            limitGiB = store.get(KEY_TORRENT_LIMIT)?.toIntOrNull() ?: 10,
        ).normalized(),
    )
    val torrentStoragePrefs: StateFlow<TorrentStoragePrefs> = _torrentStoragePrefs

    fun setTorrentStoragePrefs(prefs: TorrentStoragePrefs) {
        val normalized = prefs.normalized()
        store.put(KEY_TORRENT_KEEP, normalized.keepAfterPlayback.toString())
        store.put(KEY_TORRENT_LIMIT, normalized.limitGiB.toString())
        _torrentStoragePrefs.value = normalized
    }

    private val _poiskkinoApiKey = MutableStateFlow(store.get(KEY_POISKKINO))
    val poiskkinoApiKey: StateFlow<String?> = _poiskkinoApiKey

    fun setPoiskkinoApiKey(key: String) {
        val value = key.trim().ifEmpty { null }
        store.put(KEY_POISKKINO, value)
        _poiskkinoApiKey.value = value
        refreshActive()
    }

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

    private val _testUrl = MutableStateFlow(store.get(KEY_TEST_URL))
    val testUrl: StateFlow<String?> = _testUrl

    fun setTestUrl(url: String) {
        val value = url.trim().ifEmpty { null }
        store.put(KEY_TEST_URL, value)
        _testUrl.value = value
    }

    private val _catalogSource = MutableStateFlow(
        CatalogSource.entries.firstOrNull { it.name == store.get(KEY_SOURCE) } ?: CatalogSource.PoiskKino,
    )
    val catalogSource: StateFlow<CatalogSource> = _catalogSource

    fun setCatalogSource(source: CatalogSource) {
        store.put(KEY_SOURCE, source.name)
        _catalogSource.value = source
        refreshActive()
    }

    fun update(source: CatalogSource, poiskkinoKey: String, kinopoiskKey: String, tmdbKey: String) {
        val pk = poiskkinoKey.trim().ifEmpty { null }
        val kp = kinopoiskKey.trim().ifEmpty { null }
        val tm = tmdbKey.trim().ifEmpty { null }
        store.put(KEY_POISKKINO, pk)
        store.put(KEY_KINOPOISK, kp)
        store.put(KEY_TMDB, tm)
        store.put(KEY_SOURCE, source.name)
        _poiskkinoApiKey.value = pk
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

    private val _subtitlePrefs = MutableStateFlow(loadSubtitlePrefs())
    val subtitlePrefs: StateFlow<SubtitlePrefs> = _subtitlePrefs

    private fun loadSubtitlePrefs(): SubtitlePrefs {
        val def = SubtitlePrefs()
        fun style(layer: String, d: LayerStyle) = LayerStyle(
            size = SubSize.entries.firstOrNull { it.name == store.get("sub_size_$layer") } ?: d.size,
            color = SubColor.entries.firstOrNull { it.name == store.get("sub_color_$layer") } ?: d.color,
            offsetMs = store.get("sub_offset_$layer")?.toLongOrNull() ?: d.offsetMs,
        )
        return SubtitlePrefs(
            languageA = store.get("sub_lang_a")?.takeIf { it.isNotBlank() } ?: def.languageA,
            languageB = store.get("sub_lang_b")?.takeIf { it.isNotBlank() } ?: def.languageB,
            styleA = style("a", def.styleA),
            styleB = style("b", def.styleB),
        )
    }

    fun setSubtitlePrefs(p: SubtitlePrefs) {
        store.put("sub_lang_a", p.languageA)
        store.put("sub_lang_b", p.languageB)
        for ((layer, s) in listOf("a" to p.styleA, "b" to p.styleB)) {
            store.put("sub_size_$layer", s.size.name)
            store.put("sub_color_$layer", s.color.name)
            store.put("sub_offset_$layer", s.offsetMs.toString())
        }
        _subtitlePrefs.value = p
    }

    private val _osKey = MutableStateFlow(store.get(KEY_OS_KEY))
    private val _osUser = MutableStateFlow(store.get(KEY_OS_USER))
    private val _osPassword = MutableStateFlow(store.get(KEY_OS_PASSWORD))
    override val openSubtitlesKey: String? get() = _osKey.value
    override val openSubtitlesUser: String? get() = _osUser.value
    override val openSubtitlesPassword: String? get() = _osPassword.value

    fun setOpenSubtitles(key: String, user: String, password: String) {
        val k = key.trim().ifEmpty { null }
        val u = user.trim().ifEmpty { null }
        val p = password.ifEmpty { null }
        store.put(KEY_OS_KEY, k)
        store.put(KEY_OS_USER, u)
        store.put(KEY_OS_PASSWORD, p)
        _osKey.value = k
        _osUser.value = u
        _osPassword.value = p
    }

    private val _activeCatalog =MutableStateFlow(computeActive())
    val activeCatalog: StateFlow<ActiveCatalog> = _activeCatalog

    private fun computeActive(): ActiveCatalog {
        val source = _catalogSource.value
        val key = when (source) {
            CatalogSource.PoiskKino -> _poiskkinoApiKey.value
            CatalogSource.Kinopoisk -> _kinopoiskApiKey.value
            CatalogSource.Tmdb -> _tmdbApiKey.value
        }
        return ActiveCatalog(source, key)
    }

    private fun refreshActive() {
        _activeCatalog.value = computeActive()
    }

    private companion object {
        const val KEY_TORRENT_KEEP = "torrent_keep_after_playback"
        const val KEY_TORRENT_LIMIT = "torrent_limit_gib"
        const val KEY_POISKKINO = "poiskkino_api_key"
        const val KEY_TMDB = "tmdb_api_key"
        const val KEY_KINOPOISK = "kinopoisk_api_key"
        const val KEY_SOURCE = "catalog_source"
        const val KEY_TEST_URL = "test_url"
        const val KEY_OS_KEY = "opensubtitles_key"
        const val KEY_OS_USER = "opensubtitles_user"
        const val KEY_OS_PASSWORD = "opensubtitles_password"
    }
}
