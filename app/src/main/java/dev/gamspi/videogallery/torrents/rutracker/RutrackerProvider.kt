package dev.gamspi.videogallery.torrents.rutracker

import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.ProviderUnavailableException
import dev.gamspi.videogallery.torrents.TorrentProvider
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.await
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class RutrackerProvider(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
    private val session: RutrackerSession,
) : TorrentProvider {
    override val id = ProviderId.Rutracker

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

    /** Takes the magnet from the topic page; the .torrent download is never used. */
    override suspend fun resolve(result: TorrentResult): TorrentResult = withContext(Dispatchers.IO) {
        if (result.magnet.isNotBlank()) return@withContext result
        val url = result.topicUrl?.toHttpUrlOrNull() ?: throw ProviderUnavailableException()
        val magnet = RutrackerParser.parseMagnet(page(url)) ?: throw ProviderUnavailableException()
        result.copy(infoHash = magnet.infoHash, magnet = magnet.magnet)
    }

    private suspend fun fetch(query: String, year: String?): List<TorrentResult> {
        val base = baseUrl()
        // The site expects windows-1251 in the query string.
        val url = RutrackerSession.forumUrl(base, "tracker.php").newBuilder()
            .addEncodedQueryParameter("nm", URLEncoder.encode(query, CP1251.name()))
            .build()
        val yearRe = year?.let { Regex("(?<!\\d)$it(?!\\d)") }
        return RutrackerParser.parseSearch(page(url), base)
            .filter { RutrackerParser.isFilmForum(it.forum) }
            .map { it.result }
            .filter { yearRe == null || yearRe.containsMatchIn(it.title) }
    }

    /** Member page text; logs in again once when the site shows the login form. */
    private suspend fun page(url: HttpUrl): String {
        val cookie = session.cookie()
        val html = get(url, cookie)
        if (!RutrackerParser.isLoginPage(html)) return html
        val retry = get(url, session.relogin(cookie))
        if (RutrackerParser.isLoginPage(retry)) throw ProviderUnavailableException()
        return retry
    }

    private suspend fun get(url: HttpUrl, cookie: String): String = try {
        val request = Request.Builder().url(url).header("Cookie", cookie).build()
        client.newCall(request).await().use { resp ->
            if (!resp.isSuccessful) throw ProviderUnavailableException()
            resp.rutrackerText()
        }
    } catch (e: ProviderUnavailableException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ProviderUnavailableException()
    }
}
