package dev.gamspi.videogallery.torrents.rutor

import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.ProviderUnavailableException
import dev.gamspi.videogallery.torrents.TorrentProvider
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class RutorProvider(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
) : TorrentProvider {
    override val id = ProviderId.Rutor

    override suspend fun search(movie: MovieDetails): List<TorrentResult> = withContext(Dispatchers.IO) {
        val m = movie.movie
        val year = m.year?.toString()
        var results = fetch(listOfNotNull(m.title, year).joinToString(" "), year)
        val original = m.originalTitle
        if (results.isEmpty() && !original.isNullOrBlank() && original != m.title) {
            results = try {
                fetch(listOfNotNull(original, year).joinToString(" "), year)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results
            }
        }
        results
    }

    private suspend fun fetch(query: String, year: String?): List<TorrentResult> {
        val html = try {
            val url = baseUrl().toHttpUrl().newBuilder()
                .addPathSegments("search/0/0/000/0")
                .addPathSegment(query)
                .build()
            client.newCall(Request.Builder().url(url).build()).await().use { resp ->
                if (!resp.isSuccessful) throw ProviderUnavailableException()
                withContext(Dispatchers.IO) { resp.body.string() }
            }
        } catch (e: ProviderUnavailableException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProviderUnavailableException()
        }
        val yearRe = year?.let { Regex("(?<!\\d)$it(?!\\d)") }
        return RutorParser.parse(html)
            .filter { it.infoHash.isNotBlank() }
            .filter { yearRe == null || yearRe.containsMatchIn(it.title) }
    }
}
