package dev.gamspi.videogallery.catalog.tmdb

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.settings.SettingsRepository
import java.io.IOException
import kotlinx.serialization.SerializationException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class TmdbCatalogRepository(
    private val api: TmdbApi,
    private val settings: SettingsRepository,
) : CatalogRepository {

    override suspend fun popular(page: Int): List<Movie> =
        call { api.popular(page).results.orEmpty().map { it.toMovie() } }

    override suspend fun genres(): List<Genre> =
        call { api.genres().genres.orEmpty().map { it.toGenre() } }

    override suspend fun byGenre(genreId: Int, page: Int): List<Movie> =
        call { api.discover(genreId, page).results.orEmpty().map { it.toMovie() } }

    override suspend fun search(query: String): List<Movie> =
        call { api.search(query).results.orEmpty().map { it.toMovie() } }

    override suspend fun details(id: Int): MovieDetails =
        call { api.details(id).toDetails() }

    private suspend fun <T> call(block: suspend () -> T): T {
        if (settings.tmdbApiKey.value.isNullOrBlank()) throw CatalogException.MissingApiKey
        try {
            return block()
        } catch (e: HttpException) {
            throw if (e.code() == 401) CatalogException.Unauthorized else CatalogException.Http(e.code())
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
        const val BASE_URL = "https://api.themoviedb.org/3/"

        fun create(baseUrl: String, settings: SettingsRepository): TmdbCatalogRepository {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    // key is read per request so a changed key applies without restart
                    val key = settings.tmdbApiKey.value ?: throw MissingKeyException()
                    val url = chain.request().url.newBuilder()
                        .addQueryParameter("api_key", key)
                        .addQueryParameter("language", "ru-RU")
                        .build()
                    chain.proceed(chain.request().newBuilder().url(url).build())
                }
                .build()
            val json = Json { ignoreUnknownKeys = true }
            val api = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TmdbApi::class.java)
            return TmdbCatalogRepository(api, settings)
        }
    }
}

private class MissingKeyException : IOException("TMDB key missing")
