package dev.gamspi.videogallery.torrents.rutor

import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.torrents.ProviderUnavailableException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RutorProviderTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun provider() = RutorProvider(OkHttpClient()) { server.url("/").toString() }
    private fun fixture() = javaClass.getResource("/torrents/rutor_search.html")!!.readText()
    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }
    private fun movie(year: Int? = 1999) = MovieDetails(
        Movie(1, "Матрица", "The Matrix", year, null, ""), "tt0133093", null, emptyList(), null,
    )

    @Test
    fun searchesRussianTitleAndYear() = runBlocking {
        enqueue(fixture())
        provider().search(movie())
        val req = server.takeRequest()
        assertEquals("/search/0/0/000/0/%D0%9C%D0%B0%D1%82%D1%80%D0%B8%D1%86%D0%B0%201999", req.url.encodedPath)
    }

    @Test
    fun filtersByYear() = runBlocking {
        enqueue(fixture())
        val r = provider().search(movie(1999))
        assertEquals(
            listOf("f99edb3d16451a80283463eda2a9e8ea1fccacc9", "61065ea115b7cc3e8db9fb5ab1f6f327f08bd1c9"),
            r.map { it.infoHash },
        )
    }

    @Test
    fun emptyRussian_fallsBackToOriginalTitle() = runBlocking {
        enqueue("<html><body></body></html>")
        enqueue(fixture())
        val r = provider().search(movie())
        assertEquals(2, r.size)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("/search/0/0/000/0/The%20Matrix%201999", server.takeRequest().url.encodedPath)
    }

    @Test
    fun fallbackFails_returnsEmptyFirstResult() = runBlocking {
        enqueue("<html><body></body></html>")
        enqueue("", 503)
        val r = provider().search(movie())
        assertTrue(r.isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun yearFilter_respectsDigitBoundaries() = runBlocking {
        val html = fixture().replace("1999", "21999")
        enqueue(html)
        enqueue("<html><body></body></html>")
        val r = provider().search(movie(1999))
        assertTrue(r.isEmpty())
    }

    @Test
    fun http503_unavailable() {
        enqueue("", 503)
        val e = runCatching { runBlocking { provider().search(movie()) } }.exceptionOrNull()
        assertTrue(e is ProviderUnavailableException)
    }
}
