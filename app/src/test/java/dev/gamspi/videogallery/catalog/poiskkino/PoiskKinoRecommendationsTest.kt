package dev.gamspi.videogallery.catalog.poiskkino

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.cachingClient
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.settings.FakeSecretStore
import dev.gamspi.videogallery.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PoiskKinoRecommendationsTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun close() { server.close() }

    private fun repo(key: String? = "KEY", client: OkHttpClient = OkHttpClient()) = PoiskKinoCatalogRepository.create(
        server.url("/").toString(),
        SettingsRepository(FakeSecretStore(if (key == null) emptyMap() else mapOf("poiskkino_api_key" to key))), client)

    private fun body(json: String) = MockResponse.Builder().body(json).build()

    @Test fun recommendationsComeFromDetailsSimilarMoviesAndAreHttpCached() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("similar-cache").toFile()
        val client = cachingClient(dir)
        try {
            server.enqueue(body("""{"id":326,"name":"Побег","type":"movie","similarMovies":[
                {"id":535341,"name":"1+1","type":"movie","year":2011,"rating":{"kp":8.8},"poster":{"previewUrl":"https://p/1"}},
                {"id":77044,"name":"Друзья","type":"tv-series","rating":{"kp":8.6}},
                {"id":0,"name":"Без id"},{"id":5,"name":"Игра","type":"tv-show"}]}"""))
            val repo = repo(client = client)
            val first = repo.recommendations(326, MediaKind.Movie)
            assertEquals(listOf(535341 to MediaKind.Movie, 77044 to MediaKind.Series, 5 to MediaKind.Series),
                first.map { it.id to it.kind })
            assertEquals(8.8, first.first().rating!!, 0.001)
            assertEquals("https://p/1", first.first().posterUrl)
            assertEquals(first, repo.recommendations(326, MediaKind.Movie))
            val request = server.takeRequest()
            assertEquals("/v1.5/movie/326", request.url.encodedPath)
            assertEquals("KEY", request.headers["X-API-KEY"])
            assertEquals(1, server.requestCount)
        } finally {
            client.cache?.close()
            dir.deleteRecursively()
        }
    }

    @Test fun findByImdbSendsExternalIdFilterAndChecksTheId() = runBlocking {
        server.enqueue(body("""{"docs":[{"id":1,"name":"Чужой","type":"movie","externalId":{"imdb":"tt0000002"}},
            {"id":2,"name":"Нужный","type":"tv-series","externalId":{"imdb":"tt0000001"}}]}"""))
        val found = repo().findByImdb("tt0000001", MediaKind.Series)
        assertEquals(2 to MediaKind.Series, found!!.id to found.kind)
        val url = server.takeRequest().url
        assertEquals("/v1.5/movie", url.encodedPath)
        assertEquals("tt0000001", url.queryParameter("externalId.imdb"))
    }

    @Test fun findByImdbTrustsOnlyASingleDocWithoutExternalIdAndPrefersKind() = runBlocking {
        server.enqueue(body("""{"docs":[{"id":3,"name":"Один","type":"movie"}]}"""))
        server.enqueue(body("""{"docs":[{"id":3,"name":"Один","type":"movie"},{"id":4,"name":"Два","type":"movie"}]}"""))
        server.enqueue(body("""{"docs":[{"id":5,"type":"movie","externalId":{"imdb":"tt0000001"}},
            {"id":6,"type":"tv-series","externalId":{"imdb":"tt0000001"}}]}"""))
        server.enqueue(body("""{"docs":[]}"""))
        assertEquals(3, repo().findByImdb("tt0000001", MediaKind.Movie)?.id)
        assertNull(repo().findByImdb("tt0000001", MediaKind.Movie))
        assertEquals(5, repo().findByImdb("tt0000001", MediaKind.Movie)?.id)
        assertNull(repo().findByImdb("tt0000001", MediaKind.Movie))
    }

    @Test fun missingKeyAndQuotaAreCatalogErrors() = runBlocking {
        try { repo(key = null).recommendations(1, MediaKind.Movie); throw AssertionError("Expected failure") }
        catch (e: CatalogException) { assertTrue(e is CatalogException.MissingApiKey) }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse.Builder().code(403).body("{}").build())
        try { repo().findByImdb("tt0000001", MediaKind.Movie); throw AssertionError("Expected failure") }
        catch (e: CatalogException) { assertTrue(e is CatalogException.QuotaExceeded) }
    }
}
