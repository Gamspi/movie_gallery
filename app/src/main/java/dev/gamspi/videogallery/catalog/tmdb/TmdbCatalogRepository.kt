package dev.gamspi.videogallery.catalog.tmdb

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.withCatalogCredentials
import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.catalog.model.upcomingDateWindow
import dev.gamspi.videogallery.catalog.model.upcomingTitles
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
    override suspend fun recommendations(id: Int, kind: MediaKind): List<Movie> = call {
        val type = if (kind == MediaKind.Series) "tv" else "movie"
        // Recommendations are empty for little-rated titles; similar is the documented fallback.
        val found = api.recommendations(type, id).results.orEmpty().filter { it.id > 0 }
            .ifEmpty { api.similar(type, id).results.orEmpty().filter { it.id > 0 } }
        found.map { if (kind == MediaKind.Series) it.toSeries() else it.toMovie() }
    }

    override suspend fun findByImdb(imdbId: String, kind: MediaKind): Movie? = call {
        val result = api.findByImdb(imdbId)
        val movie = result.movies.orEmpty().firstOrNull { it.id > 0 }?.toMovie()
        val series = result.series.orEmpty().firstOrNull { it.id > 0 }?.toSeries()
        if (kind == MediaKind.Series) series ?: movie else movie ?: series
    }

    override suspend fun upcoming(today: java.time.LocalDate, kind: MediaKind?): List<Movie> = call {
        val window = upcomingDateWindow(today)
        val from = window.start.toString()
        val to = window.endInclusive.toString()
        val movies = if (kind != MediaKind.Series) api.upcomingMovies(from, to).results.orEmpty()
            .filter { !it.posterPath.isNullOrBlank() }.map { it.toMovie() } else emptyList()
        val series = if (kind != MediaKind.Movie) api.upcomingSeries(from, to).results.orEmpty()
            .filter { !it.posterPath.isNullOrBlank() }.map { it.toSeries() } else emptyList()
        upcomingTitles(movies + series, today)
    }

    override suspend fun popular(page: Int, kind: MediaKind): List<Movie> = call {
        if (kind == MediaKind.Series) api.tvPopular(page).results.orEmpty().map { it.toSeries() }
        else api.popular(page).results.orEmpty().map { it.toMovie() }
    }

    override suspend fun genres(kind: MediaKind): List<Genre> = call {
        val dto = if (kind == MediaKind.Series) api.tvGenres() else api.genres()
        dto.genres.orEmpty().map { it.toGenre() }
    }

    override suspend fun byGenre(genreId: Int, page: Int, kind: MediaKind): List<Movie> = call {
        if (kind == MediaKind.Series) api.tvDiscover(genreId, page).results.orEmpty().map { it.toSeries() }
        else api.discover(genreId, page).results.orEmpty().map { it.toMovie() }
    }

    override suspend fun search(query: String): List<Movie> =
        call { api.searchMulti(query).results.orEmpty().mapNotNull { it.toMultiResult() } }

    override suspend fun details(id: Int, kind: MediaKind): MovieDetails = call {
        if (kind == MediaKind.Series) api.tvDetails(id).toSeriesDetails() else api.details(id).toDetails()
    }

    override suspend fun season(id: Int, number: Int): List<Episode> =
        call { api.season(id, number).toEpisodes(number) }

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

        fun create(baseUrl: String, settings: SettingsRepository, client: OkHttpClient = OkHttpClient()): TmdbCatalogRepository {
            val configured = client.newBuilder()
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
                .build().withCatalogCredentials("tmdb")
            val json = Json { ignoreUnknownKeys = true }
            val api = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(configured)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TmdbApi::class.java)
            return TmdbCatalogRepository(api, settings)
        }
    }
}

private class MissingKeyException : IOException("TMDB key missing")
