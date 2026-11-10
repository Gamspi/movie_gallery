package dev.gamspi.videogallery.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.upcomingTitles
import java.time.LocalDate
import dev.gamspi.videogallery.history.WatchEntry
import dev.gamspi.videogallery.history.WatchHistoryStore
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.ui.components.ErrorKind
import dev.gamspi.videogallery.ui.components.toErrorKind
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.gamspi.videogallery.watchlist.ListRecord
import kotlinx.coroutines.launch

data class MovieRow(val title: String, val movies: List<Movie>, val key: String? = null)

enum class HomeTab { All, Movies, Series, Watchlist, Watched, ForYou }

/** Alternates items of [a] and [b] starting with [a]; the longer tail is appended. */
internal fun <T> interleave(a: List<T>, b: List<T>): List<T> = buildList(a.size + b.size) {
    for (i in 0 until maxOf(a.size, b.size)) {
        if (i < a.size) add(a[i])
        if (i < b.size) add(b[i])
    }
}

sealed interface GalleryUiState {
    data class Loading(val initial: Boolean) : GalleryUiState
    data class Content(val rows: List<MovieRow>, val tab: HomeTab? = null) : GalleryUiState
    data class Error(val kind: ErrorKind) : GalleryUiState
}

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    history: WatchHistoryStore,
    private val listSync: dev.gamspi.videogallery.watchlist.ListSyncService? = null,
    private val imdbMatches: ImdbMatchStore? = null,
) : ViewModel() {
    val listSnapshot = listSync?.store?.snapshots ?: MutableStateFlow(dev.gamspi.videogallery.watchlist.ListSnapshot())
    val listError = listSync?.error ?: MutableStateFlow<String?>(null)
    fun changeList(row: dev.gamspi.videogallery.watchlist.ListRecord, field: String, value: kotlinx.serialization.json.JsonElement) = listSync?.changeTitle(row, field, value)
    /** Instant record for the card menu: the saved row, else a seed without the IMDb id. */
    fun menuRecord(movie: Movie): dev.gamspi.videogallery.watchlist.ListRecord? {
        val source = settings.catalogSource.value.name
        return dev.gamspi.videogallery.watchlist.findByCatalog(listSnapshot.value, source, movie.id, movie.kind)
            ?: dev.gamspi.videogallery.watchlist.titleSeed(movie, "", source)
    }
    fun changeFromCard(movie: Movie, field: String, value: kotlinx.serialization.json.JsonElement) = withCardRecord(movie) { record, listed ->
        listSync?.changeTitle(if (listed) record else dev.gamspi.videogallery.watchlist.firstEditSeed(record, field), field, value)
    }
    fun rateFromCard(movie: Movie, season: Int, rating: Int) = withCardRecord(movie) { record, _ -> listSync?.rateSeason(record, season, rating) }

    // The card has no IMDb id: fetch it so the row gets the same key as the details screen.
    private fun withCardRecord(movie: Movie, act: (dev.gamspi.videogallery.watchlist.ListRecord, Boolean) -> Unit) {
        val source = settings.catalogSource.value.name
        dev.gamspi.videogallery.watchlist.findByCatalog(listSnapshot.value, source, movie.id, movie.kind)?.let { return act(it, true) }
        viewModelScope.launch {
            try {
                val details = catalog.details(movie.id, movie.kind)
                val seed = dev.gamspi.videogallery.watchlist.titleSeed(details.movie, details.imdbId.orEmpty(), source) ?: return@launch
                val live = listSnapshot.value.records[seed.identity]?.takeUnless { it.deleted }
                act(live ?: seed, live != null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: CatalogException) { listSync?.report("Не удалось получить данные тайтла") }
        }
    }
    fun rateSaved(row: dev.gamspi.videogallery.watchlist.ListRecord, season: Int, rating: Int) { listSync?.rateSeason(row, season, rating) }

    /** The title's source goes to its card only; the selected catalog stays as it is. */
    fun openSaved(row: dev.gamspi.videogallery.watchlist.ListRecord, open: (dev.gamspi.videogallery.watchlist.SavedTarget) -> Unit) {
        val target = dev.gamspi.videogallery.watchlist.savedTarget(row)
        if (target == null) listSync?.report("Карточка этого тайтла недоступна") else open(target)
    }
    /** «Продолжить просмотр» entries; separate from [state] so history updates keep catalog rows. */
    val history: StateFlow<List<WatchEntry>> = history.entries
    private val _state = MutableStateFlow<GalleryUiState>(GalleryUiState.Loading(initial = true))
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()
    private val retryTick = MutableStateFlow(0)
    private val _tab = MutableStateFlow(HomeTab.All)
    val tab: StateFlow<HomeTab> = _tab.asStateFlow()
    private var firstLoad = true

    // Rows per tab; dropped on catalog change or retry.
    private val cache = HashMap<HomeTab, List<MovieRow>>()
    private var cacheEpoch: Pair<*, Int>? = null
    private var cacheDate = LocalDate.now()
    private var recommendations: RecommendationLoader? = null
    // Last «Похожие на» rows; shown with the catalog rows until fresh ones replace them.
    private var related: List<MovieRow> = emptyList()

    init {
        viewModelScope.launch {
            // Only live titles matter: positions and episode marks do not reload the rows.
            val titles = listSnapshot.map { recommendationTitles(it.records.values) }.distinctUntilChanged()
            combine(settings.activeCatalog, retryTick, _tab, titles) { active, tick, tab, records ->
                Pair(Triple(active, tick, tab), records)
            }.collectLatest { (selection, records) ->
                    val (active, tick, tab) = selection
                    if (cacheEpoch != active to tick) {
                        cache.clear()
                        cacheEpoch = active to tick
                        related = emptyList()
                        recommendations = RecommendationLoader(catalog.forSource(active.source), active.source, imdbMatches)
                    }
                    load(tab, records)
                }
        }
    }

    fun retry() {
        retryTick.value++
    }

    fun selectTab(t: HomeTab) {
        _tab.value = t
        if (t == HomeTab.Watchlist || t == HomeTab.Watched || t == HomeTab.ForYou) { listSync?.loadLocal(); listSync?.request() }
    }

    private suspend fun load(tab: HomeTab, records: List<ListRecord>) {
        val today = LocalDate.now()
        if (cacheDate != today) {
            cache.clear()
            cacheDate = today
        }
        // Cached catalog rows and list edits on an open «Для вас» reload in place, without a skeleton.
        val shown = (_state.value as? GalleryUiState.Content)?.tab == tab
        if (if (tab == HomeTab.ForYou) !shown else cache[tab] == null) {
            _state.value = GalleryUiState.Loading(initial = firstLoad)
        }
        firstLoad = false
        val loader = recommendations ?: return
        try {
            val rows = cache[tab] ?: when (tab) {
                HomeTab.Movies -> fetchRows(MediaKind.Movie, "Популярное")
                HomeTab.Series -> fetchRows(MediaKind.Series, "Популярные сериалы")
                HomeTab.All -> fetchAllRows()
                HomeTab.Watchlist, HomeTab.Watched, HomeTab.ForYou -> emptyList()
            }
            cache[tab] = rows
            when (tab) {
                HomeTab.All -> {
                    _state.value = GalleryUiState.Content(withRelatedRows(rows, related), tab)
                    related = relatedRows(loader, records)
                    _state.value = GalleryUiState.Content(withRelatedRows(rows, related), tab)
                }
                HomeTab.ForYou -> _state.value = GalleryUiState.Content(forYouRows(loader.load(records, forYouSeeds(records))), tab)
                else -> _state.value = GalleryUiState.Content(rows, tab)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CatalogException) {
            _state.value = GalleryUiState.Error(e.toErrorKind())
        }
    }

    // A failed similar-titles lookup only hides these rows.
    private suspend fun relatedRows(loader: RecommendationLoader, records: List<ListRecord>): List<MovieRow> = try {
        loader.load(records, homeRecommendationSeeds(records)).mapNotNull { batch ->
            batch.movies.takeIf { it.isNotEmpty() }?.let {
                MovieRow("Похожие на „${recommendationSeedName(batch.seed)}“", it, "similar:${batch.seed.titleKey}")
            }
        }
    } catch (_: CatalogException) {
        emptyList()
    }

    private suspend fun fetchRows(kind: MediaKind, popularTitle: String): List<MovieRow> = coroutineScope {
        val popular = async { catalog.popular(kind = kind) }
        val upcoming = async { upcomingRow(kind) }
        val genres = catalog.genres(kind).take(GENRE_ROWS)
        val genreRows = genres.map { genre ->
            async {
                try {
                    MovieRow(genre.name, catalog.byGenre(genre.id, kind = kind))
                } catch (e: CatalogException) {
                    null // failed genre row is skipped; CancellationException is not caught here
                }
            }
        }
        listOfNotNull(MovieRow(popularTitle, popular.await()), upcoming.await()) + genreRows.mapNotNull { it.await() }.filter { it.movies.isNotEmpty() }
    }

    // Movie rows mixed with the same-named series genre; series failures only thin the rows out.
    private suspend fun fetchAllRows(): List<MovieRow> = coroutineScope {
        val popular = async { catalog.popular(kind = MediaKind.Movie) }
        val upcoming = async { upcomingRow(null) }
        val seriesPopular = async { optional { catalog.popular(kind = MediaKind.Series) } }
        val seriesGenres = async { optional { catalog.genres(MediaKind.Series) } }
        val genres = catalog.genres(MediaKind.Movie).take(GENRE_ROWS)
        val seriesByName: Map<String, Genre> = seriesGenres.await().associateBy { it.name }
        val genreRows = genres.map { genre ->
            async {
                try {
                    val movies = catalog.byGenre(genre.id, kind = MediaKind.Movie)
                    val series = seriesByName[genre.name]
                        ?.let { optional { catalog.byGenre(it.id, kind = MediaKind.Series) } }
                        .orEmpty()
                    MovieRow(genre.name, interleave(movies, series))
                } catch (e: CatalogException) {
                    null
                }
            }
        }
        val first = MovieRow("Популярное", interleave(popular.await(), seriesPopular.await()))
        listOfNotNull(first, upcoming.await()) + genreRows.mapNotNull { it.await() }.filter { it.movies.isNotEmpty() }
    }

    private suspend fun upcomingRow(kind: MediaKind?): MovieRow? {
        val today = LocalDate.now()
        val movies = upcomingTitles(optional { catalog.upcoming(today, kind) }, today)
            .filter { kind == null || it.kind == kind }
        return movies.takeIf { it.isNotEmpty() }?.let { MovieRow("Скоро", it) }
    }

    private suspend fun <T> optional(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: CatalogException) {
            emptyList()
        }

    private companion object {
        const val GENRE_ROWS = 8
    }
}
