package dev.gamspi.videogallery.catalog.poiskkino

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
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
    private class Chain {
        val mutex = Mutex()
        val pages = mutableListOf<MovieCursorDto>()
    }
    private class Credentials(val key: String) {
        val genresMutex = Mutex()
        var genres: List<Genre>? = null
        private val chains = mutableMapOf<Int?, Chain>()
        @Synchronized fun chain(genreId: Int?): Chain = chains.getOrPut(genreId) { Chain() }
    }
    private var credentials: Credentials? = null

    override suspend fun popular(page: Int): List<Movie> = call { state -> loadPage(state, null, page) }

    override suspend fun genres(): List<Genre> = call { state -> genres(state) }

    override suspend fun byGenre(genreId: Int, page: Int): List<Movie> = call { state ->
        if (genres(state).none { it.id == genreId }) emptyList() else loadPage(state, genreId, page)
    }

    override suspend fun search(query: String): List<Movie> = call { state ->
        api.search(state.key, query).docs.orEmpty().mapNotNull { it.toMovieOrNull() }
    }

    override suspend fun details(id: Int): MovieDetails = call { state ->
        api.details(state.key, id).toDetailsOrNull() ?: throw CatalogException.Parse
    }

    private suspend fun genres(state: Credentials): List<Genre> = state.genresMutex.withLock {
        state.genres ?: api.genres(state.key).toGenres().also { state.genres = it }
    }

    private suspend fun loadPage(state: Credentials, genreId: Int?, page: Int): List<Movie> {
        if (page < 1) throw CatalogException.Parse
        val chain = state.chain(genreId)
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
                val response = api.movies(state.key, next = next,
                    list = if (genreId == null) "popular-films" else null, genreId = genreId)
                currentCoroutineContext().ensureActive()
                // In-flight work belongs only to this credential snapshot.
                if (settings.poiskkinoApiKey.value != state.key) throw CancellationException("Catalog credentials changed")
                chain.pages += response
            }
            chain.pages[page - 1].docs.orEmpty().mapNotNull { it.toMovieOrNull() }
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
