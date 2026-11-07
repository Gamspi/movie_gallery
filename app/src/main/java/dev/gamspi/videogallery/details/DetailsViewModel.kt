package dev.gamspi.videogallery.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.catalog.model.SeasonInfo
import dev.gamspi.videogallery.history.FINISHED_PROGRESS
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.history.encodeSeasonSizes
import dev.gamspi.videogallery.history.SeriesStore
import dev.gamspi.videogallery.history.WatchEntry
import dev.gamspi.videogallery.history.WatchHistoryStore
import dev.gamspi.videogallery.history.seriesKey
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.stream.EpisodeRef
import dev.gamspi.videogallery.ui.components.ErrorKind
import dev.gamspi.videogallery.ui.components.toErrorKind
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface DetailsUiState {
    data object Loading : DetailsUiState
    data class Content(
        val details: MovieDetails,
        /** Series only: released seasons (episodeCount > 0). */
        val seasons: List<SeasonInfo> = emptyList(),
        val selectedSeason: Int = 0,
        val episodes: List<Episode> = emptyList(),
        val episodesLoading: Boolean = false,
        /** Set when the selected season failed to load; the header stays. */
        val episodesError: ErrorKind? = null,
        val progress: Map<EpisodeRef, Float> = emptyMap(),
        /** Episode of the «Продолжить» / «Смотреть» button; null for movies. */
        val resume: EpisodeRef? = null,
        /** True when [resume] comes from history or progress, false for the S01E01 start. */
        val started: Boolean = false,
        /** True for any series, even when no released season is known (then only the season releases are offered). */
        val series: Boolean = false,
    ) : DetailsUiState
    data class Error(val kind: ErrorKind) : DetailsUiState
}

/** What OK on an episode does. */
sealed interface PlayTarget {
    data class Stream(val meta: PlaybackMeta, val releaseTitle: String) : PlayTarget
    data class PickRelease(val season: Int) : PlayTarget
}

fun formatRuntime(min: Int?): String? {
    if (min == null || min <= 0) return null
    val h = min / 60
    val m = min % 60
    return when {
        h == 0 -> "$m мин"
        m == 0 -> "$h ч"
        else -> "$h ч $m мин"
    }
}

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val history: WatchHistoryStore,
    private val series: SeriesStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val id: Int = checkNotNull(savedStateHandle["id"])
    private val kind = MediaKind.entries.firstOrNull { it.name == savedStateHandle.get<String>("kind") }
        ?: MediaKind.Movie
    private val _state = MutableStateFlow<DetailsUiState>(DetailsUiState.Loading)
    val state: StateFlow<DetailsUiState> = _state
    private val retryTick = MutableStateFlow(0)
    private var episodesJob: Job? = null

    init {
        viewModelScope.launch {
            combine(settings.activeCatalog, retryTick) { active, tick -> active to tick }
                .collectLatest { load() }
        }
        viewModelScope.launch { history.entries.collect { refreshProgress() } }
    }

    fun retry() {
        retryTick.value++
    }

    private fun key() = seriesKey(settings.catalogSource.value.name, id)

    private fun content() = _state.value as? DetailsUiState.Content

    private fun historyEntry(): WatchEntry? {
        val source = settings.catalogSource.value.name
        return history.entries.value.firstOrNull {
            it.kind == MediaKind.Series && it.movieId == id && (it.source == null || it.source == source) &&
                it.season != null && it.episode != null
        }
    }

    /** Re-reads progress and the resume episode: history changes while the card is open. */
    private fun refreshProgress() {
        val c = content()?.takeIf { it.seasons.isNotEmpty() } ?: return
        val progress = series.progress(key())
        val (resume, started) = resumeOf(c.seasons, progress)
        _state.value = c.copy(progress = progress, resume = resume, started = started)
    }

    private fun resumeOf(seasons: List<SeasonInfo>, progress: Map<EpisodeRef, Float>): Pair<EpisodeRef, Boolean> {
        historyEntry()?.let { return EpisodeRef(it.season!!, it.episode!!) to true }
        // Finished episodes leave the history: continue with the next one.
        val last = progress.filter { it.value >= FINISHED_PROGRESS }.keys.maxOrNull()
        if (last != null) {
            val info = seasons.firstOrNull { it.number == last.season }
            if (info != null && last.episode < info.episodeCount) return EpisodeRef(last.season, last.episode + 1) to true
            val next = seasons.firstOrNull { it.number > last.season }
            if (next != null) return EpisodeRef(next.number, 1) to true
        }
        return EpisodeRef(seasons.first().number, 1) to false
    }

    fun selectSeason(n: Int) {
        val c = content() ?: return
        if (c.seasons.none { it.number == n } || (n == c.selectedSeason && c.episodesError == null)) return
        loadEpisodes(n)
    }

    fun retryEpisodes() {
        val c = content() ?: return
        if (c.seasons.isNotEmpty()) loadEpisodes(c.selectedSeason)
    }

    private fun loadEpisodes(season: Int) {
        episodesJob?.cancel()
        content()?.let {
            _state.value = it.copy(selectedSeason = season, episodes = emptyList(), episodesLoading = true, episodesError = null)
        }
        episodesJob = viewModelScope.launch {
            val result = try {
                catalog.season(id, season)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CatalogException) {
                val c = content()
                if (c != null && c.selectedSeason == season) {
                    _state.value = c.copy(episodesLoading = false, episodesError = e.toErrorKind())
                }
                return@launch
            }
            val c = content()
            if (c != null && c.selectedSeason == season) {
                _state.value = c.copy(episodes = result, episodesLoading = false, episodesError = null)
            }
        }
    }

    /** Episode to preselect in the release list for [season]. */
    fun releaseEpisode(season: Int): Int? {
        val c = content() ?: return 1
        if (c.seasons.isEmpty()) return null
        val r = c.resume
        return if (r != null && r.season == season) r.episode else 1
    }

    /** Saved release of the season when it holds the episode; otherwise the release list. */
    fun playTarget(ref: EpisodeRef): PlayTarget {
        val c = content() ?: return PlayTarget.PickRelease(ref.season)
        val release = series.release(key(), ref.season)
        if (release == null || release.files.none { it.ref == ref }) return PlayTarget.PickRelease(ref.season)
        val movie = c.details.movie
        val meta = PlaybackMeta(
            movieId = movie.id,
            movieTitle = movie.title,
            movieYear = movie.year,
            poster = movie.posterUrl,
            rating = movie.rating,
            magnet = release.magnet,
            torrent = release.torrentUrl,
            imdb = c.details.imdbId.orEmpty(),
            autoResume = true,
            kind = MediaKind.Series,
            source = settings.catalogSource.value.name,
            season = ref.season,
            episode = ref.episode,
            seasonEpisodes = c.seasons.firstOrNull { it.number == ref.season }?.episodeCount,
            seasonCount = c.seasons.size,
            seasonSizes = encodeSeasonSizes(c.seasons),
            episodeTitle = c.episodes.firstOrNull { it.season == ref.season && it.number == ref.episode }?.title.orEmpty(),
        )
        return PlayTarget.Stream(meta, release.title)
    }

    private suspend fun load() {
        episodesJob?.cancel()
        _state.value = DetailsUiState.Loading
        val details = try {
            catalog.details(id, kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CatalogException) {
            _state.value = DetailsUiState.Error(e.toErrorKind())
            return
        }
        val isSeries = kind == MediaKind.Series
        var seasons = if (isSeries) details.seasons.filter { it.episodeCount > 0 } else emptyList()
        var preloaded: List<Episode>? = null
        if (seasons.isEmpty()) {
            if (!isSeries) {
                _state.value = DetailsUiState.Content(details)
                return
            }
            // No season list: probe season 1 so a series is never shown as a movie.
            val probe = try {
                catalog.season(id, 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CatalogException) {
                emptyList()
            }
            if (probe.isEmpty()) {
                _state.value = DetailsUiState.Content(details, selectedSeason = 1, series = true)
                return
            }
            seasons = listOf(SeasonInfo(1, probe.size))
            preloaded = probe
        }
        // The store reads stay empty until loaded.
        series.awaitLoaded()
        history.awaitLoaded()
        val progress = series.progress(key())
        val (resume, started) = resumeOf(seasons, progress)
        val first = historyEntry()?.season?.takeIf { s -> seasons.any { it.number == s } } ?: seasons.first().number
        _state.value = DetailsUiState.Content(
            details, seasons, first, progress = progress, resume = resume, started = started, series = true,
            episodes = preloaded.orEmpty(),
        )
        if (preloaded == null) loadEpisodes(first)
    }
}
