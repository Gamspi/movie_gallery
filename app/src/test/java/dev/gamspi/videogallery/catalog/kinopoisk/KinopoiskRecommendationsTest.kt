package dev.gamspi.videogallery.catalog.kinopoisk

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.settings.FakeSecretStore
import dev.gamspi.videogallery.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KinopoiskRecommendationsTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun repo(key: String? = "KEY") = KinopoiskCatalogRepository.create(server.url("/").toString(),
        SettingsRepository(FakeSecretStore(if (key == null) emptyMap() else mapOf("kinopoisk_api_key" to key))))

    private fun body(json: String) = MockResponse.Builder().body(json).build()

    @Test fun similarsReadTypeAndRatingFromDetailsAndSkipBrokenOnes() = runBlocking {
        server.enqueue(body("""{"total":4,"items":[{"filmId":10},{"filmId":20},{"filmId":10},{"filmId":30},{"filmId":0}]}"""))
        server.enqueue(body("""{"kinopoiskId":10,"nameRu":"Фильм","type":"FILM","year":2001,"ratingKinopoisk":7.9}"""))
        server.enqueue(body("""{"kinopoiskId":20,"nameRu":"Сериал","type":"TV_SERIES","ratingKinopoisk":8.5}"""))
        server.enqueue(MockResponse.Builder().code(404).body("{}").build())
        val movies = repo().recommendations(301, MediaKind.Movie)
        assertEquals(listOf(10 to MediaKind.Movie, 20 to MediaKind.Series), movies.map { it.id to it.kind })
        assertEquals(7.9, movies.first().rating!!, 0.001)
        assertEquals(2001, movies.first().year)
        val paths = (1..4).map { server.takeRequest().url.encodedPath }
        assertEquals(listOf("/api/v2.2/films/301/similars", "/api/v2.2/films/10", "/api/v2.2/films/20", "/api/v2.2/films/30"), paths)
        // No seasons or distributions for the candidates.
        assertEquals(4, server.requestCount)
    }

    @Test fun similarsReadAtMostTenDetails() = runBlocking {
        server.enqueue(body("""{"items":[${(1..15).joinToString(",") { """{"filmId":$it}""" }}]}"""))
        repeat(10) { server.enqueue(body("""{"kinopoiskId":${it + 1},"nameRu":"Ф","type":"FILM"}""")) }
        assertEquals(10, repo().recommendations(1, MediaKind.Movie).size)
        assertEquals(11, server.requestCount)
    }

    @Test fun findByImdbFiltersByIdAndPrefersKind() = runBlocking {
        val items = """{"items":[{"kinopoiskId":1,"imdbId":"tt0000009","type":"FILM"},
            {"kinopoiskId":2,"imdbId":"tt0000001","type":"FILM"},{"kinopoiskId":3,"imdbId":"tt0000001","type":"TV_SERIES"}]}"""
        server.enqueue(body(items))
        server.enqueue(body(items))
        server.enqueue(body("""{"items":[{"kinopoiskId":1,"imdbId":"tt0000009","type":"FILM"}]}"""))
        assertEquals(3 to MediaKind.Series, repo().findByImdb("tt0000001", MediaKind.Series)!!.let { it.id to it.kind })
        assertEquals(2 to MediaKind.Movie, repo().findByImdb("tt0000001", MediaKind.Movie)!!.let { it.id to it.kind })
        assertNull(repo().findByImdb("tt0000001", MediaKind.Movie))
        val url = server.takeRequest().url
        assertEquals("/api/v2.2/films", url.encodedPath)
        assertEquals("tt0000001", url.queryParameter("imdbId"))
    }

    @Test fun missingKeyMakesNoRequest() = runBlocking {
        try { repo(null).recommendations(1, MediaKind.Movie); throw AssertionError("Expected failure") }
        catch (e: CatalogException) { assertTrue(e is CatalogException.MissingApiKey) }
        assertEquals(0, server.requestCount)
    }
}
