package dev.gamspi.videogallery.catalog.kinopoisk

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.model.Genre
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

    override suspend fun popular(page: Int): List<Movie> =
        call { api.popular(page).movies() }

    override suspend fun genres(): List<Genre> =
        call { api.filters().genres.orEmpty().mapNotNull { it.toGenreOrNull() } }

    override suspend fun byGenre(genreId: Int, page: Int): List<Movie> =
        call { api.byGenre(genreId, page).movies() }

    override suspend fun search(query: String): List<Movie> =
        call { api.search(query).movies() }

    override suspend fun details(id: Int): MovieDetails =
        call { api.details(id).toDetails() }

    private fun FilmListDto.movies(): List<Movie> =
        items.orEmpty().filter { it.isFilm() }.map { it.toMovie() }

    private suspend fun <T> call(block: suspend () -> T): T {
        if (settings.kinopoiskApiKey.value.isNullOrBlank()) throw CatalogException.MissingApiKey
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

        fun create(baseUrl: String, settings: SettingsRepository): KinopoiskCatalogRepository {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    // key is read per request so a changed key applies without restart
                    val key = settings.kinopoiskApiKey.value ?: throw MissingKeyException()
                    chain.proceed(chain.request().newBuilder().header("X-API-KEY", key).build())
                }
                .build()
            val json = Json { ignoreUnknownKeys = true }
            val api = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(KinopoiskApi::class.java)
            return KinopoiskCatalogRepository(api, settings)
        }
    }
}

private class MissingKeyException : IOException("Kinopoisk key missing")
