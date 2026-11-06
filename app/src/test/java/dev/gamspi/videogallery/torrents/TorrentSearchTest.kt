package dev.gamspi.videogallery.torrents

import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import java.net.ServerSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSearchTest {
    private val movie = MovieDetails(Movie(1, "Матрица", "The Matrix", 1999, null, ""), null, null, emptyList(), null)

    private fun r(hash: String, seeds: Int, p: ProviderId) =
        TorrentResult("t", p, 1L, seeds, 0, null, hash, "m")

    private fun topic(id: String, seeds: Int) = TorrentResult(
        "t$id", ProviderId.Rutracker, 1L, seeds, 0, null, "", "", topicUrl = "https://rt.test/forum/viewtopic.php?t=$id",
    )

    private class Fake(
        override val id: ProviderId,
        val resolver: (suspend (TorrentResult) -> TorrentResult)? = null,
        val block: suspend () -> List<TorrentResult>,
    ) : TorrentProvider {
        override suspend fun search(movie: MovieDetails) = block()
        override suspend fun resolve(result: TorrentResult): TorrentResult =
            resolver?.invoke(result) ?: super.resolve(result)
    }

    @Test fun merges_dedupsByHash_keepsMaxSeeds_sortsBySeeds() = runTest {
        val a = Fake(ProviderId.Rutor) { listOf(r("abc", 5, ProviderId.Rutor), r("low", 1, ProviderId.Rutor)) }
        val b = Fake(ProviderId.Apibay) { listOf(r("ABC", 50, ProviderId.Apibay), r("mid", 10, ProviderId.Apibay)) }
        val out = TorrentSearch({ listOf(a, b) }).search(movie)
        assertEquals(listOf(50, 10, 1), out.results.map { it.seeders })
        assertEquals(ProviderId.Apibay, out.results.first().provider)
        assertTrue(out.unavailable.isEmpty())
    }

    @Test fun failingProvider_unavailable_othersReturned() = runTest {
        val a = Fake(ProviderId.Rutor) { throw ProviderUnavailableException() }
        val b = Fake(ProviderId.Apibay) { listOf(r("x", 3, ProviderId.Apibay)) }
        val out = TorrentSearch({ listOf(a, b) }).search(movie)
        assertEquals(1, out.results.size)
        assertEquals(listOf(ProviderId.Rutor), out.unavailable)
    }

    @Test fun slowProvider_timesOut_unavailable() = runTest {
        val a = Fake(ProviderId.Rutor) { delay(100_000); emptyList() }
        val b = Fake(ProviderId.Apibay) { listOf(r("x", 3, ProviderId.Apibay)) }
        val out = TorrentSearch({ listOf(a, b) }, timeoutMs = 15_000).search(movie)
        assertEquals(listOf(ProviderId.Rutor), out.unavailable)
        assertEquals(1, out.results.size)
    }

    @Test fun allProvidersFail_emptyResults_allUnavailable() = runTest {
        val a = Fake(ProviderId.Rutor) { throw ProviderUnavailableException() }
        val b = Fake(ProviderId.Apibay) { throw IllegalStateException("boom") }
        val c = Fake(ProviderId.Yts) { throw ProviderUnavailableException() }
        val out = TorrentSearch({ listOf(a, b, c) }).search(movie)
        assertTrue(out.results.isEmpty())
        assertEquals(listOf(ProviderId.Rutor, ProviderId.Apibay, ProviderId.Yts), out.unavailable)
    }

    @Test fun hangingRealHttpCall_abortedByTimeout() = runBlocking {
        // Accepts TCP connections (backlog) but never reads or answers.
        val server = ServerSocket(0)
        try {
            val client = OkHttpClient()
            val hung = Fake(ProviderId.Rutor) {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
                    .await().use { emptyList<TorrentResult>() }
            }
            val ok = Fake(ProviderId.Apibay) { listOf(r("x", 3, ProviderId.Apibay)) }
            val t0 = System.nanoTime()
            val out = TorrentSearch({ listOf(hung, ok) }, timeoutMs = 500).search(movie)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("took $ms ms", ms < 1500)
            assertEquals(listOf(ProviderId.Rutor), out.unavailable)
            assertEquals(1, out.results.size)
        } finally {
            server.close()
        }
    }

    @Test fun resultsWithoutHash_dedupByTopicUrl_notCollapsed() = runTest {
        val a = Fake(ProviderId.Rutracker) { listOf(topic("1", 5), topic("2", 7), topic("1", 9)) }
        val b = Fake(ProviderId.Apibay) { listOf(r("x", 3, ProviderId.Apibay)) }
        val out = TorrentSearch({ listOf(a, b) }).search(movie)
        assertEquals(listOf(9, 7, 3), out.results.map { it.seeders })
        assertEquals("https://rt.test/forum/viewtopic.php?t=1", out.results.first().topicUrl)
    }

    @Test fun unavailableReasons_reported() = runTest {
        val a = Fake(ProviderId.Rutracker) { throw ProviderUnavailableException(UnavailableReason.Captcha) }
        val b = Fake(ProviderId.Rutor) { throw IllegalStateException("boom") }
        val c = Fake(ProviderId.Apibay) { emptyList() }
        val out = TorrentSearch({ listOf(a, b, c) }).search(movie)
        assertEquals(listOf(ProviderId.Rutracker, ProviderId.Rutor), out.unavailable)
        assertEquals(
            mapOf(ProviderId.Rutracker to UnavailableReason.Captcha, ProviderId.Rutor to UnavailableReason.Unavailable),
            out.reasons,
        )
    }

    @Test fun resolve_usesProviderOfResult() = runTest {
        val rt = Fake(ProviderId.Rutracker, resolver = { it.copy(infoHash = "h", magnet = "magnet:?xt=urn:btih:h") }) {
            emptyList()
        }
        // Resolution works even when the provider is no longer enabled for search.
        val search = TorrentSearch({ emptyList() }, resolvers = { listOf(rt) })
        val out = search.resolve(topic("1", 1))
        assertEquals("h", out.infoHash)
        assertEquals("magnet:?xt=urn:btih:h", out.magnet)
    }

    @Test fun resolve_resultWithMagnet_returnedAsIs() = runTest {
        val plain = r("abc", 1, ProviderId.Rutor)
        assertEquals(plain, TorrentSearch({ emptyList() }).resolve(plain))
    }

    @Test fun resolve_failuresBecomeUnavailable() = runTest {
        val noProvider = runCatching { TorrentSearch({ emptyList() }).resolve(topic("1", 1)) }.exceptionOrNull()
        assertTrue(noProvider is ProviderUnavailableException)
        val slow = Fake(ProviderId.Rutracker, resolver = { delay(100_000); it }) { emptyList() }
        val timeout = runCatching {
            TorrentSearch({ listOf(slow) }, timeoutMs = 1_000).resolve(topic("1", 1))
        }.exceptionOrNull()
        assertTrue(timeout is ProviderUnavailableException)
        val blank = Fake(ProviderId.Rutracker, resolver = { it }) { emptyList() }
        val stillBlank = runCatching { TorrentSearch({ listOf(blank) }).resolve(topic("1", 1)) }.exceptionOrNull()
        assertTrue(stillBlank is ProviderUnavailableException)
    }

    @Test fun noProviders_emptyOutcome() = runTest {
        val out = TorrentSearch({ emptyList() }).search(movie)
        assertEquals(SearchOutcome(emptyList(), emptyList()), out)
        assertTrue(out.reasons.isEmpty())
    }
}
