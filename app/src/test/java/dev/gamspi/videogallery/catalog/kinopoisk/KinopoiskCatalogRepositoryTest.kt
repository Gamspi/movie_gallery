package dev.gamspi.videogallery.catalog.kinopoisk

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.settings.FakeSecretStore
import dev.gamspi.videogallery.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KinopoiskCatalogRepositoryTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun repo(key: String? = "KEY"): KinopoiskCatalogRepository {
        val store = FakeSecretStore(if (key == null) emptyMap() else mapOf("kinopoisk_api_key" to key))
        return KinopoiskCatalogRepository.create(server.url("/").toString(), SettingsRepository(store))
    }

    private fun fixture(name: String) = javaClass.getResource("/kinopoisk/$name")!!.readText()

    private fun enqueueFixture(name: String) {
        server.enqueue(MockResponse.Builder().body(fixture(name)).build())
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: CatalogException) {
            return e
        }
        throw AssertionError("expected CatalogException")
    }

    @Test
    fun popular_mapsFields_andSendsHeader() = runBlocking {
        enqueueFixture("popular.json")
        val m = repo().popular(2).first()
        assertEquals(301, m.id)
        assertEquals("Матрица", m.title)
        assertEquals("The Matrix", m.originalTitle)
        assertEquals(1999, m.year)
        assertEquals("https://kinopoiskapiunofficial.tech/images/posters/kp_small/301.jpg", m.posterUrl)
        val req = server.takeRequest()
        assertEquals("KEY", req.headers["X-API-KEY"])
        assertEquals("/api/v2.2/films/collections", req.url.encodedPath)
        assertEquals("TOP_POPULAR_MOVIES", req.url.queryParameter("type"))
        assertEquals("2", req.url.queryParameter("page"))
    }

    @Test
    fun popular_dropsSeries() = runBlocking {
        enqueueFixture("popular.json")
        val ids = repo().popular().map { it.id }
        assertTrue(77044 !in ids)
        assertEquals(listOf(301, 600, 700, 800), ids)
    }

    @Test
    fun movie_withoutNameRu_usesOriginal_nullPosterAndYear() = runBlocking {
        enqueueFixture("popular.json")
        val list = repo().popular()
        val m = list.first { it.id == 600 }
        assertEquals("Original Only", m.title)
        assertEquals("Original Only", m.originalTitle)
        assertNull(m.posterUrl)
        assertNull(m.year)
        assertEquals("", m.overview)
        // year as numeric string is tolerated; posterUrlPreview null -> posterUrl
        val s = list.first { it.id == 700 }
        assertEquals(2001, s.year)
        assertEquals("https://kinopoiskapiunofficial.tech/images/posters/kp/700.jpg", s.posterUrl)
        // empty names -> nameEn; garbage year -> null; empty poster -> null
        val e = list.first { it.id == 800 }
        assertEquals("English Only", e.title)
        assertNull(e.year)
        assertNull(e.posterUrl)
    }

    @Test
    fun genres_filtersAndCapitalizes() = runBlocking {
        enqueueFixture("filters.json")
        val names = repo().genres().map { it.name }
        assertEquals(listOf("Триллер", "Драма", "Фантастика"), names)
        assertEquals("/api/v2.2/films/filters", server.takeRequest().url.encodedPath)
    }

    @Test
    fun byGenre_sendsGenreAndTypeFilm() = runBlocking {
        enqueueFixture("by_genre.json")
        assertEquals(2, repo().byGenre(11, 3).size)
        val url = server.takeRequest().url
        assertEquals("/api/v2.2/films", url.encodedPath)
        assertEquals("11", url.queryParameter("genres"))
        assertEquals("FILM", url.queryParameter("type"))
        assertEquals("NUM_VOTE", url.queryParameter("order"))
        assertEquals("3", url.queryParameter("page"))
    }

    @Test
    fun search_sendsKeyword_emptyReturnsEmpty() = runBlocking {
        enqueueFixture("search.json")
        val r = repo()
        assertEquals(1, r.search("матрица").size)
        val url = server.takeRequest().url
        assertEquals("матрица", url.queryParameter("keyword"))
        assertEquals("FILM", url.queryParameter("type"))
        assertEquals("1", url.queryParameter("page"))
        enqueueFixture("search_empty.json")
        assertTrue(r.search("zzz").isEmpty())
    }

    @Test
    fun details_mapsImdbRuntimeCoverGenres() = runBlocking {
        enqueueFixture("details.json")
        val d = repo().details(301)
        assertEquals(301, d.movie.id)
        assertEquals("Матрица", d.movie.title)
        assertEquals("tt0133093", d.imdbId)
        assertEquals(136, d.runtimeMin)
        assertEquals(listOf("Фантастика", "Боевик"), d.genres)
        assertEquals("https://avatars.mds.yandex.net/get-ott/1648503/2a0000016b6c1a2f/orig", d.backdropUrl)
        assertTrue(d.movie.overview.startsWith("Хакер"))
        assertEquals("/api/v2.2/films/301", server.takeRequest().url.encodedPath)
    }

    @Test
    fun missingKey_noRequest() = runBlocking {
        val e = expectFailure { repo(key = null).popular() }
        assertEquals(CatalogException.MissingApiKey, e)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun nonAsciiKey_badKey_noRequest() = runBlocking {
        assertEquals(CatalogException.Unauthorized, expectFailure { repo("ключ123").popular() })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun http401_unauthorized() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        assertEquals(CatalogException.Unauthorized, expectFailure { repo().popular() })
    }

    @Test
    fun http402_quotaExceeded() = runBlocking {
        server.enqueue(MockResponse.Builder().code(402).body("{}").build())
        assertEquals(CatalogException.QuotaExceeded, expectFailure { repo().popular() })
    }

    @Test
    fun http500_http() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        assertEquals(CatalogException.Http(500), expectFailure { repo().popular() })
    }

    @Test
    fun ioError_network() = runBlocking {
        repeat(2) { server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
        assertEquals(CatalogException.Network, expectFailure { repo().popular() })
    }

    @Test
    fun garbageBody_parse() = runBlocking {
        server.enqueue(MockResponse.Builder().body("<html>captive portal</html>").build())
        assertEquals(CatalogException.Parse, expectFailure { repo().popular() })
    }
}
