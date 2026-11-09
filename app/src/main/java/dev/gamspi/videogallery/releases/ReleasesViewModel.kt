package dev.gamspi.videogallery.releases

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.history.encodeSeasonSizes
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.Quality
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.TorrentSearch
import dev.gamspi.videogallery.torrents.UnavailableReason
import dev.gamspi.videogallery.torrents.key
import dev.gamspi.videogallery.torrents.seasonRank
import dev.gamspi.videogallery.torrents.needsResolve
import dev.gamspi.videogallery.ui.components.ErrorKind
import dev.gamspi.videogallery.ui.components.toErrorKind
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReleasesUiState {
    data object Loading : ReleasesUiState
    data class Content(
        val movieTitle: String,
        val all: List<TorrentResult>,
        val filter: ReleaseFilter,
        val limitBytes: Long,
        val unavailable: List<ProviderId>,
        val noProviders: Boolean = false,
        val imdbId: String? = null,
        val movie: Movie? = null,
        val reasons: Map<ProviderId, UnavailableReason> = emptyMap(),
        /** Key of the release whose magnet is being fetched. */
        val resolving: String? = null,
        val message: String? = null,
        val source: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val seasonEpisodes: Int? = null,
        val seasonCount: Int? = null,
        val seasonSizes: String = "",
    ) : ReleasesUiState {
        val visible: List<TorrentResult>
            get() = all.applyFilter(filter, limitBytes)

        /** Movie and release data forwarded to the stream and player screens. */
        fun metaFor(r: TorrentResult): PlaybackMeta = PlaybackMeta(
            movieId = movie?.id ?: 0,
            movieTitle = movie?.title ?: movieTitle,
            movieYear = movie?.year,
            poster = movie?.posterUrl,
            rating = movie?.rating,
            magnet = r.magnet,
            torrent = r.torrentUrl.orEmpty(),
            imdb = imdbId.orEmpty(),
            kind = movie?.kind ?: MediaKind.Movie,
            source = source,
            season = season,
            episode = episode,
            seasonEpisodes = seasonEpisodes,
            seasonCount = seasonCount,
            seasonSizes = seasonSizes,
        )
    }

    data class Error(val kind: ErrorKind) : ReleasesUiState
}

/** Navigation request to the stream screen with a playable release. */
data class PlayRequest(val release: TorrentResult, val meta: PlaybackMeta)

/** Releases covering [season] first, then unmarked ones, then other seasons; seeds break ties. */
internal fun List<TorrentResult>.sortedForSeason(season: Int): List<TorrentResult> =
    sortedWith(compareBy<TorrentResult> { seasonRank(it.title, season) }.thenByDescending { it.seeders })

internal fun missingEpisodeNote(season: Int, episode: Int): String =
    "В раздаче нет S%02dE%02d".format(season, episode)

const val RESOLVE_ERROR = "Не удалось открыть раздачу"

@HiltViewModel
class ReleasesViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val search: TorrentSearch,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val id: Int = checkNotNull(savedStateHandle["id"])
    private val kind = MediaKind.entries.firstOrNull { it.name == savedStateHandle.get<String>("kind") }
        ?: MediaKind.Movie
    private val pinnedSource = dev.gamspi.videogallery.settings.CatalogSource.entries
        .firstOrNull { it.name == savedStateHandle.get<String>("source") }
    private val season = savedStateHandle.get<String>("season")?.toIntOrNull()
    private val episode = savedStateHandle.get<String>("episode")?.toIntOrNull()
    /** «В раздаче нет SxxEyy» when the screen was reached because the episode was missing from a release. */
    private val missing = MutableStateFlow(savedStateHandle.get<String>("missing") == "1")
    val missingNote: StateFlow<String?> = missing
        .map { if (it && season != null && episode != null) missingEpisodeNote(season, episode) else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Turns the missing-episode note on when the screen is revealed by popping back from the stream. */
    fun markEpisodeMissing() {
        missing.value = true
    }
    private val _state = MutableStateFlow<ReleasesUiState>(ReleasesUiState.Loading)
    val state: StateFlow<ReleasesUiState> = _state
    private var job: Job? = null
    private var resolveJob: Job? = null
    private var saveJob: Job? = null
    /** Dispatcher for settings writes; tests replace it. */
    internal var io: CoroutineDispatcher = Dispatchers.IO
    private val _play = MutableStateFlow<PlayRequest?>(null)
    /** Set when a resolved release is ready; the screen navigates and calls [onPlayHandled]. */
    val play: StateFlow<PlayRequest?> = _play

    init {
        load()
    }

    fun setQuality(q: Quality?) = updateFilter { it.copy(quality = q) }

    fun toggleProvider(id: ProviderId) = updateFilter {
        it.copy(hiddenProviders = if (id in it.hiddenProviders) it.hiddenProviders - id else it.hiddenProviders + id)
    }

    fun toggleDead() = updateFilter { it.copy(showDead = !it.showDead) }

    fun toggleOverLimit() = updateFilter { it.copy(showOverLimit = !it.showOverLimit) }

    private fun updateFilter(f: (ReleaseFilter) -> ReleaseFilter) {
        val s = _state.value as? ReleasesUiState.Content ?: return
        val next = f(s.filter)
        _state.value = s.copy(filter = next)
        if (settings.rememberReleaseFilter.value) {
            // Writes run in order off the main thread.
            val previous = saveJob
            saveJob = viewModelScope.launch {
                previous?.join()
                withContext(io) { settings.saveReleaseFilter(next) }
            }
        }
    }

    fun retry() = load()

    /** Fetches the magnet of [r] from its topic page, then publishes a [PlayRequest]. */
    fun resolve(r: TorrentResult) {
        val s = _state.value as? ReleasesUiState.Content ?: return
        if (!r.needsResolve) {
            _play.value = PlayRequest(r, s.metaFor(r))
            return
        }
        val key = r.key
        if (s.resolving == key && resolveJob?.isActive == true) return
        resolveJob?.cancel()
        _state.value = s.copy(resolving = key, message = null)
        resolveJob = viewModelScope.launch {
            try {
                val resolved = search.resolve(r)
                val current = _state.value as? ReleasesUiState.Content ?: return@launch
                _state.value = current.copy(resolving = null)
                _play.value = PlayRequest(resolved, current.metaFor(resolved))
            } catch (e: CancellationException) {
                updateContent { if (it.resolving == key) it.copy(resolving = null) else it }
                throw e
            } catch (e: Exception) {
                updateContent { it.copy(resolving = null, message = RESOLVE_ERROR) }
            }
        }
    }

    fun onPlayHandled() {
        _play.value = null
    }

    private inline fun updateContent(f: (ReleasesUiState.Content) -> ReleasesUiState.Content) {
        val s = _state.value
        if (s is ReleasesUiState.Content) _state.value = f(s)
    }

    private fun load() {
        resolveJob?.cancel()
        job?.cancel()
        _state.value = ReleasesUiState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                val source = (pinnedSource ?: settings.catalogSource.value).name
                val details = (pinnedSource?.let(catalog::forSource) ?: catalog).details(id, kind)
                val noProviders = ProviderId.entries.none { settings.tracker(it).value.enabled }
                val outcome = search.search(details, season)
                ReleasesUiState.Content(
                    movieTitle = details.movie.title,
                    all = if (season == null) outcome.results else outcome.results.sortedForSeason(season),
                    filter = if (settings.rememberReleaseFilter.value) settings.savedReleaseFilter() else ReleaseFilter(),
                    limitBytes = settings.torrentStoragePrefs.value.limitBytes,
                    unavailable = outcome.unavailable,
                    reasons = outcome.reasons,
                    noProviders = noProviders,
                    imdbId = details.imdbId,
                    movie = details.movie,
                    source = source,
                    season = season,
                    episode = episode,
                    seasonEpisodes = details.seasons.firstOrNull { it.number == season }?.episodeCount,
                    seasonCount = details.seasons.count { it.episodeCount > 0 }.takeIf { season != null },
                    seasonSizes = encodeSeasonSizes(details.seasons),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: CatalogException) {
                ReleasesUiState.Error(e.toErrorKind())
            }
        }
    }
}
