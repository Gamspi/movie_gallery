package dev.gamspi.videogallery.torrents.apibay

import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.ProviderUnavailableException
import dev.gamspi.videogallery.torrents.Quality
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApibayProviderTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun provider() = ApibayProvider(OkHttpClient()) { server.url("/").toString() }
    private fun fixture(name: String) = javaClass.getResource("/torrents/$name")!!.readText()
    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }
    private fun movie(year: Int? = 1999, imdb: String? = "tt0133093") = MovieDetails(
        Movie(1, "Матрица", "The Matrix", year, null, ""), imdb, null, emptyList(), null,
    )

    @Test
    fun parsesResults_buildsMagnet_quality() = runBlocking {
        enqueue(fixture("apibay.json"))
        val r = provider().search(movie()).first()
        assertEquals("The Matrix (1999) 1080p BrRip x264 - 1.85GB - YIFY", r.title)
        assertEquals(ProviderId.Apibay, r.provider)
        assertEquals(1992277407L, r.sizeBytes)
        assertEquals(987, r.seeders)
        assertEquals(162, r.leechers)
        assertEquals(Quality.FullHd, r.quality)
        assertEquals("D7A46713EAEE18C746B3254B7D1492A50FD9D6CE", r.infoHash)
        assertTrue(r.magnet.startsWith("magnet:?xt=urn:btih:D7A46713EAEE18C746B3254B7D1492A50FD9D6CE&dn="))
    }

    @Test
    fun sendsTitleYearAndCategory() = runBlocking {
        enqueue(fixture("apibay_empty.json"))
        provider().search(movie())
        val req = server.takeRequest()
        assertEquals("/q.php", req.url.encodedPath)
        assertEquals("The Matrix 1999", req.url.queryParameter("q"))
        assertEquals("200", req.url.queryParameter("cat"))
        enqueue(fixture("apibay_empty.json"))
        provider().search(movie(year = null))
        assertEquals("The Matrix", server.takeRequest().url.queryParameter("q"))
    }

    @Test
    fun noResultsSentinel_empty() = runBlocking {
        enqueue(fixture("apibay_empty.json"))
        assertTrue(provider().search(movie()).isEmpty())
    }

    @Test
    fun filtersForeignImdbAndCategory() = runBlocking {
        enqueue(fixture("apibay.json"))
        val hashes = provider().search(movie()).map { it.infoHash }
        assertEquals(
            listOf(
                "D7A46713EAEE18C746B3254B7D1492A50FD9D6CE",
                "3333333333333333333333333333333333333333",
            ),
            hashes,
        )
    }

    @Test
    fun http500_unavailable() = runBlocking {
        enqueue("oops", 500)
        try {
            provider().search(movie())
            throw AssertionError("expected ProviderUnavailableException")
        } catch (_: ProviderUnavailableException) {
        }
    }
}
