package dev.gamspi.videogallery.catalog.kinopoisk

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.withCatalogCredentials
import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.settings.SettingsRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class KinopoiskCatalogRepository(
    private val api: KinopoiskApi,
    private val settings: SettingsRepository,
) : CatalogRepository {

    override suspend fun popular(page: Int, kind: MediaKind): List<Movie> = call {
        val list = if (kind == MediaKind.Series) api.popular(page, "POPULAR_SERIES") else api.popular(page)
        list.movies(kind)
    }

    override suspend fun genres(kind: MediaKind): List<Genre> = call {
        api.filters().genres.orEmpty().mapNotNull { it.toGenreOrNull() }
    }

    override suspend fun byGenre(genreId: Int, page: Int, kind: MediaKind): List<Movie> = call {
        val list = if (kind == MediaKind.Series) api.byGenre(genreId, page, type = "TV_SERIES")
        else api.byGenre(genreId, page)
        list.movies(kind)
    }

    override suspend fun search(query: String): List<Movie> =
        call { api.search(query).items.orEmpty().mapNotNull { dto -> dto.kind()?.let { dto.toMovie(it) } } }

    override suspend fun details(id: Int, kind: MediaKind): MovieDetails = call {
        val dto = api.details(id)
        if (kind == MediaKind.Series) dto.toDetails(kind, api.seasons(id).toSeasonInfos()) else dto.toDetails()
    }

    // the seasons response is cached by the shared HTTP cache
    override suspend fun season(id: Int, number: Int): List<Episode> =
        call { api.seasons(id).toEpisodes(number) }

    private fun FilmListDto.movies(kind: MediaKind): List<Movie> =
        items.orEmpty().filter { it.kind() == kind }.map { it.toMovie(kind) }

    private suspend fun <T> call(block: suspend () -> T): T {
        val key = settings.kinopoiskApiKey.value
        if (key.isNullOrBlank()) throw CatalogException.MissingApiKey
        if (!key.isHeaderSafe()) throw CatalogException.Unauthorized
        try {
            return block()
        } catch (e: HttpException) {
            throw when (e.code()) {
                401 -> CatalogException.Unauthorized
                402 -> CatalogException.QuotaExceeded
                else -> CatalogException.Http(e.code())
            }
        } catch (e: MissingKeyException) {
            throw CatalogException.MissingApiKey
        } catch (e: BadKeyException) {
            throw CatalogException.Unauthorized
        } catch (e: SerializationException) {
            throw CatalogException.Parse
        } catch (e: IllegalArgumentException) {
            throw CatalogException.Parse
        } catch (e: IOException) {
            throw CatalogException.Network
        }
    }

    companion object {
        const val BASE_URL = "https://kinopoiskapiunofficial.tech/"

        fun create(baseUrl: String, settings: SettingsRepository, client: OkHttpClient = OkHttpClient()): KinopoiskCatalogRepository {
            val configured = client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    // key is read per request so a changed key applies without restart
                    val key = settings.kinopoiskApiKey.value ?: throw MissingKeyException()
                    if (!key.isHeaderSafe()) throw BadKeyException()
                    chain.proceed(chain.request().newBuilder().header("X-API-KEY", key).build())
                }
                .build().withCatalogCredentials("kinopoisk")
            val json = Json { ignoreUnknownKeys = true }
            val api = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(configured)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(KinopoiskApi::class.java)
            return KinopoiskCatalogRepository(api, settings)
        }
    }
}

private class MissingKeyException : IOException("Kinopoisk key missing")

private class BadKeyException : IOException("Kinopoisk key has invalid characters")

private fun String.isHeaderSafe(): Boolean = all { it in '!'..'~' }
