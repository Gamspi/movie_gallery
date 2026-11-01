package dev.gamspi.videogallery.player

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dev.gamspi.videogallery.stream.StreamManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.settings.SubtitlePrefs
import dev.gamspi.videogallery.subs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class PlayerUiState(
    val title: String,
    val url: String,
    val resumeOffer: Long? = null,
    val error: String? = null,
)

private const val SAVE_INTERVAL_MS = 10_000L

@HiltViewModel
class PlayerViewModel internal constructor(
    savedState: SavedStateHandle,
    private val store: PlaybackStore,
    private val manager: StreamManager,
    private val client: SubtitleClient? = null,
    prefs: StateFlow<SubtitlePrefs> = MutableStateFlow(SubtitlePrefs()),
    private val hashFetcher: suspend (String) -> String? = { null },
    private val clock: () -> Long,
) : ViewModel() {
    @Inject
    constructor(
        savedState: SavedStateHandle, store: PlaybackStore, manager: StreamManager,
        client: OpenSubtitlesClient, settings: SettingsRepository,
    ) : this(savedState, store, manager, client, settings.subtitlePrefs, { url ->
        fetchHashOverHttp(subtitleHashHttp, url)
    }, SystemClock::elapsedRealtime)

    companion object {
        private val subtitleHashHttp by lazy {
            OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        }
    }

    val subtitlePrefs: StateFlow<SubtitlePrefs> = prefs
    private val initialSubtitlePrefs = prefs.value
    private val _layers = MutableStateFlow(mapOf(
        Layer.A to LayerState(style = initialSubtitlePrefs.styleA, offsetMs = initialSubtitlePrefs.styleA.offsetMs),
        Layer.B to LayerState(style = initialSubtitlePrefs.styleB, offsetMs = initialSubtitlePrefs.styleB.offsetMs),
    ))
    val layers: StateFlow<Map<Layer, LayerState>> = _layers
    private val _remoteOptions = MutableStateFlow<List<RemoteSubtitle>>(emptyList())
    val remoteOptions: StateFlow<List<RemoteSubtitle>> = _remoteOptions
    private val _embeddedSlots = MutableStateFlow<Pair<Pair<Int, Int>?, Pair<Int, Int>?>>(null to null)
    val embeddedSlots: StateFlow<Pair<Pair<Int, Int>?, Pair<Int, Int>?>> = _embeddedSlots
    private var embedded = emptyList<TextTrack>()
    private var searchStarted = false
    private var searchNote: String? = null
    private var searchNotes: Map<String, String> = emptyMap()
    private var cleared = false
    private val manual = mutableSetOf<Layer>()
    private val layerJobs = mutableMapOf<Layer, Job>()
    private val generations = mutableMapOf<Layer, Long>()

    fun onTextTracks(tracks: List<TextTrack>) {
        if (cleared) return
        embedded = tracks
        autoSelect()
        if (searchStarted || client == null) return
        searchStarted = true
        viewModelScope.launch {
            val result = client.search(hashFetcher(url), imdbId,
                listOf(initialSubtitlePrefs.languageA, initialSubtitlePrefs.languageB).distinct())
            ensureActive()
            if (cleared) return@launch
            when (result) {
                is SubsResult.Ok -> {
                    _remoteOptions.value = result.value
                    searchNotes = result.languageNotes
                }
                else -> searchNote = result.note()
            }
            autoSelect()
        }
    }

    private fun autoSelect() {
        for (layer in Layer.entries) {
            if (layer in manual) continue
            val other = if (layer == Layer.A) Layer.B else Layer.A
            val taken = (_layers.value[other]?.source as? LayerSource.Embedded)?.track
            val language = if (layer == Layer.A) initialSubtitlePrefs.languageA else initialSubtitlePrefs.languageB
            val source = chooseSource(language, embedded, _remoteOptions.value, taken)
            if (_layers.value.getValue(layer).source != source) applySource(layer, source)
            else if (source == LayerSource.None) updateLayer(layer) { it.copy(note = missingNote(layer)) }
        }
    }

    fun select(layer: Layer, source: LayerSource) {
        if (cleared) return
        if (source is LayerSource.Embedded && (!source.track.supported || source.track !in embedded)) return
        manual += layer
        if (source is LayerSource.Embedded) {
            val other = if (layer == Layer.A) Layer.B else Layer.A
            if ((_layers.value[other]?.source as? LayerSource.Embedded)?.track?.group == source.track.group) {
                manual += other
                applySource(other, LayerSource.None)
            }
        }
        applySource(layer, source)
    }

    private fun applySource(layer: Layer, source: LayerSource) {
        layerJobs.remove(layer)?.cancel()
        val generation = (generations[layer] ?: 0) + 1
        generations[layer] = generation
        updateLayer(layer) { it.copy(source = source, track = null,
            note = if (source == LayerSource.None) missingNote(layer) else null) }
        updateSlots()
        if (source !is LayerSource.Remote) return
        layerJobs[layer] = viewModelScope.launch {
            val result = client?.download(source.sub.fileId) ?: SubsResult.NoKey
            ensureActive()
            val track = if (result is SubsResult.Ok) withContext(Dispatchers.Default) {
                SubtitleParser.parse(result.value, null)
            } else null
            ensureActive()
            if (cleared || generations[layer] != generation) return@launch
            updateLayer(layer) { it.copy(track = track, note = when {
                result !is SubsResult.Ok -> result.note()
                track?.cues.isNullOrEmpty() -> "ошибка загрузки"
                else -> null
            }) }
        }
    }

    private fun updateLayer(layer: Layer, update: (LayerState) -> LayerState) {
        _layers.value = _layers.value + (layer to update(_layers.value.getValue(layer)))
    }

    private fun missingNote(layer: Layer): String {
        val language = if (layer == Layer.A) initialSubtitlePrefs.languageA else initialSubtitlePrefs.languageB
        return searchNotes.entries.firstOrNull { sameLanguage(language, it.key) }?.value
            ?: searchNote ?: "нет субтитров"
    }

    private fun updateSlots() {
        fun slot(layer: Layer) = (_layers.value[layer]?.source as? LayerSource.Embedded)
            ?.track?.let { it.group to it.index }
        _embeddedSlots.value = slot(Layer.A) to slot(Layer.B)
    }

    fun shiftOffset(layer: Layer, deltaMs: Long) {
        if (cleared) return
        updateLayer(layer) { it.copy(offsetMs = (it.offsetMs + deltaMs.coerceIn(-20_000, 20_000)).coerceIn(-10_000, 10_000)) }
    }

    fun textAt(layer: Layer, positionMs: Long): String? {
        val state = _layers.value.getValue(layer)
        return if (state.source is LayerSource.Remote) state.track?.textAt(positionMs - state.offsetMs) else null
    }

    private fun SubsResult<*>.note(): String = when (this) {
        SubsResult.NoKey -> "нужен ключ OpenSubtitles"
        SubsResult.BadKey -> "ключ OpenSubtitles неверный"
        SubsResult.LimitReached -> "лимит OpenSubtitles на сегодня"
        else -> "ошибка загрузки"
    }

    private val url: String = savedState.get<String>("url").orEmpty()
    private val key: String = savedState.get<String>("key").orEmpty().ifEmpty { "url:$url" }
    internal val imdbId: String? = savedState.get<String>("imdb")?.ifBlank { null }
    private val _state = MutableStateFlow(
        PlayerUiState(title = savedState.get<String>("title").orEmpty(), url = url),
    )
    val state: StateFlow<PlayerUiState> = _state

    private val isTorrent = key.startsWith("bt:")
    private val sessionId = manager.currentSessionId()

    /** Download status line; only for torrent sessions. */
    val statusLine: StateFlow<String?> = if (isTorrent) manager.statusLine else MutableStateFlow(null)

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    override fun onCleared() {
        cleared = true
        layerJobs.values.forEach { it.cancel() }
        if (isTorrent) manager.stopIf(sessionId)
        super.onCleared()
    }

    private var resumeComputed = false
    private var lastSaveAt: Long? = null

    fun onDuration(durationMs: Long) {
        if (resumeComputed || durationMs <= 0) return
        resumeComputed = true
        _state.value = _state.value.copy(resumeOffer = resumeFrom(store.position(key), durationMs))
    }

    fun dismissResume() {
        _state.value = _state.value.copy(resumeOffer = null)
    }

    fun onProgress(positionMs: Long, durationMs: Long) {
        val now = clock()
        val last = lastSaveAt
        if (last != null && now - last < SAVE_INTERVAL_MS) return
        if (positionMs <= 0) return
        lastSaveAt = now
        store.savePosition(key, positionMs)
    }

    fun onPauseOrExit(positionMs: Long, durationMs: Long) {
        if (positionMs <= 0) return
        lastSaveAt = clock()
        if (isFinished(positionMs, durationMs)) store.clearPosition(key) else store.savePosition(key, positionMs)
    }

    fun chooseInitialAudio(tracks: List<AudioTrack>): AudioTrack? =
        chooseAudio(tracks, store.audioPreference())

    fun onAudioSelected(t: AudioTrack) {
        val lang = t.language?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) } ?: return
        store.saveAudioPreference(AudioPreference(lang, t.label))
    }

    fun onError(message: String) {
        _state.value = _state.value.copy(error = message)
    }
}
