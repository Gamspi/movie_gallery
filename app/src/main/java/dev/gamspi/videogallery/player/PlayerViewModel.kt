package dev.gamspi.videogallery.player

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.history.FINISHED_PROGRESS
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.history.SeriesStore
import dev.gamspi.videogallery.history.seriesKey
import dev.gamspi.videogallery.stream.EpisodeFile
import dev.gamspi.videogallery.stream.EpisodeRef
import dev.gamspi.videogallery.history.WatchEntry
import dev.gamspi.videogallery.history.WatchHistoryStore
import dev.gamspi.videogallery.stream.StreamManager
import dev.gamspi.videogallery.stream.StreamState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.settings.SubtitlePrefs
import dev.gamspi.videogallery.settings.stepSubScale
import dev.gamspi.videogallery.subs.*
import dev.gamspi.videogallery.translate.CacheKey
import dev.gamspi.videogallery.translate.EmbeddedTranslation
import dev.gamspi.videogallery.translate.ModelFailure
import dev.gamspi.videogallery.translate.ModelState
import dev.gamspi.videogallery.translate.SubtitleTranslator
import dev.gamspi.videogallery.translate.TrackTranslation
import dev.gamspi.videogallery.translate.TranslateLang
import dev.gamspi.videogallery.translate.TranslationCache
import dev.gamspi.videogallery.translate.TranslationModels
import dev.gamspi.videogallery.translate.message
import dev.gamspi.videogallery.translate.other
import dev.gamspi.videogallery.translate.translateLang
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class PlayerUiState(
    val title: String,
    val url: String,
    val resumeOffer: Long? = null,
    val error: String? = null,
    val failure: PlayerFailure? = null,
)

private const val SAVE_INTERVAL_MS = 10_000L
private const val LIST_INTERVAL_MS = 60_000L

/** Forward position jump between subtitle frames that reorders the background translation. */
private const val TRANSLATION_SEEK_JUMP_MS = 5_000L

/** Any backward jump beyond playback jitter reorders it: earlier cues sit at the queue tail. */
private const val TRANSLATION_SEEK_BACK_MS = 1_000L
private const val NO_SOURCE_NOTE = "Нет субтитров для перевода"
private const val TRANSLATING_NOTE = "Перевод…"
private const val UNAVAILABLE_NOTE = "Перевод недоступен"

private fun downloadNote(state: ModelState): String =
    "Загрузка модели…" + ((state as? ModelState.Downloading)?.progress?.let { " $it%" } ?: "")

private fun Layer.other() = if (this == Layer.A) Layer.B else Layer.A

@HiltViewModel
class PlayerViewModel internal constructor(
    savedState: SavedStateHandle,
    private val store: PlaybackStore,
    private val manager: StreamManager,
    private val client: SubtitleClient? = null,
    prefs: StateFlow<SubtitlePrefs> = MutableStateFlow(SubtitlePrefs()),
    private val hashFetcher: suspend (String) -> String? = { null },
    private val models: TranslationModels? = null,
    private val translator: SubtitleTranslator? = null,
    private val cache: TranslationCache? = null,
    private val translationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val history: WatchHistoryStore? = null,
    private val saveSubtitlePrefs: (SubtitlePrefs) -> Unit = {},
    private val seriesStore: SeriesStore? = null,
    private val episodeFiles: () -> List<EpisodeFile> = manager::episodeFiles,
    private val finishRelease: (Long) -> Unit = manager::finishRelease,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val listSync: dev.gamspi.videogallery.watchlist.PlaybackList? = null,
    private val clock: () -> Long,
) : ViewModel() {
    @Inject
    constructor(
        savedState: SavedStateHandle, store: PlaybackStore, manager: StreamManager,
        client: OpenSubtitlesClient, settings: SettingsRepository,
        models: TranslationModels, translator: SubtitleTranslator, cache: TranslationCache,
        history: WatchHistoryStore, seriesStore: SeriesStore,
        listSync: dev.gamspi.videogallery.watchlist.ListSyncService,
    ) : this(savedState, store, manager, client, settings.subtitlePrefs, { url ->
        fetchHashOverHttp(subtitleHashHttp, url)
    }, models, translator, cache, Dispatchers.Default, history, settings::setSubtitlePrefs,
        seriesStore = seriesStore, listSync = listSync, clock = SystemClock::elapsedRealtime)

    companion object {
        private val subtitleHashHttp by lazy {
            OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        }
    }

    val subtitlePrefs: StateFlow<SubtitlePrefs> = prefs

    /** Changes a layer's subtitle scale by [steps] steps and persists it; the overlay follows the prefs. */
    fun changeSubtitleScale(layer: Layer, steps: Int) {
        val p = subtitlePrefs.value
        val updated = if (layer == Layer.A) {
            p.copy(styleA = p.styleA.copy(scalePercent = stepSubScale(p.styleA.scalePercent, steps)))
        } else {
            p.copy(styleB = p.styleB.copy(scalePercent = stepSubScale(p.styleB.scalePercent, steps)))
        }
        if (updated != p) saveSubtitlePrefs(updated)
    }
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

    private class RemoteTranslation(val translation: TrackTranslation, var positionMs: Long)
    private class EmbeddedRuntime(val translation: EmbeddedTranslation, val scope: CoroutineScope, val generation: Long)
    private val remoteTranslations = mutableMapOf<Layer, RemoteTranslation>()
    private val embeddedTranslations = mutableMapOf<Layer, EmbeddedRuntime>()
    private val lastEmbeddedText = mutableMapOf<Layer, String>()
    private val embeddedJobs = mutableMapOf<Layer, Job>()
    private var lastPositionMs = 0L
    private val _translatedEmbedded = MutableStateFlow<Map<Layer, String?>>(emptyMap())

    /** Translated text of embedded cues, keyed by the target layer. */
    val translatedEmbedded: StateFlow<Map<Layer, String?>> = _translatedEmbedded

    fun onTextTracks(tracks: List<TextTrack>) {
        if (cleared) return
        embedded = tracks
        autoSelect()
        if (searchStarted || client == null) return
        searchStarted = true
        viewModelScope.launch {
            val result = client.search(hashFetcher(url), imdbId,
                listOf(initialSubtitlePrefs.languageA, initialSubtitlePrefs.languageB).distinct(),
                seriesRef()?.season, seriesRef()?.episode)
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
        if (source is LayerSource.Translated && source !in translationOptions(layer)) return
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
        remoteTranslations.remove(layer)?.translation?.cancel()
        embeddedTranslations.remove(layer)
        embeddedJobs.remove(layer)?.cancel()
        lastEmbeddedText.remove(layer)
        if (layer in _translatedEmbedded.value) _translatedEmbedded.value -= layer
        val generation = (generations[layer] ?: 0) + 1
        generations[layer] = generation
        updateLayer(layer) { it.copy(source = source, track = null,
            note = if (source == LayerSource.None) missingNote(layer) else null) }
        updateSlots()
        when (source) {
            is LayerSource.Remote -> startDownload(layer, source, generation)
            is LayerSource.Translated -> startTranslation(layer, source, generation)
            else -> {}
        }
        // A layer translating this one starts over with the new source.
        if (source !is LayerSource.Translated) {
            val dependent = layer.other()
            val translated = _layers.value.getValue(dependent).source
            if (translated is LayerSource.Translated && translated.from == layer) applySource(dependent, translated)
        }
    }

    private fun startDownload(layer: Layer, source: LayerSource.Remote, generation: Long) {
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

    /** Translation sources the menu may offer for [layer]; empty when unavailable. */
    fun translationOptions(layer: Layer): List<LayerSource.Translated> {
        if (translator == null || models == null || cache == null) return emptyList()
        val from = layer.other()
        val origin = _layers.value.getValue(from).source
        val language = when (origin) {
            is LayerSource.Remote -> origin.sub.language
            is LayerSource.Embedded -> origin.track.language
            else -> return emptyList()
        }
        // Unknown embedded language: the user picks the direction.
        if (origin is LayerSource.Embedded && subtitleLanguage(language) == null) {
            return TranslateLang.entries.map { LayerSource.Translated(from, it) }
        }
        val sourceLang = translateLang(language) ?: return emptyList()
        val target = translateLang(if (layer == Layer.A) initialSubtitlePrefs.languageA else initialSubtitlePrefs.languageB)
        return if (target == sourceLang.other()) listOf(LayerSource.Translated(from, sourceLang)) else emptyList()
    }

    private fun startTranslation(layer: Layer, source: LayerSource.Translated, generation: Long) {
        fun current() = !cleared && generations[layer] == generation
        fun note(text: String?) {
            if (current() && _layers.value.getValue(layer).note != text) updateLayer(layer) { it.copy(note = text) }
        }
        val models = models
        val translator = translator
        val cache = cache
        if (models == null || translator == null || cache == null || source !in translationOptions(layer)) {
            note(NO_SOURCE_NOTE)
            return
        }
        val origin = _layers.value.getValue(source.from).source
        note(when {
            models.state.value != ModelState.Ready -> downloadNote(models.state.value)
            origin is LayerSource.Remote -> TRANSLATING_NOTE
            else -> null
        })
        layerJobs[layer] = viewModelScope.launch {
            if (models.state.value != ModelState.Ready) {
                coroutineScope {
                    val watcher = launch {
                        models.state.collect { if (it is ModelState.Downloading) note(downloadNote(it)) }
                    }
                    models.ensureDownloaded()
                    watcher.cancel()
                }
                ensureActive()
                if (!current()) return@launch
                when (val state = models.state.value) {
                    ModelState.Ready -> {}
                    is ModelState.Failed -> return@launch note(state.reason.message())
                    else -> return@launch note(ModelFailure.Other.message())
                }
            }
            when (origin) {
                is LayerSource.Remote -> {
                    note(TRANSLATING_NOTE)
                    val ready = _layers.first {
                        val s = it.getValue(source.from)
                        s.source != origin || s.note != null || !s.track?.cues.isNullOrEmpty()
                    }.getValue(source.from)
                    val track = ready.track
                    if (ready.source != origin || track == null || track.cues.isEmpty()) {
                        return@launch note(NO_SOURCE_NOTE)
                    }
                    coroutineScope {
                        val start = lastPositionMs - ready.offsetMs
                        val translation = TrackTranslation(track,
                            CacheKey(origin.sub.fileId, source.sourceLang.other(), translator.modelVersion),
                            source.sourceLang, translator, cache, this, start, translationDispatcher)
                        remoteTranslations[layer] = RemoteTranslation(translation, start)
                        // Collected inside the layer job: values of a cancelled translation never get here.
                        combine(translation.translated, translation.failed) { t, failed -> t to failed }
                            .collect { (translated, failed) ->
                                ensureActive()
                                if (!current()) return@collect
                                updateLayer(layer) { it.copy(track = translated, note = when {
                                    failed -> UNAVAILABLE_NOTE
                                    translated.cues.isEmpty() -> TRANSLATING_NOTE
                                    else -> null
                                }) }
                            }
                    }
                }
                is LayerSource.Embedded -> coroutineScope {
                    note(null)
                    val runtime = EmbeddedRuntime(EmbeddedTranslation(translator, source.sourceLang), this, generation)
                    embeddedTranslations[layer] = runtime
                    lastEmbeddedText[source.from]?.let { translateCue(layer, runtime, it) }
                    awaitCancellation()
                }
                else -> note(NO_SOURCE_NOTE)
            }
        }
    }

    /** Embedded cue text of [layer] changed; null when no cue is shown. */
    fun onEmbeddedText(layer: Layer, text: String?) {
        if (cleared) return
        val cue = text?.takeIf { it.isNotBlank() }
        if (cue == null) lastEmbeddedText.remove(layer) else lastEmbeddedText[layer] = cue
        val target = layer.other()
        val source = _layers.value.getValue(target).source
        if (source !is LayerSource.Translated || source.from != layer) return
        val runtime = embeddedTranslations[target] ?: return
        if (cue == null) {
            embeddedJobs.remove(target)?.cancel()
            runtime.translation.invalidate()
            _translatedEmbedded.value += target to null
        } else translateCue(target, runtime, cue)
    }

    private fun translateCue(target: Layer, runtime: EmbeddedRuntime, text: String) {
        // Only the latest cue matters: stop the call for the previous one.
        embeddedJobs.remove(target)?.cancel()
        _translatedEmbedded.value += target to null
        embeddedJobs[target] = runtime.scope.launch {
            val out = withContext(translationDispatcher) { runtime.translation.translate(text) }
            ensureActive()
            if (cleared || generations[target] != runtime.generation) return@launch
            if (out != null) _translatedEmbedded.value += target to out
            val note = if (runtime.translation.failed) UNAVAILABLE_NOTE else null
            if (_layers.value.getValue(target).note != note) updateLayer(target) { it.copy(note = note) }
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
        lastPositionMs = positionMs
        val state = _layers.value.getValue(layer)
        return when (val source = state.source) {
            is LayerSource.Remote -> state.track?.textAt(positionMs - state.offsetMs)
            is LayerSource.Translated -> {
                // Translated downloaded cues follow the source layer's offset.
                val origin = _layers.value.getValue(source.from)
                if (origin.source !is LayerSource.Remote) return null
                val pos = positionMs - origin.offsetMs
                remoteTranslations[layer]?.let {
                    val delta = pos - it.positionMs
                    if (delta > TRANSLATION_SEEK_JUMP_MS || delta < -TRANSLATION_SEEK_BACK_MS) it.translation.seek(pos)
                    it.positionMs = pos
                }
                state.track?.textAt(pos)
            }
            else -> null
        }
    }

    /**
     * Text the overlay shows for [layer]: [embeddedText] for an embedded layer,
     * [translatedText] for a translation of an embedded layer, otherwise the downloaded/translated cue.
     * A translated layer shows its note (model download, errors), and «Перевод…» only while
     * the source has a cue at the current moment that is not translated yet.
     */
    fun overlayText(
        layer: Layer,
        positionMs: Long,
        embeddedText: String? = null,
        translatedText: String? = _translatedEmbedded.value[layer],
    ): String? {
        val state = _layers.value.getValue(layer)
        val source = state.source
        if (source is LayerSource.Embedded) return embeddedText
        if (source !is LayerSource.Translated) return textAt(layer, positionMs)
        val origin = _layers.value.getValue(source.from)
        val note = state.note?.takeIf { it != TRANSLATING_NOTE }
        return when (origin.source) {
            is LayerSource.Embedded -> translatedText ?: note
            is LayerSource.Remote -> textAt(layer, positionMs) ?: note
                ?: TRANSLATING_NOTE.takeIf { origin.track?.textAt(positionMs - origin.offsetMs) != null }
            else -> note
        }
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

    /** Movie and release data from the route; movieId 0 when opened without a movie. */
    val meta: PlaybackMeta = PlaybackMeta.from { savedState.get<String>(it) }
    private val _state = MutableStateFlow(
        PlayerUiState(title = savedState.get<String>("title").orEmpty(), url = url),
    )
    val state: StateFlow<PlayerUiState> = _state

    private val isTorrent = key.startsWith("bt:")
    private val sessionId = manager.currentSessionId()

    val stallWarning = manager.stallWarning.map { warning ->
        warning?.takeIf { isTorrent && it.sessionId == sessionId }
    }

    val terminalStreamError = manager.state.map { stream ->
        (stream as? StreamState.Failed)?.message?.takeIf {
            isTorrent && !cleared && manager.currentSessionId() == sessionId
        }
    }

    fun onPlaybackWaiting(waiting: Boolean) {
        if (isTorrent && !cleared) manager.reportPlaybackWaiting(sessionId, waiting)
    }

    fun onPlaybackSeek() {
        if (isTorrent && !cleared) manager.resetPlaybackDemand(sessionId)
    }

    fun chooseAnotherTorrent() {
        if (isTorrent) manager.stopIf(sessionId)
    }

    /** Download status line; only for torrent sessions. */
    val statusLine: StateFlow<String?> = if (isTorrent) manager.statusLine else MutableStateFlow(null)

    fun clearError() {
        _state.value = _state.value.copy(error = null, failure = null)
    }

    override fun onCleared() {
        if (isTorrent) manager.reportPlaybackWaiting(sessionId, false)
        cleared = true
        layerJobs.values.forEach { it.cancel() }
        if (isTorrent) manager.stopIf(sessionId)
        super.onCleared()
    }

    private var resumeComputed = false
    private var lastSaveAt: Long? = null
    private var actuallyPlayed = false
    private var cloudPosition: Long? = null

    suspend fun prepareCloudResume() {
        try { cloudPosition = listSync?.savedPosition(listEntry(0, 0)) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { cloudPosition = null }
    }

    private fun listEntry(positionMs: Long, durationMs: Long) = WatchEntry(
        movieId = meta.movieId, title = meta.movieTitle.ifBlank { _state.value.title },
        year = meta.movieYear, posterUrl = meta.poster, imdb = meta.imdb,
        positionKey = key, positionMs = positionMs, durationMs = durationMs,
        updatedAt = wallClock(), kind = meta.kind, source = meta.source,
        season = meta.season, episode = meta.episode,
    )

    private var lastListAt: Long? = null
    private var listStage = 0

    /** Routine ticks are thinned out; pause, exit, first play and crossing a finish threshold are not. */
    private fun recordList(positionMs: Long, durationMs: Long, leaving: Boolean) {
        if (!actuallyPlayed || durationMs <= 0 || meta.movieId <= 0) return
        val entry = listEntry(positionMs, durationMs)
        val stage = if (isFinished(positionMs, durationMs)) 2 else if (entry.progress >= FINISHED_PROGRESS) 1 else 0
        val now = clock()
        val last = lastListAt
        val urgent = leaving || last == null || stage > listStage
        if (!urgent && now - last!! < LIST_INTERVAL_MS) return
        lastListAt = now
        listStage = stage
        listSync?.record(entry,
            isFinished(positionMs, durationMs) && meta.seasonCount != null &&
                meta.season != null && meta.season >= meta.seasonCount && nextStep() is NextStep.SeriesDone,
            syncNow = urgent)
    }

    private var ratingAsked = false

    /** Label of the score to request now, at most once per screen; null when none is due. */
    suspend fun ratingPrompt(positionMs: Long, durationMs: Long): String? {
        if (ratingAsked || !actuallyPlayed || meta.movieId <= 0 || durationMs <= 0) return null
        if (positionMs.toFloat() / durationMs < FINISHED_PROGRESS) return null
        val season = meta.season
        val series = meta.kind == MediaKind.Series
        if (series && (season == null || meta.episode == null || meta.seasonEpisodes == null || meta.episode < meta.seasonEpisodes)) return null
        val needed = try { listSync?.needsRating(listEntry(positionMs, durationMs)) == true }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        if (!needed) return null
        ratingAsked = true
        return if (series) "Оцените сезон $season" else "Оцените фильм"
    }

    fun submitRating(rating: Int) {
        listSync?.rate(listEntry(0, 0), rating)
    }

    /**
     * Computes the resume point once. With autoResume returns the position to seek to silently;
     * otherwise may raise the resume offer and returns null.
     */
    fun onDuration(durationMs: Long): Long? {
        if (resumeComputed || durationMs <= 0) return null
        resumeComputed = true
        val saved = cloudPosition ?: store.position(key)
        if (meta.autoResume) return saved?.takeIf { it > 0 && !isFinished(it, durationMs) }
        _state.value = _state.value.copy(resumeOffer = resumeFrom(saved, durationMs))
        return null
    }

    fun dismissResume() {
        _state.value = _state.value.copy(resumeOffer = null)
    }

    fun onProgress(positionMs: Long, durationMs: Long) {
        actuallyPlayed = true
        lastPositionMs = positionMs
        val now = clock()
        val last = lastSaveAt
        if (last != null && now - last < SAVE_INTERVAL_MS) return
        if (positionMs <= 0) return
        lastSaveAt = now
        store.savePosition(key, positionMs)
        recordList(positionMs, durationMs, leaving = false)
    }

    fun onPauseOrExit(positionMs: Long, durationMs: Long) {
        onPlaybackWaiting(false)
        if (positionMs <= 0) return
        lastSaveAt = clock()
        if (isFinished(positionMs, durationMs)) store.clearPosition(key) else store.savePosition(key, positionMs)
        recordHistory(positionMs, durationMs)
        recordSeries(positionMs, durationMs)
        recordList(positionMs, durationMs, leaving = true)
    }

    private fun seriesRef(): EpisodeRef? {
        if (meta.kind != MediaKind.Series) return null
        return EpisodeRef(meta.season ?: return null, meta.episode ?: return null)
    }

    private fun seriesKeyOrNull(): String? =
        if (meta.movieId > 0) meta.source?.let { seriesKey(it, meta.movieId) } else null

    /** Episode files of this release: the live session first, then the saved season release. */
    private fun releaseFiles(ref: EpisodeRef): List<EpisodeFile> {
        episodeFiles().takeIf { it.isNotEmpty() }?.let { return it }
        val key = seriesKeyOrNull() ?: return emptyList()
        return seriesStore?.release(key, ref.season)?.files.orEmpty()
    }

    /** What follows the current series episode; null for movies. */
    fun nextStep(): NextStep? {
        val ref = seriesRef() ?: return null
        return afterEpisode(ref, releaseFiles(ref), meta.seasonEpisodes, meta.seasonCount)
    }

    private fun seasonSize(season: Int): Int? =
        if (season == meta.season) meta.seasonEpisodes ?: meta.sizeOf(season) else meta.sizeOf(season)

    /** Route meta for [ref] in this release, resuming silently. */
    fun metaFor(ref: EpisodeRef): PlaybackMeta = meta.copy(
        autoResume = true,
        season = ref.season,
        episode = ref.episode,
        seasonEpisodes = seasonSize(ref.season),
        episodeTitle = "",
    )

    /** Saves episode progress; a watched episode moves history to the next one or ends the series. */
    private fun recordSeries(positionMs: Long, durationMs: Long) {
        val ref = seriesRef() ?: return
        if (durationMs <= 0) return
        val fraction = (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
        val seriesKey = seriesKeyOrNull() ?: return
        seriesStore?.saveProgress(seriesKey, ref, fraction)
        if (fraction < FINISHED_PROGRESS) return
        val files = releaseFiles(ref)
        if (files.isNotEmpty() && files.none { it.ref > ref }) finishRelease(sessionId)
        val history = history ?: return
        when (val next = afterEpisode(ref, files, meta.seasonEpisodes, meta.seasonCount)) {
            is NextStep.SeriesDone -> history.remove("${meta.source ?: "-"}:${MediaKind.Series}:${meta.movieId}")
            is NextStep.PlayFile -> advanceHistory(history, next.ref, durationMs)
            is NextStep.PickRelease -> advanceHistory(history, next.ref, durationMs)
        }
    }

    private fun advanceHistory(history: WatchHistoryStore, next: EpisodeRef, durationMs: Long) {
        if (meta.magnet.isBlank() && meta.torrent.isBlank()) return
        history.upsert(WatchEntry(
            movieId = meta.movieId,
            title = meta.movieTitle.ifBlank { _state.value.title },
            year = meta.movieYear,
            posterUrl = meta.poster,
            rating = meta.rating,
            magnet = meta.magnet,
            torrentUrl = meta.torrent,
            imdb = meta.imdb,
            positionKey = key,
            positionMs = 0,
            durationMs = durationMs,
            updatedAt = wallClock(),
            kind = MediaKind.Series,
            source = meta.source,
            season = next.season,
            episode = next.episode,
            seasonEpisodes = seasonSize(next.season),
            seasonCount = meta.seasonCount,
            seasonSizes = meta.seasonSizes,
        ))
    }

    /** Keeps «continue watching» in sync; the store drops watched and duration-less entries. */
    private fun recordHistory(positionMs: Long, durationMs: Long) {
        val history = history ?: return
        if (meta.movieId <= 0 || (meta.magnet.isBlank() && meta.torrent.isBlank())) return
        history.upsert(WatchEntry(
            movieId = meta.movieId,
            title = meta.movieTitle.ifBlank { _state.value.title },
            year = meta.movieYear,
            posterUrl = meta.poster,
            rating = meta.rating,
            magnet = meta.magnet,
            torrentUrl = meta.torrent,
            imdb = meta.imdb,
            positionKey = key,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = wallClock(),
            kind = meta.kind,
            source = meta.source,
            season = meta.season,
            episode = meta.episode,
            seasonEpisodes = meta.seasonEpisodes,
            seasonCount = meta.seasonCount,
            seasonSizes = meta.seasonSizes,
        ))
    }

    fun chooseInitialAudio(tracks: List<AudioTrack>): AudioTrack? =
        chooseAudio(tracks, store.audioPreference())

    fun onAudioSelected(t: AudioTrack) {
        val lang = t.language?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) } ?: return
        store.saveAudioPreference(AudioPreference(lang, t.label))
    }

    fun onError(message: String) {
        _state.value = _state.value.copy(error = message, failure = null)
    }

    fun onError(failure: PlayerFailure) {
        _state.value = _state.value.copy(error = failure.message, failure = failure)
    }
}
