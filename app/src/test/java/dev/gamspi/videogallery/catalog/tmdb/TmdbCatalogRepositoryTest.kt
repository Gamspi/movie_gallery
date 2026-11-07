package dev.gamspi.videogallery.catalog.tmdb

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.SeasonInfo
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

class TmdbCatalogRepositoryTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun repo(key: String? = "KEY"): TmdbCatalogRepository {
        val store = FakeSecretStore(if (key == null) emptyMap() else mapOf("tmdb_api_key" to key))
        return TmdbCatalogRepository.create(server.url("/3/").toString(), SettingsRepository(store))
    }

    private fun fixture(name: String) = javaClass.getResource("/tmdb/$name")!!.readText()

    private fun enqueueFixture(name: String) {
        server.enqueue(MockResponse.Builder().body(fixture(name)).build())
    }

    @Test
    fun popular_mapsFields() = runBlocking {
        enqueueFixture("popular.json")
        val m = repo().popular().first()
        assertEquals(550, m.id)
        assertEquals("Бойцовский клуб", m.title)
        assertEquals("Fight Club", m.originalTitle)
        assertEquals(1999, m.year)
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", m.posterUrl)
        assertEquals(8.438, m.rating!!, 1e-9)
        val url = server.takeRequest().url
        assertEquals("/3/movie/popular", url.encodedPath)
        assertEquals("KEY", url.queryParameter("api_key"))
        assertEquals("ru-RU", url.queryParameter("language"))
    }

    @Test
    fun popular_nullPosterAndEmptyDate() = runBlocking {
        enqueueFixture("popular.json")
        val list = repo().popular()
        assertNull(list[1].posterUrl)
        assertNull(list[1].year)
        assertEquals("Без постера", list[1].originalTitle) // empty original_title
        assertEquals("Матрица", list[2].originalTitle) // original_title absent
        assertNull(list[1].rating) // zero vote_average
        assertNull(list[2].rating) // vote_average absent
    }

    @Test
    fun details_readsImdbId() = runBlocking {
        enqueueFixture("details.json")
        val d = repo().details(550)
        assertEquals("tt0137523", d.imdbId)
        assertEquals(139, d.runtimeMin)
        assertEquals(8.4, d.movie.rating!!, 1e-9)
        assertEquals(listOf("драма", "триллер"), d.genres)
        assertEquals("https://image.tmdb.org/t/p/w1280/bg.jpg", d.backdropUrl)
        val url = server.takeRequest().url
        assertEquals("/3/movie/550", url.encodedPath)
        assertEquals("external_ids", url.queryParameter("append_to_response"))
    }

    @Test
    fun tvPopularMapsSeries() = runBlocking {
        enqueueFixture("tv_popular.json")
        val m = repo().popular(1, MediaKind.Series).first()
        assertEquals(MediaKind.Series, m.kind)
        assertEquals("Игра престолов", m.title)
        assertEquals("Game of Thrones", m.originalTitle)
        assertEquals(2011, m.year)
        assertEquals("/3/tv/popular", server.takeRequest().url.encodedPath)
    }

    @Test
    fun tvGenresAndDiscoverUseTvEndpoints() = runBlocking {
        enqueueFixture("genres.json")
        repo().genres(MediaKind.Series)
        assertEquals("/3/genre/tv/list", server.takeRequest().url.encodedPath)
        enqueueFixture("tv_popular.json")
        assertEquals(MediaKind.Series, repo().byGenre(28, 1, MediaKind.Series).first().kind)
        assertEquals("/3/discover/tv", server.takeRequest().url.encodedPath)
    }

    @Test
    fun searchMultiKeepsMoviesAndSeriesDropsPeople() = runBlocking {
        enqueueFixture("search_multi.json")
        val list = repo().search("pitt")
        assertEquals(listOf(MediaKind.Movie, MediaKind.Series), list.map { it.kind })
        assertEquals(listOf(550, 1399), list.map { it.id })
        assertEquals("/3/search/multi", server.takeRequest().url.encodedPath)
    }

    @Test
    fun tvDetailsMapsSeasonsWithoutSpecials() = runBlocking {
        enqueueFixture("tv_details.json")
        val d = repo().details(1399, MediaKind.Series)
        assertEquals(listOf(SeasonInfo(1, 10), SeasonInfo(2, 8)), d.seasons)
        assertEquals("tt0944947", d.imdbId)
        assertEquals(MediaKind.Series, d.movie.kind)
        assertEquals(60, d.runtimeMin)
        val url = server.takeRequest().url
        assertEquals("/3/tv/1399", url.encodedPath)
        assertEquals("external_ids", url.queryParameter("append_to_response"))
    }

    @Test
    fun seasonMapsEpisodes() = runBlocking {
        enqueueFixture("season.json")
        val eps = repo().season(1399, 2)
        assertEquals(Episode(2, 3, "Призраки", 51, "https://image.tmdb.org/t/p/w300/x.jpg"), eps[0])
        assertNull(eps[1].stillUrl)
        assertNull(eps[1].runtimeMin)
        assertEquals("/3/tv/1399/season/2", server.takeRequest().url.encodedPath)
    }

    @Test
    fun genres_mapsList() = runBlocking {
        enqueueFixture("genres.json")
        assertEquals("боевик", repo().genres().first().name)
    }

    @Test
    fun byGenre_sendsGenreId() = runBlocking {
        enqueueFixture("discover.json")
        assertEquals(2, repo().byGenre(28).size)
        val url = server.takeRequest().url
        assertEquals("28", url.queryParameter("with_genres"))
        assertEquals("popularity.desc", url.queryParameter("sort_by"))
    }

    @Test
    fun search_returnsMovies() = runBlocking {
        enqueueFixture("search.json")
        assertEquals(1, repo().search("fight").size)
        assertEquals("fight", server.takeRequest().url.queryParameter("query"))
    }

    @Test
    fun search_empty_returnsEmptyList() = runBlocking {
        enqueueFixture("search_empty.json")
        assertTrue(repo().search("zzz").isEmpty())
    }

    @Test
    fun key_isReadPerRequest() = runBlocking {
        val settings = SettingsRepository(FakeSecretStore(mapOf("tmdb_api_key" to "A")))
        val repo = TmdbCatalogRepository.create(server.url("/3/").toString(), settings)
        enqueueFixture("popular.json")
        enqueueFixture("popular.json")
        repo.popular()
        settings.setTmdbApiKey("B")
        repo.popular()
        assertEquals("A", server.takeRequest().url.queryParameter("api_key"))
        assertEquals("B", server.takeRequest().url.queryParameter("api_key"))
    }

    @Test
    fun missingKey_throwsMissingApiKey() = runBlocking {
        try {
            repo(key = null).popular()
            throw AssertionError("expected MissingApiKey")
        } catch (e: CatalogException.MissingApiKey) {
            assertEquals(0, server.requestCount)
        }
        Unit
    }

    @Test
    fun http401_throwsUnauthorized() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        try {
            repo().popular()
            throw AssertionError("expected Unauthorized")
        } catch (e: CatalogException.Unauthorized) {
            // expected
        }
        Unit
    }

    @Test
    fun http500_throwsHttp() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        try {
            repo().popular()
            throw AssertionError("expected Http")
        } catch (e: CatalogException.Http) {
            assertEquals(500, e.code)
        }
        Unit
    }

    @Test
    fun ioError_throwsNetwork() = runBlocking {
        // two responses: OkHttp may retry once on connection failure; avoid waiting on an empty queue
        repeat(2) { server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
        try {
            repo().popular()
            throw AssertionError("expected Network")
        } catch (e: CatalogException.Network) {
            // expected
        }
        Unit
    }

    @Test
    fun garbageBody_throwsParse() = runBlocking {
        server.enqueue(MockResponse.Builder().body("<html>captive portal</html>").build())
        try {
            repo().popular()
            throw AssertionError("expected Parse")
        } catch (e: CatalogException.Parse) {
            // expected
        }
        Unit
    }
}
