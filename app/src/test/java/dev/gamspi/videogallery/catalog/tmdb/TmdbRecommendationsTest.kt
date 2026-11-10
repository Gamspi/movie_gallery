package dev.gamspi.videogallery.catalog.tmdb

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

class TmdbRecommendationsTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun repo() = TmdbCatalogRepository.create(server.url("/3/").toString(),
        SettingsRepository(FakeSecretStore(mapOf("tmdb_api_key" to "KEY"))))

    private fun body(json: String) = MockResponse.Builder().body(json).build()

    @Test fun movieRecommendationsUseOneRequestAndMapCards() = runBlocking {
        server.enqueue(body("""{"results":[{"id":7,"title":"Семь","release_date":"1995-09-22","vote_average":8.4},{"id":0}]}"""))
        val movies = repo().recommendations(550, MediaKind.Movie)
        assertEquals(listOf(7), movies.map { it.id })
        assertEquals("Семь", movies.single().title)
        assertEquals(1995, movies.single().year)
        assertEquals(8.4, movies.single().rating!!, 0.001)
        assertEquals(MediaKind.Movie, movies.single().kind)
        val url = server.takeRequest().url
        assertEquals("/3/movie/550/recommendations", url.encodedPath)
        assertEquals("ru-RU", url.queryParameter("language"))
        assertEquals(1, server.requestCount)
    }

    @Test fun emptySeriesRecommendationsFallBackToSimilar() = runBlocking {
        server.enqueue(body("""{"results":[]}"""))
        server.enqueue(body("""{"results":[{"id":3,"name":"Сериал","first_air_date":"2020-01-01"}]}"""))
        val series = repo().recommendations(1399, MediaKind.Series)
        assertEquals(listOf(3 to MediaKind.Series), series.map { it.id to it.kind })
        assertEquals("Сериал", series.single().title)
        assertEquals("/3/tv/1399/recommendations", server.takeRequest().url.encodedPath)
        assertEquals("/3/tv/1399/similar", server.takeRequest().url.encodedPath)
    }

    @Test fun findByImdbPrefersRequestedKindAndFallsBackToTheOther() = runBlocking {
        val both = """{"movie_results":[{"id":11,"title":"Фильм"}],"tv_results":[{"id":22,"name":"Сериал"}]}"""
        server.enqueue(body(both))
        server.enqueue(body(both))
        server.enqueue(body("""{"movie_results":[{"id":11,"title":"Фильм"}],"tv_results":[]}"""))
        server.enqueue(body("""{"movie_results":[],"tv_results":[]}"""))
        assertEquals(11 to MediaKind.Movie, repo().findByImdb("tt0137523", MediaKind.Movie)!!.let { it.id to it.kind })
        assertEquals(22 to MediaKind.Series, repo().findByImdb("tt0137523", MediaKind.Series)!!.let { it.id to it.kind })
        assertEquals(11 to MediaKind.Movie, repo().findByImdb("tt0137523", MediaKind.Series)!!.let { it.id to it.kind })
        assertNull(repo().findByImdb("tt0000001", MediaKind.Movie))
        val url = server.takeRequest().url
        assertEquals("/3/find/tt0137523", url.encodedPath)
        assertEquals("imdb_id", url.queryParameter("external_source"))
    }

    @Test fun httpErrorsBecomeCatalogExceptions() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        try {
            repo().recommendations(1, MediaKind.Movie)
            throw AssertionError("Expected CatalogException")
        } catch (e: CatalogException) {
            assertTrue(e is CatalogException.Unauthorized)
        }
    }
}
