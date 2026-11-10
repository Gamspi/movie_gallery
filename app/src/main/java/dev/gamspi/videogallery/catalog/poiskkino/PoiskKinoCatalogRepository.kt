package dev.gamspi.videogallery.catalog.poiskkino

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.catalog.model.upcomingDateWindow
import dev.gamspi.videogallery.catalog.model.upcomingTitles
import dev.gamspi.videogallery.catalog.withCatalogCredentials
import dev.gamspi.videogallery.settings.SettingsRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class PoiskKinoCatalogRepository(private val api: PoiskKinoApi, private val settings: SettingsRepository) : CatalogRepository {
    override suspend fun recommendations(id: Int, kind: MediaKind): List<Movie> = call { state ->
        api.details(state.key, id).similarMovies.orEmpty().mapNotNull { it.toMovieOrNull() }.take(20)
    }

    override suspend fun findByImdb(imdbId: String, kind: MediaKind): Movie? = call { state ->
        val docs = api.findByImdb(state.key, imdbId).docs.orEmpty()
        // The filter is server side; a single doc without externalId is trusted, a contradicting id never.
        val found = docs.filter { it.externalId?.imdb == imdbId || (it.externalId?.imdb == null && docs.size == 1) }
            .mapNotNull { it.toMovieOrNull() }
        found.firstOrNull { it.kind == kind } ?: found.firstOrNull()
    }
    private class Chain {
        val mutex = Mutex()
        val pages = mutableListOf<MovieCursorDto>()
    }
    private class Credentials(val key: String) {
        val genresMutex = Mutex()
        var genres: List<Genre>? = null
        private val chains = mutableMapOf<Pair<Int?, MediaKind>, Chain>()
        @Synchronized fun chain(genreId: Int?, kind: MediaKind): Chain = chains.getOrPut(genreId to kind) { Chain() }
    }
    private var credentials: Credentials? = null

    override suspend fun upcoming(today: java.time.LocalDate, kind: MediaKind?): List<Movie> = call { state ->
        val window = upcomingDateWindow(today)
        val format = java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu", java.util.Locale.ROOT)
        val from = window.start.format(format)
        val to = window.endInclusive.format(format)
        val result = api.movies(state.key, type = if (kind == MediaKind.Movie) "movie" else null,
            isSeries = if (kind == MediaKind.Series) true else null,
            sortField = "votes.await", sortType = "-1", premiereWorld = "$from-$to")
        upcomingTitles(
            result.docs.orEmpty().mapNotNull { it.toMovieOrNull() }.filter { kind == null || it.kind == kind }, today)
    }

    override suspend fun popular(page: Int, kind: MediaKind): List<Movie> =
        call { state -> loadPage(state, null, page, kind) }

    override suspend fun genres(kind: MediaKind): List<Genre> = call { state -> genres(state) }

    override suspend fun byGenre(genreId: Int, page: Int, kind: MediaKind): List<Movie> = call { state ->
        if (genres(state).none { it.id == genreId }) emptyList() else loadPage(state, genreId, page, kind)
    }

    override suspend fun search(query: String): List<Movie> = call { state ->
        api.search(state.key, query).docs.orEmpty().mapNotNull { it.toMovieOrNull() }
    }

    override suspend fun details(id: Int, kind: MediaKind): MovieDetails = call { state ->
        api.details(state.key, id).toDetailsOrNull() ?: throw CatalogException.Parse
    }

    override suspend fun season(id: Int, number: Int): List<Episode> = call { state ->
        api.season(state.key, id, number).toEpisodes(number)
    }

    private suspend fun genres(state: Credentials): List<Genre> = state.genresMutex.withLock {
        state.genres ?: api.genres(state.key).toGenres().also { state.genres = it }
    }

    private suspend fun loadPage(state: Credentials, genreId: Int?, page: Int, kind: MediaKind): List<Movie> {
        if (page < 1) throw CatalogException.Parse
        val chain = state.chain(genreId, kind)
        return chain.mutex.withLock {
            if (page == 1) chain.pages.clear()
            while (chain.pages.size < page) {
                currentCoroutineContext().ensureActive()
                val previous = chain.pages.lastOrNull()
                if (previous != null && !previous.hasNext) return@withLock emptyList()
                val next = previous?.next
                if (previous != null && (next.isNullOrBlank() || chain.pages.dropLast(1).any { it.next == next })) {
                    throw CatalogException.Parse
                }
                val series = kind == MediaKind.Series
                val list = if (genreId == null && !series) "popular-films" else null
                val response = api.movies(state.key, next = next, list = list, genreId = genreId,
                    type = if (series) null else "movie", isSeries = if (series) true else null)
                currentCoroutineContext().ensureActive()
                // In-flight work belongs only to this credential snapshot.
                if (settings.poiskkinoApiKey.value != state.key) throw CancellationException("Catalog credentials changed")
                chain.pages += response
            }
            chain.pages[page - 1].docs.orEmpty().mapNotNull { it.toMovieOrNull() }
                .filter { it.kind == kind }
        }
    }

    private suspend fun <T> call(block: suspend (Credentials) -> T): T {
        val key = settings.poiskkinoApiKey.value?.takeIf { it.isNotBlank() } ?: throw CatalogException.MissingApiKey
        if (!key.all { it in '!'..'~' }) throw CatalogException.Unauthorized
        val state = synchronized(this) {
            credentials?.takeIf { it.key == key } ?: Credentials(key).also { credentials = it }
        }
        try {
            val result = block(state)
            currentCoroutineContext().ensureActive()
            if (settings.poiskkinoApiKey.value != key) throw CancellationException("Catalog credentials changed")
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            throw when (e.code()) {
                401 -> CatalogException.Unauthorized
                403 -> CatalogException.QuotaExceeded
                else -> CatalogException.Http(e.code())
            }
        } catch (_: SerializationException) {
            throw CatalogException.Parse
        } catch (_: IllegalArgumentException) {
            throw CatalogException.Parse
        } catch (_: IOException) {
            throw CatalogException.Network
        }
    }

    companion object {
        const val BASE_URL = "https://api.poiskkino.dev/"
        private val json = Json { ignoreUnknownKeys = true }
        fun create(baseUrl: String, settings: SettingsRepository, client: OkHttpClient = OkHttpClient()): PoiskKinoCatalogRepository {
            val http = client.newBuilder().connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
                .build().withCatalogCredentials("poiskkino")
            val api = Retrofit.Builder().baseUrl(baseUrl).client(http)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(PoiskKinoApi::class.java)
            return PoiskKinoCatalogRepository(api, settings)
        }
    }
}
