package dev.gamspi.videogallery.torrents.yts

import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.ProviderUnavailableException
import dev.gamspi.videogallery.torrents.TorrentProvider
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.magnetOf
import dev.gamspi.videogallery.torrents.parseQuality
import dev.gamspi.videogallery.torrents.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal class YtsTorrent(
    val hash: String = "",
    val quality: String = "",
    val type: String = "",
    val seeds: Int = 0,
    val peers: Int = 0,
    val size_bytes: Long = 0,
)

@Serializable
internal class YtsMovie(
    val title_long: String = "",
    val torrents: List<YtsTorrent> = emptyList(),
)

@Serializable
internal class YtsData(val movies: List<YtsMovie> = emptyList())

@Serializable
internal class YtsResponse(val data: YtsData = YtsData())

class YtsProvider(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
) : TorrentProvider {
    override val id = ProviderId.Yts

    override suspend fun search(movie: MovieDetails): List<TorrentResult> {
        val imdb = movie.imdbId ?: return emptyList()
        val movies = withContext(Dispatchers.IO) {
            try {
                val url = baseUrl().toHttpUrl().newBuilder()
                    .addPathSegments("api/v2/list_movies.json")
                    .addQueryParameter("query_term", imdb)
                    .build()
                client.newCall(Request.Builder().url(url).build()).await().use { resp ->
                    if (!resp.isSuccessful) throw ProviderUnavailableException()
                    withContext(Dispatchers.IO) {
                        json.decodeFromString<YtsResponse>(resp.body.string()).data.movies
                    }
                }
            } catch (e: ProviderUnavailableException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ProviderUnavailableException()
            }
        }
        return movies.flatMap { m ->
            m.torrents.filter { it.hash.isNotBlank() }.map { t ->
                val title = "${m.title_long} ${t.quality} ${t.type}"
                TorrentResult(
                    title = title,
                    provider = ProviderId.Yts,
                    sizeBytes = t.size_bytes,
                    seeders = t.seeds,
                    leechers = t.peers,
                    quality = parseQuality(t.quality),
                    infoHash = t.hash,
                    magnet = magnetOf(t.hash, title),
                )
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
