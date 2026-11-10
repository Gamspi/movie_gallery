package dev.gamspi.videogallery.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.stream.TorrentCacheStore
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.translate.ModelState
import dev.gamspi.videogallery.translate.TranslationCache
import dev.gamspi.videogallery.translate.TranslationModels
import dev.gamspi.videogallery.translate.message
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TorrentCacheClearState(val busy: Boolean = false, val message: String? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val torrentCache: TorrentCacheStore? = null,
    private val models: TranslationModels? = null,
    private val translationCache: TranslationCache? = null,
    private val listSync: dev.gamspi.videogallery.watchlist.ListSyncService? = null,
) : ViewModel() {
    val listSyncEnabled = listSync?.enabled ?: MutableStateFlow(false)
    val listSyncStatus = listSync?.status ?: MutableStateFlow<dev.gamspi.videogallery.watchlist.ListSyncStatus>(dev.gamspi.videogallery.watchlist.ListSyncStatus.Disabled)
    val listSyncError = listSync?.error ?: MutableStateFlow<String?>(null)
    val listQueue: StateFlow<Int> = listSync?.store?.snapshots?.map { it.pending.size }
        ?.stateIn(viewModelScope, SharingStarted.Eagerly, 0) ?: MutableStateFlow(0)
    init { listSync?.loadLocal() }
    fun toggleListSync() { viewModelScope.launch { listSync?.toggle() } }
    fun syncListNow() { listSync?.retryNow() }
    /** Translation model state; NotDownloaded when translation is unavailable. */
    val modelState: StateFlow<ModelState> = models?.state ?: MutableStateFlow(ModelState.NotDownloaded)
    val modelStatus: StateFlow<String> = models?.state?.map { it.status() }
        ?.stateIn(viewModelScope, SharingStarted.Eagerly, models.state.value.status())
        ?: MutableStateFlow(ModelState.NotDownloaded.status())
    private val _modelMessage = MutableStateFlow<String?>(null)
    val modelMessage: StateFlow<String?> = _modelMessage
    private var modelJob: Job? = null

    fun downloadModels() {
        val models = models ?: return
        if (modelJob?.isActive == true) return
        _modelMessage.value = null
        // Failures land in ModelState.
        modelJob = viewModelScope.launch { models.ensureDownloaded() }
    }

    /** Deletes both models and the translation cache built with them. */
    fun deleteModels() {
        val models = models ?: return
        if (modelJob?.isActive == true) return
        _modelMessage.value = null
        modelJob = viewModelScope.launch {
            try {
                models.delete()
                translationCache?.clear()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _modelMessage.value = "Не удалось удалить модели перевода"
            }
        }
    }

    val torrentStoragePrefs: StateFlow<TorrentStoragePrefs> = repository.torrentStoragePrefs
    private val _torrentCacheClear = MutableStateFlow(TorrentCacheClearState())
    val torrentCacheClear: StateFlow<TorrentCacheClearState> = _torrentCacheClear

    fun toggleTorrentRetention() {
        val prefs = torrentStoragePrefs.value
        repository.setTorrentStoragePrefs(prefs.copy(keepAfterPlayback = !prefs.keepAfterPlayback))
    }

    fun changeTorrentLimit(deltaGiB: Int) {
        val prefs = torrentStoragePrefs.value
        val limit = (prefs.limitGiB.toLong() + deltaGiB).coerceIn(1, 100).toInt()
        repository.setTorrentStoragePrefs(prefs.copy(limitGiB = limit))
    }

    fun clearTorrentCache() {
        if (_torrentCacheClear.value.busy) return
        _torrentCacheClear.value = TorrentCacheClearState(busy = true)
        viewModelScope.launch {
            try {
                val cache = torrentCache ?: error("Torrent cache unavailable")
                cache.clearInactive()
                _torrentCacheClear.value = TorrentCacheClearState(message = "Сохранённые торренты очищены. Активная раздача сохранена.")
            } catch (cancelled: CancellationException) {
                _torrentCacheClear.value = TorrentCacheClearState()
                throw cancelled
            } catch (_: Exception) {
                _torrentCacheClear.value = TorrentCacheClearState(message = "Не удалось очистить сохранённые торренты. Попробуйте ещё раз.")
            }
        }
    }

    val poiskkinoApiKey: StateFlow<String?> = repository.poiskkinoApiKey
    val tmdbApiKey: StateFlow<String?> = repository.tmdbApiKey
    val kinopoiskApiKey: StateFlow<String?> = repository.kinopoiskApiKey
    val catalogSource: StateFlow<CatalogSource> = repository.catalogSource

    val testUrl: StateFlow<String?> = repository.testUrl

    fun setTestUrl(url: String) = repository.setTestUrl(url)

    val subtitlePrefs: StateFlow<SubtitlePrefs> = repository.subtitlePrefs

    val openSubtitlesKey: String? get() = repository.openSubtitlesKey
    val openSubtitlesUser: String? get() = repository.openSubtitlesUser
    val openSubtitlesPassword: String? get() = repository.openSubtitlesPassword

    /** Writes OpenSubtitles credentials; false when the store write fails (values are never logged). */
    fun saveOpenSubtitles(key: String, user: String, password: String): Boolean =
        runSave { repository.setOpenSubtitles(key, user, password) }

    fun cycleLanguage(layer: SubLayer) {
        val p = subtitlePrefs.value
        fun next(cur: String) = SUB_LANGUAGES[(SUB_LANGUAGES.indexOf(cur) + 1) % SUB_LANGUAGES.size]
        repository.setSubtitlePrefs(
            if (layer == SubLayer.A) p.copy(languageA = next(p.languageA)) else p.copy(languageB = next(p.languageB)),
        )
    }

    /** Changes the layer's subtitle scale by [steps] steps of [SUB_SCALE_STEP] percent. */
    fun changeScale(layer: SubLayer, steps: Int) = updateStyle(layer) {
        it.copy(scalePercent = stepSubScale(it.scalePercent, steps))
    }

    fun cycleColor(layer: SubLayer) = updateStyle(layer) {
        it.copy(color = SubColor.entries[(it.color.ordinal + 1) % SubColor.entries.size])
    }

    fun changeOffset(layer: SubLayer, deltaMs: Long) = updateStyle(layer) {
        it.copy(offsetMs = (it.offsetMs + deltaMs).coerceIn(-SUB_OFFSET_MAX_MS, SUB_OFFSET_MAX_MS))
    }

    private fun updateStyle(layer: SubLayer, f: (LayerStyle) -> LayerStyle) {
        val p = subtitlePrefs.value
        repository.setSubtitlePrefs(
            if (layer == SubLayer.A) p.copy(styleA = f(p.styleA)) else p.copy(styleB = f(p.styleB)),
        )
    }

    fun tracker(id: ProviderId): StateFlow<TrackerConfig> = repository.tracker(id)

    val rememberReleaseFilter: StateFlow<Boolean> = repository.rememberReleaseFilter

    fun toggleRememberReleaseFilter(): Boolean =
        runSave { repository.setRememberReleaseFilter(!rememberReleaseFilter.value) }

    /** Writes the selected catalog source only; false when the store write fails. */
    fun saveCatalogSource(source: CatalogSource): Boolean = runSave { repository.setCatalogSource(source) }

    /** Writes catalog API keys only; false when the store write fails (values are never logged). */
    fun saveCatalogKeys(poiskkinoKey: String, kinopoiskKey: String, tmdbKey: String): Boolean =
        runSave { repository.setCatalogKeys(poiskkinoKey, kinopoiskKey, tmdbKey) }

    /** Writes tracker enabled flags only; addresses are untouched. */
    fun saveTrackerEnabled(enabled: Map<ProviderId, Boolean>): Boolean =
        runSave { repository.setTrackerEnabled(enabled) }

    /** Writes tracker addresses only; enabled flags are untouched. */
    fun saveTrackerUrls(urls: Map<ProviderId, String>): Boolean =
        runSave { repository.setTrackerUrls(urls) }

    val rutrackerUser: String? get() = repository.rutrackerUser
    val rutrackerPassword: String? get() = repository.rutrackerPassword

    /** Writes rutracker credentials; false when the store write fails (values are never logged). */
    fun saveRutracker(user: String, password: String): Boolean =
        runSave { repository.setRutracker(user, password) }

    val proxyEnabled: StateFlow<Boolean> = repository.proxyEnabled
    val proxyUrl: StateFlow<String?> = repository.proxyUrl
    val proxyKey: StateFlow<String?> = repository.proxyKey

    /** Applies immediately; false when the store write fails. */
    fun toggleProxy(): Boolean = runSave { repository.setProxyEnabled(!proxyEnabled.value) }

    /** Writes proxy address and key; false when the store write fails (values are never logged). */
    fun saveNetwork(url: String, key: String): Boolean = runSave {
        repository.setProxyUrl(url)
        repository.setProxyKey(key)
    }

    /** Writes one catalog key only; false for non-catalog services or a failed store write. */
    fun saveCatalogKey(service: KeyService, key: String): Boolean = when (service) {
        KeyService.PoiskKino -> runSave { repository.setPoiskkinoApiKey(key) }
        KeyService.Kinopoisk -> runSave { repository.setKinopoiskApiKey(key) }
        KeyService.Tmdb -> runSave { repository.setTmdbApiKey(key) }
        else -> false
    }

    /** Whether the service's main value is filled; the value itself is never exposed here. */
    fun isKeySet(service: KeyService): Boolean = when (service) {
        KeyService.PoiskKino -> !poiskkinoApiKey.value.isNullOrBlank()
        KeyService.Kinopoisk -> !kinopoiskApiKey.value.isNullOrBlank()
        KeyService.Tmdb -> !tmdbApiKey.value.isNullOrBlank()
        KeyService.Rutracker -> !rutrackerUser.isNullOrBlank()
        KeyService.OpenSubtitles -> !openSubtitlesKey.isNullOrBlank()
        KeyService.Proxy -> !proxyUrl.value.isNullOrBlank() && !proxyKey.value.isNullOrBlank()
        KeyService.Rutor, KeyService.Apibay, KeyService.Yts ->
            tracker(requireNotNull(service.tracker)).value.baseUrl.isNotBlank()
    }

    private inline fun runSave(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private fun ModelState.status(): String = when (this) {
    ModelState.NotDownloaded -> "Модели не скачаны"
    is ModelState.Downloading -> "Загрузка…"
    ModelState.Ready -> "Модели готовы"
    is ModelState.Failed -> reason.message()
}
