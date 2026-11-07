package dev.gamspi.videogallery.settings

import dev.gamspi.videogallery.net.ProxyConfig
import dev.gamspi.videogallery.subs.SubtitleSettingsSource
import dev.gamspi.videogallery.torrents.ProviderId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
        // Re-read so a cleared field shows the build-time default.
        _poiskkinoApiKey.value = store.get(KEY_POISKKINO)
        refreshActive()
    }

    private val _tmdbApiKey = MutableStateFlow(store.get(KEY_TMDB))
    val tmdbApiKey: StateFlow<String?> = _tmdbApiKey

    fun setTmdbApiKey(key: String) {
        val value = key.trim().ifEmpty { null }
        store.put(KEY_TMDB, value)
        // Re-read so a cleared field shows the build-time default.
        _tmdbApiKey.value = store.get(KEY_TMDB)
        refreshActive()
    }

    private val _kinopoiskApiKey = MutableStateFlow(store.get(KEY_KINOPOISK))
    val kinopoiskApiKey: StateFlow<String?> = _kinopoiskApiKey

    fun setKinopoiskApiKey(key: String) {
        val value = key.trim().ifEmpty { null }
        store.put(KEY_KINOPOISK, value)
        // Re-read so a cleared field shows the build-time default.
        _kinopoiskApiKey.value = store.get(KEY_KINOPOISK)
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
        writeCatalogKeys(poiskkinoKey, kinopoiskKey, tmdbKey)
        store.put(KEY_SOURCE, source.name)
        _catalogSource.value = source
        // Single emission for the combined change.
        refreshActive()
    }

    /** Writes the three catalog keys without touching the selected source. */
    fun setCatalogKeys(poiskkinoKey: String, kinopoiskKey: String, tmdbKey: String) {
        writeCatalogKeys(poiskkinoKey, kinopoiskKey, tmdbKey)
        refreshActive()
    }

    private fun writeCatalogKeys(poiskkinoKey: String, kinopoiskKey: String, tmdbKey: String) {
        store.put(KEY_POISKKINO, poiskkinoKey.trim().ifEmpty { null })
        store.put(KEY_KINOPOISK, kinopoiskKey.trim().ifEmpty { null })
        store.put(KEY_TMDB, tmdbKey.trim().ifEmpty { null })
        _poiskkinoApiKey.value = store.get(KEY_POISKKINO)
        _kinopoiskApiKey.value = store.get(KEY_KINOPOISK)
        _tmdbApiKey.value = store.get(KEY_TMDB)
    }

    private val trackerFlows: Map<ProviderId, MutableStateFlow<TrackerConfig>> =
        ProviderId.entries.associateWith { id ->
            val def = defaultTracker(id)
            val defEnabled = def.enabled ||
                (id == ProviderId.Rutracker && !store.get(KEY_RUTRACKER_USER).isNullOrBlank())
            MutableStateFlow(
                TrackerConfig(
                    enabled = store.get(enabledKey(id))?.let { it == "true" } ?: defEnabled,
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

    /** Writes only the enabled flags; addresses stay as they are. */
    fun setTrackerEnabled(enabled: Map<ProviderId, Boolean>) {
        for ((id, on) in enabled) store.put(enabledKey(id), on.toString())
        for ((id, on) in enabled) trackerFlows.getValue(id).let { it.value = it.value.copy(enabled = on) }
    }

    /** Writes only the addresses (normalized); enabled flags stay as they are. */
    fun setTrackerUrls(urls: Map<ProviderId, String>) {
        val normalized = urls.mapValues { (id, url) -> normalizeTrackerUrl(id, url) }
        for ((id, url) in normalized) store.put(urlKey(id), url)
        for ((id, url) in normalized) trackerFlows.getValue(id).let { it.value = it.value.copy(baseUrl = url) }
    }

    private fun enabledKey(id: ProviderId) = "tracker_${id.name}_enabled"
    private fun urlKey(id: ProviderId) = "tracker_${id.name}_url"

    private val _subtitlePrefs = MutableStateFlow(loadSubtitlePrefs())
    val subtitlePrefs: StateFlow<SubtitlePrefs> = _subtitlePrefs

    private fun loadSubtitlePrefs(): SubtitlePrefs {
        val def = SubtitlePrefs()
        fun style(layer: String, d: LayerStyle) = LayerStyle(
            // Legacy size names map to percents; the next save rewrites the key as a number.
            scalePercent = parseSubScale(store.get("sub_size_$layer")) ?: d.scalePercent,
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
            store.put("sub_size_$layer", normalizeSubScale(s.scalePercent).toString())
            store.put("sub_color_$layer", s.color.name)
            store.put("sub_offset_$layer", s.offsetMs.toString())
        }
        _subtitlePrefs.value = p.copy(
            styleA = p.styleA.copy(scalePercent = normalizeSubScale(p.styleA.scalePercent)),
            styleB = p.styleB.copy(scalePercent = normalizeSubScale(p.styleB.scalePercent)),
        )
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
        _osKey.value = store.get(KEY_OS_KEY)
        _osUser.value = store.get(KEY_OS_USER)
        _osPassword.value = store.get(KEY_OS_PASSWORD)
    }

    private val _rutrackerUser = MutableStateFlow(store.get(KEY_RUTRACKER_USER))
    private val _rutrackerPassword = MutableStateFlow(store.get(KEY_RUTRACKER_PASSWORD))
    val rutrackerUser: String? get() = _rutrackerUser.value
    val rutrackerPassword: String? get() = _rutrackerPassword.value

    /** Stores rutracker credentials; blank values fall back to build-time defaults. */
    fun setRutracker(user: String, password: String) {
        store.put(KEY_RUTRACKER_USER, user.trim().ifEmpty { null })
        store.put(KEY_RUTRACKER_PASSWORD, password.ifEmpty { null })
        _rutrackerUser.value = store.get(KEY_RUTRACKER_USER)
        _rutrackerPassword.value = store.get(KEY_RUTRACKER_PASSWORD)
    }

    private val _proxyEnabled = MutableStateFlow(store.get(KEY_PROXY_ENABLED)?.toBooleanStrictOrNull() ?: false)
    val proxyEnabled: StateFlow<Boolean> = _proxyEnabled
    private val _proxyUrl = MutableStateFlow(store.get(KEY_PROXY_URL))
    val proxyUrl: StateFlow<String?> = _proxyUrl
    private val _proxyKey = MutableStateFlow(store.get(KEY_PROXY_KEY))
    val proxyKey: StateFlow<String?> = _proxyKey

    // Immutable snapshot for per-request reads from OkHttp threads.
    @Volatile
    private var proxySnapshot: ProxyConfig? = computeProxy()

    /** Current proxy, or null unless enabled with a valid http(s) address; no disk I/O. */
    fun proxyConfig(): ProxyConfig? = proxySnapshot

    fun setProxyEnabled(enabled: Boolean) {
        store.put(KEY_PROXY_ENABLED, enabled.toString())
        _proxyEnabled.value = enabled
        proxySnapshot = computeProxy()
    }

    fun setProxyUrl(url: String) {
        store.put(KEY_PROXY_URL, url.trim().ifEmpty { null })
        _proxyUrl.value = store.get(KEY_PROXY_URL)
        proxySnapshot = computeProxy()
    }

    fun setProxyKey(key: String) {
        store.put(KEY_PROXY_KEY, key.trim().ifEmpty { null })
        _proxyKey.value = store.get(KEY_PROXY_KEY)
        proxySnapshot = computeProxy()
    }

    private fun computeProxy(): ProxyConfig? {
        if (!_proxyEnabled.value) return null
        val raw = _proxyUrl.value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // A bare host means https.
        val base = if ("://" in raw) raw else "https://$raw"
        val parsed = base.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        return ProxyConfig(base.trimEnd('/'), _proxyKey.value?.trim()?.ifEmpty { null })
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

    internal companion object {
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
        const val KEY_PROXY_ENABLED = "proxy_enabled"
        const val KEY_PROXY_URL = "proxy_url"
        const val KEY_PROXY_KEY = "proxy_key"
        const val KEY_RUTRACKER_USER = "rutracker_username"
        const val KEY_RUTRACKER_PASSWORD = "rutracker_password"
    }
}
