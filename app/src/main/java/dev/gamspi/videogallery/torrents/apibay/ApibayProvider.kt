package dev.gamspi.videogallery.torrents.apibay

import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.ProviderUnavailableException
import dev.gamspi.videogallery.torrents.TorrentProvider
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.magnetOf
import dev.gamspi.videogallery.torrents.parseQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal class ApibayItem(
    val id: String = "",
    val name: String = "",
    val info_hash: String = "",
    val leechers: String = "0",
    val seeders: String = "0",
    val size: String = "0",
    val category: String = "",
    val imdb: String = "",
)

class ApibayProvider(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
) : TorrentProvider {
    override val id = ProviderId.Apibay

    override suspend fun search(movie: MovieDetails): List<TorrentResult> = withContext(Dispatchers.IO) {
        val query = listOfNotNull(movie.movie.originalTitle, movie.movie.year?.toString()).joinToString(" ")
        val items = try {
            val url = baseUrl().toHttpUrl().newBuilder()
                .addPathSegment("q.php")
                .addQueryParameter("q", query)
                .addQueryParameter("cat", "200")
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw ProviderUnavailableException()
                json.decodeFromString<List<ApibayItem>>(resp.body.string())
            }
        } catch (e: ProviderUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw ProviderUnavailableException()
        }
        items.filter { it.id != "0" && it.category in categories && it.info_hash.isNotBlank() }
            .filter { movie.imdbId == null || it.imdb.isEmpty() || it.imdb == movie.imdbId }
            .map {
                TorrentResult(
                    title = it.name,
                    provider = ProviderId.Apibay,
                    sizeBytes = it.size.toLongOrNull() ?: 0L,
                    seeders = it.seeders.toIntOrNull() ?: 0,
                    leechers = it.leechers.toIntOrNull() ?: 0,
                    quality = parseQuality(it.name),
                    infoHash = it.info_hash,
                    magnet = magnetOf(it.info_hash, it.name),
                )
            }
    }

    private companion object {
        val categories = setOf("201", "202", "207", "211")
        val json = Json { ignoreUnknownKeys = true }
    }
}
