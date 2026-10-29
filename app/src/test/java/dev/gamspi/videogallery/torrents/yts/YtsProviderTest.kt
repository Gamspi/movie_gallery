package dev.gamspi.videogallery.torrents.yts

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

class YtsProviderTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun provider() = YtsProvider(OkHttpClient()) { server.url("/").toString() }
    private fun fixture(name: String) = javaClass.getResource("/torrents/$name")!!.readText()
    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }
    private fun movie(imdb: String? = "tt0133093") = MovieDetails(
        Movie(1, "Матрица", "The Matrix", 1999, null, ""), imdb, null, emptyList(), null,
    )

    @Test
    fun parsesTorrents() = runBlocking {
        enqueue(fixture("yts.json"))
        val list = provider().search(movie())
        assertEquals(2, list.size) // blank hash dropped
        val r = list.first()
        assertEquals("The Matrix (1999) 1080p bluray", r.title)
        assertEquals(ProviderId.Yts, r.provider)
        assertEquals(1932735283L, r.sizeBytes)
        assertEquals(100, r.seeders)
        assertEquals(10, r.leechers)
        assertEquals(Quality.FullHd, r.quality)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", r.infoHash)
        assertTrue(r.magnet.startsWith("magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&dn="))
        assertEquals(Quality.Hd, list[1].quality)
    }

    @Test
    fun sendsImdbQuery() = runBlocking {
        enqueue(fixture("yts_empty.json"))
        provider().search(movie())
        val req = server.takeRequest()
        assertEquals("/api/v2/list_movies.json", req.url.encodedPath)
        assertEquals("tt0133093", req.url.queryParameter("query_term"))
    }

    @Test
    fun noImdb_noRequest_empty() = runBlocking {
        assertTrue(provider().search(movie(imdb = null)).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun noMovies_empty() = runBlocking {
        enqueue(fixture("yts_empty.json"))
        assertTrue(provider().search(movie()).isEmpty())
    }

    @Test
    fun garbage_unavailable() = runBlocking {
        enqueue("<html>not json</html>")
        try {
            provider().search(movie())
            throw AssertionError("expected ProviderUnavailableException")
        } catch (_: ProviderUnavailableException) {
        }
    }
}
