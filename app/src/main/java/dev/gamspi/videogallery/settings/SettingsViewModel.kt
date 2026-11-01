package dev.gamspi.videogallery.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.stream.TorrentCacheStore
import dev.gamspi.videogallery.torrents.ProviderId
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class TorrentCacheClearState(val busy: Boolean = false, val message: String? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val torrentCache: TorrentCacheStore? = null,
) : ViewModel() {
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

    fun setOpenSubtitles(key: String, user: String, password: String) =
        repository.setOpenSubtitles(key, user, password)

    fun cycleLanguage(layer: SubLayer) {
        val p = subtitlePrefs.value
        fun next(cur: String) = SUB_LANGUAGES[(SUB_LANGUAGES.indexOf(cur) + 1) % SUB_LANGUAGES.size]
        repository.setSubtitlePrefs(
            if (layer == SubLayer.A) p.copy(languageA = next(p.languageA)) else p.copy(languageB = next(p.languageB)),
        )
    }

    fun cycleSize(layer: SubLayer) = updateStyle(layer) {
        it.copy(size = SubSize.entries[(it.size.ordinal + 1) % SubSize.entries.size])
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

    fun save(
        source: CatalogSource,
        poiskkinoKey: String,
        kinopoiskKey: String,
        tmdbKey: String,
        trackers: Map<ProviderId, TrackerConfig> = emptyMap(),
    ) {
        repository.update(source, poiskkinoKey, kinopoiskKey, tmdbKey)
        repository.setTrackers(trackers)
    }
}
