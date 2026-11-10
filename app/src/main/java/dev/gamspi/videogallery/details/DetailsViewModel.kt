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
import dev.gamspi.videogallery.catalog.model.isComingSoon
import dev.gamspi.videogallery.catalog.model.isEpisodeAvailable
import java.time.LocalDate
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
        /** Series only: includes announced future seasons. */
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
        val knownEpisodes: Map<Int, List<Episode>> = emptyMap(),
    ) : DetailsUiState
    data class Error(val kind: ErrorKind) : DetailsUiState
}

/** What OK on an episode does. */
sealed interface PlayTarget {
    data object Unavailable : PlayTarget
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

/** A list mark overrides local progress; an unmarked episode keeps only partial progress. */
internal fun episodeFraction(local: Float?, watched: Boolean): Float =
    if (watched) 1f else local?.takeIf { it < FINISHED_PROGRESS } ?: 0f

/** Episodes without a date count as released: not every catalog reports one. */
internal fun releasedEpisodes(episodes: List<Episode>, todayIso: String): List<Episode> =
    episodes.filter { isEpisodeAvailable(it, LocalDate.parse(todayIso)) }

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val history: WatchHistoryStore,
    private val series: SeriesStore,
    savedStateHandle: SavedStateHandle,
    private val listSync: dev.gamspi.videogallery.watchlist.ListSyncService? = null,
) : ViewModel() {
    val listSnapshot = listSync?.store?.snapshots ?: MutableStateFlow(dev.gamspi.videogallery.watchlist.ListSnapshot())
    val listError = listSync?.error ?: MutableStateFlow<String?>(null)
    fun listRecord(): dev.gamspi.videogallery.watchlist.ListRecord? {
        val details = content()?.details ?: return null
        val movie = details.movie
        val seed = dev.gamspi.videogallery.watchlist.titleSeed(movie, details.imdbId.orEmpty(), source.name) ?: return null
        return dev.gamspi.videogallery.watchlist.withPremiere(
            listSnapshot.value.records[seed.identity]?.takeUnless { it.deleted } ?: seed, movie)
    }
    fun rateSeason(season: Int, rating: Int) { listRecord()?.let { listSync?.rateSeason(it, season, rating) } }
    fun markEpisode(ref: EpisodeRef, watched: Boolean) {
        if (watched && content()?.episodeAvailable(ref, LocalDate.now()) != true) return
        listRecord()?.let { listSync?.markEpisodes(it, listOf(ref.season to ref.episode), watched) }
    }
    /** Marks only episodes already out; future ones stay unmarked. */
    fun markSeason() {
        val c = content() ?: return
        val episodes = c.episodes.filter { c.episodeAvailable(EpisodeRef(it.season, it.number), LocalDate.now()) }
        listRecord()?.let { record -> listSync?.markEpisodes(record, episodes.map { it.season to it.number }, true) }
    }
    fun changeList(field: String, value: kotlinx.serialization.json.JsonElement) {
        val record = listRecord() ?: return
        val listed = listSnapshot.value.records[record.identity]?.deleted == false
        listSync?.changeTitle(if (listed) record else dev.gamspi.videogallery.watchlist.firstEditSeed(record, field), field, value)
    }
    private val pinnedSource = dev.gamspi.videogallery.settings.CatalogSource.entries
        .firstOrNull { it.name == savedStateHandle.get<String>("source") }
    private val source get() = pinnedSource ?: settings.catalogSource.value
    private val cards get() = pinnedSource?.let(catalog::forSource) ?: catalog
    private val id: Int = checkNotNull(savedStateHandle["id"])
    private val kind = MediaKind.entries.firstOrNull { it.name == savedStateHandle.get<String>("kind") }
        ?: MediaKind.Movie
    private val _state = MutableStateFlow<DetailsUiState>(DetailsUiState.Loading)
    val state: StateFlow<DetailsUiState> = _state
    private val retryTick = MutableStateFlow(0)
    private var episodesJob: Job? = null
    private var resumeJob: Job? = null
    private var resumeLoading: Int? = null

    init {
        listSync?.loadLocal()
        listSync?.request()
        viewModelScope.launch { listSnapshot.collect { refreshProgress() } }
        viewModelScope.launch {
            combine(settings.activeCatalog, retryTick) { active, tick -> active to tick }
                .collectLatest { load() }
        }
        viewModelScope.launch { history.entries.collect { refreshProgress() } }
    }

    fun retry() {
        retryTick.value++
    }

    private fun key() = seriesKey(source.name, id)

    private fun content() = _state.value as? DetailsUiState.Content

    private fun historyEntry(): WatchEntry? {
        val name = source.name
        return history.entries.value.firstOrNull {
            it.kind == MediaKind.Series && it.movieId == id && (it.source == null || it.source == name) &&
                it.season != null && it.episode != null
        }
    }

    /** Re-reads progress and the resume episode: history changes while the card is open. */
    private fun refreshProgress() {
        val c = content()?.takeIf { it.seasons.isNotEmpty() } ?: return
        val progress = series.progress(key()).toMutableMap()
        val titleKey = listRecord()?.titleKey
        val marks = listSnapshot.value.records.values.filter { it.table == dev.gamspi.videogallery.watchlist.ListTable.Episodes && it.titleKey == titleKey }
            .associate { EpisodeRef(it.season, it.episode) to (!it.deleted && it.watched) }
        marks.forEach { (ref, watched) -> progress[ref] = episodeFraction(progress[ref], watched) }
        val (resume, started) = resumeOf(c.seasons, progress)
        _state.value = c.copy(progress = progress, resume = availableResume(resume, c, progress, LocalDate.now()), started = started)
        if (resume.season != c.selectedSeason && resume.season !in c.knownEpisodes && resumeLoading != resume.season &&
            !c.seasonComingSoon(resume.season, LocalDate.now())) {
            loadResumeSeason(resume.season)
        }
    }

    private fun loadResumeSeason(number: Int) {
        resumeJob?.cancel()
        resumeLoading = number
        resumeJob = viewModelScope.launch {
            try {
                val episodes = cards.season(id, number)
                content()?.let { _state.value = it.copy(knownEpisodes = it.knownEpisodes + (number to episodes)) }
                refreshProgress()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: CatalogException) { /* Retry on the next progress refresh or season selection. */ }
            finally { if (resumeLoading == number) resumeLoading = null }
        }
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
        content()?.knownEpisodes?.get(season)?.let { cached ->
            content()?.let { _state.value = it.copy(selectedSeason = season, episodes = cached, episodesLoading = false, episodesError = null) }
            refreshProgress()
            return
        }
        content()?.let {
            _state.value = it.copy(selectedSeason = season, episodes = emptyList(), episodesLoading = true, episodesError = null)
        }
        episodesJob = viewModelScope.launch {
            val result = try {
                cards.season(id, season)
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
                _state.value = c.copy(episodes = result, episodesLoading = false, episodesError = null,
                    knownEpisodes = c.knownEpisodes + (season to result))
                refreshProgress()
            }
        }
    }

    /** Episode to preselect in the release list for [season]. */
    fun releaseEpisode(season: Int): Int? {
        val c = content() ?: return null
        if (c.seasons.isEmpty()) return null
        val r = c.resume
        return if (r != null && r.season == season && c.episodeAvailable(r, LocalDate.now())) r.episode
        else c.knownEpisodes[season]?.firstOrNull { c.episodeAvailable(EpisodeRef(season, it.number), LocalDate.now()) }?.number
    }

    fun canWatch(): Boolean = content()?.details?.movie?.isComingSoon(LocalDate.now()) == false

    fun canPickSeason(season: Int): Boolean {
        val c = content() ?: return false
        if (!canWatch() || c.seasonComingSoon(season, LocalDate.now())) return false
        val episodes = c.knownEpisodes[season] ?: return !c.episodesLoading
        return episodes.isEmpty() || episodes.any { c.episodeAvailable(EpisodeRef(season, it.number), LocalDate.now()) }
    }

    /** Saved release of the season when it holds the episode; otherwise the release list. */
    fun playTarget(ref: EpisodeRef): PlayTarget {
        val c = content() ?: return PlayTarget.Unavailable
        if (!c.episodeAvailable(ref, LocalDate.now())) return PlayTarget.Unavailable
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
            source = source.name,
            season = ref.season,
            episode = ref.episode,
            seasonEpisodes = c.seasons.firstOrNull { it.number == ref.season }?.episodeCount,
            seasonCount = c.seasons.size,
            seasonSizes = encodeSeasonSizes(c.seasons),
            episodeTitle = c.knownEpisodes[ref.season]?.firstOrNull { it.number == ref.episode }?.title.orEmpty(),
        )
        return PlayTarget.Stream(meta, release.title)
    }

    private suspend fun load() {
        episodesJob?.cancel()
        resumeJob?.cancel()
        resumeLoading = null
        _state.value = DetailsUiState.Loading
        val details = try {
            cards.details(id, kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CatalogException) {
            _state.value = DetailsUiState.Error(e.toErrorKind())
            return
        }
        val isSeries = kind == MediaKind.Series
        var seasons = if (isSeries) details.seasons.filter { it.number > 0 && (it.episodeCount > 0 ||
            details.movie.isComingSoon(LocalDate.now()) ||
            dev.gamspi.videogallery.catalog.model.releaseDate(it.airDate)?.isAfter(LocalDate.now()) == true) } else emptyList()
        var preloaded: List<Episode>? = null
        if (seasons.isEmpty()) {
            if (!isSeries) {
                _state.value = DetailsUiState.Content(details)
                return
            }
            // No season list: probe season 1 so a series is never shown as a movie.
            val probe = try {
                cards.season(id, 1)
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
        val first = resume.season.takeIf { s -> seasons.any { it.number == s } } ?: seasons.first().number
        _state.value = DetailsUiState.Content(
            details, seasons, first, progress = progress, started = started, series = true,
            episodes = preloaded.orEmpty(),
            knownEpisodes = preloaded?.let { mapOf(first to it) }.orEmpty(),
        )
        refreshProgress()
        if (preloaded == null) loadEpisodes(first)
    }
}
