package dev.gamspi.videogallery.torrents

import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSearchTest {
    private val movie = MovieDetails(Movie(1, "Матрица", "The Matrix", 1999, null, ""), null, null, emptyList(), null)

    private fun r(hash: String, seeds: Int, p: ProviderId) =
        TorrentResult("t", p, 1L, seeds, 0, null, hash, "m")

    private class Fake(
        override val id: ProviderId,
        val block: suspend () -> List<TorrentResult>,
    ) : TorrentProvider {
        override suspend fun search(movie: MovieDetails) = block()
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

    @Test fun noProviders_emptyOutcome() = runTest {
        val out = TorrentSearch({ emptyList() }).search(movie)
        assertEquals(SearchOutcome(emptyList(), emptyList()), out)
    }
}
