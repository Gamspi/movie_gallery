package dev.gamspi.videogallery.catalog.poiskkino

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.settings.FakeSecretStore
import dev.gamspi.videogallery.settings.SettingsRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PoiskKinoCatalogRepositoryTest {
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun close() { server.close() }

    private fun settings(key: String? = "KEY") = SettingsRepository(FakeSecretStore(
        if (key == null) emptyMap() else mapOf("poiskkino_api_key" to key)))
    private fun repo(settings: SettingsRepository = settings(), client: OkHttpClient = OkHttpClient()) =
        PoiskKinoCatalogRepository.create(server.url("/").toString(), settings, client)
    private fun fixture(name: String) = javaClass.getResource("/poiskkino/$name")!!.readText()
    private fun enqueue(name: String) { server.enqueue(MockResponse.Builder().body(fixture(name)).build()) }
    private fun page(id: Int, next: String? = null) = MockResponse.Builder().body(
        """{"docs":[{"id":$id,"name":"Film$id","type":"movie"}],"next":${next?.let { "\"$it\"" } ?: "null"},"hasNext":${next != null}}""",
    ).build()
    private suspend fun failure(block: suspend () -> Any?): CatalogException {
        try { block() } catch (e: CatalogException) { return e }
        throw AssertionError("Expected CatalogException")
    }

    @Test fun popular_maps_fields_and_sends_official_cursor_request() = runBlocking {
        enqueue("popular.json")
        val movies = repo().popular()
        assertEquals(listOf(301, 600, 700, 800), movies.map { it.id })
        val movie = movies.first()
        assertEquals("Матрица", movie.title)
        assertEquals("The Matrix", movie.originalTitle)
        assertEquals(1999, movie.year)
        assertEquals("https://images.example/matrix-small.jpg", movie.posterUrl)
        assertEquals("Полное описание", movie.overview)
        val request = server.takeRequest()
        assertEquals("KEY", request.headers["X-API-KEY"])
        assertEquals("/v1.5/movie", request.url.encodedPath)
        assertEquals("popular-films", request.url.queryParameter("lists"))
        assertEquals("movie", request.url.queryParameter("type"))
        assertEquals("20", request.url.queryParameter("limit"))
        assertNull(request.url.queryParameter("page"))
        assertNull(request.url.queryParameter("next"))
    }

    @Test fun missing_names_and_nullable_or_string_years_have_fallbacks() = runBlocking {
        enqueue("popular.json")
        val movies = repo().popular()
        assertEquals("Original Only", movies[1].title)
        assertEquals("Original Only", movies[1].originalTitle)
        assertNull(movies[1].posterUrl)
        assertNull(movies[1].year)
        assertEquals("English Only", movies[2].title)
        assertEquals(2001, movies[2].year)
        assertEquals("https://images.example/700.jpg", movies[2].posterUrl)
        assertEquals("Краткое описание", movies[2].overview)
        assertNull(movies[3].year)
    }

    @Test fun genres_use_official_ids_filter_and_sort_without_positional_renumbering() = runBlocking {
        enqueue("genres.json")
        val genres = repo().genres()
        assertEquals(listOf(7, 6), genres.map { it.id })
        assertEquals(listOf("Драма", "Комедия"), genres.map { it.name })
        assertEquals("/v1.5/dictionary/genres", server.takeRequest().url.encodedPath)
    }

    @Test fun byGenre_without_prior_genres_fetches_dictionary_and_uses_official_id() = runBlocking {
        enqueue("genres.json")
        enqueue("by_genre.json")
        val r = repo()
        assertEquals(listOf(301), r.byGenre(6).map { it.id })
        assertEquals("/v1.5/dictionary/genres", server.takeRequest().url.encodedPath)
        val request = server.takeRequest()
        assertEquals("6", request.url.queryParameter("genres.id"))
        assertEquals("movie", request.url.queryParameter("type"))
        assertEquals("votes.kp", request.url.queryParameter("sortField"))
        assertEquals("-1", request.url.queryParameter("sortType"))
        assertNull(request.url.queryParameter("lists"))
        assertNull(request.url.queryParameter("page"))
    }

    @Test fun byGenre_uses_loaded_dictionary_and_unknown_id_is_empty() = runBlocking {
        enqueue("genres.json")
        enqueue("by_genre.json")
        val r = repo()
        r.genres()
        assertEquals(1, r.byGenre(7).size)
        assertTrue(r.byGenre(123).isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test fun search_sends_page_query_filters_series_and_handles_empty() = runBlocking {
        enqueue("search.json")
        enqueue("search_empty.json")
        val r = repo()
        assertEquals(listOf(301), r.search("матрица").map { it.id })
        val request = server.takeRequest()
        assertEquals("/v1.5/movie/search", request.url.encodedPath)
        assertEquals("матрица", request.url.queryParameter("query"))
        assertEquals("1", request.url.queryParameter("page"))
        assertTrue(r.search("zzz").isEmpty())
    }

    @Test fun details_maps_imdb_runtime_backdrop_and_genres() = runBlocking {
        enqueue("details.json")
        val details = repo().details(301)
        assertEquals("tt0133093", details.imdbId)
        assertEquals(136, details.runtimeMin)
        assertEquals("https://images.example/matrix-backdrop.jpg", details.backdropUrl)
        assertEquals(listOf("Фантастика", "Боевик"), details.genres)
        assertEquals("Полное описание", details.movie.overview)
        assertEquals("/v1.5/movie/301", server.takeRequest().url.encodedPath)
    }

    @Test fun direct_page_three_loads_preceding_cursors_and_returns_only_third_page() = runBlocking {
        server.enqueue(page(1, "cursor2")); server.enqueue(page(2, "cursor3")); server.enqueue(page(3))
        val r = repo()
        assertEquals(listOf(3), r.popular(3).map { it.id })
        assertNull(server.takeRequest().url.queryParameter("next"))
        assertEquals("cursor2", server.takeRequest().url.queryParameter("next"))
        assertEquals("cursor3", server.takeRequest().url.queryParameter("next"))
        assertEquals(listOf(2), r.popular(2).map { it.id })
        assertTrue(r.popular(4).isEmpty())
        assertTrue(r.popular(5).isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test fun page_one_refresh_resets_cursor_chain() = runBlocking {
        server.enqueue(page(1, "old2")); server.enqueue(page(2))
        server.enqueue(page(10, "new2")); server.enqueue(page(20))
        val r = repo()
        r.popular(2)
        assertEquals(listOf(10), r.popular(1).map { it.id })
        assertEquals(listOf(20), r.popular(2).map { it.id })
        val requests = List(4) { server.takeRequest() }
        assertNull(requests[2].url.queryParameter("next"))
        assertEquals("new2", requests[3].url.queryParameter("next"))
    }

    @Test fun concurrent_same_chain_requests_do_not_duplicate_cursor_walk() = runBlocking {
        server.enqueue(page(1, "next2")); server.enqueue(page(2))
        val r = repo()
        val one = async(Dispatchers.Default) { r.popular(2) }
        val two = async(Dispatchers.Default) { r.popular(2) }
        assertEquals(listOf(2), one.await().map { it.id })
        assertEquals(listOf(2), two.await().map { it.id })
        assertEquals(2, server.requestCount)
    }

    @Test fun key_change_clears_cursor_and_dictionary_memory() = runBlocking {
        enqueue("genres.json"); server.enqueue(page(1, "old2"))
        enqueue("genres.json"); server.enqueue(page(10, "new2")); server.enqueue(page(20))
        val settings = settings()
        val r = repo(settings)
        r.byGenre(6)
        settings.setPoiskkinoApiKey("NEW")
        assertEquals(listOf(20), r.byGenre(6, 2).map { it.id })
        val requests = List(5) { server.takeRequest() }
        assertEquals("KEY", requests[0].headers["X-API-KEY"])
        assertEquals("/v1.5/dictionary/genres", requests[2].url.encodedPath)
        assertTrue(requests.drop(2).all { it.headers["X-API-KEY"] == "NEW" })
        assertNull(requests[3].url.queryParameter("next"))
        assertEquals("new2", requests[4].url.queryParameter("next"))
    }

    @Test fun invalid_cursor_contract_does_not_repeat_first_page() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"docs":[],"next":null,"hasNext":true}""").build())
        assertEquals(CatalogException.Parse, failure { repo().popular(2) })
        assertEquals(1, server.requestCount)
    }

    @Test fun missing_key_or_unsafe_header_makes_no_request() = runBlocking {
        assertEquals(CatalogException.MissingApiKey, failure { repo(settings(null)).popular() })
        assertEquals(CatalogException.Unauthorized, failure { repo(settings("ключ")).popular() })
        assertEquals(0, server.requestCount)
    }

    @Test fun http_errors_are_mapped() = runBlocking {
        for ((code, error) in listOf(401 to CatalogException.Unauthorized,
            403 to CatalogException.QuotaExceeded, 500 to CatalogException.Http(500))) {
            server.enqueue(MockResponse.Builder().code(code).body("{}").build())
            assertEquals(error, failure { repo().popular() })
        }
    }

    @Test fun malformed_json_is_parse_error() = runBlocking {
        server.enqueue(MockResponse.Builder().body("<html>bad response</html>").build())
        assertEquals(CatalogException.Parse, failure { repo().popular() })
    }

    @Test fun io_failure_is_network_error() = runBlocking {
        repeat(2) { server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
        assertEquals(CatalogException.Network, failure { repo().popular() })
    }

    @Test fun cancellation_before_headers_cancels_call_and_releases_chain_mutex() = cancellation(bodyDelay = false)
    @Test fun cancellation_during_body_cancels_call_and_releases_chain_mutex() = cancellation(bodyDelay = true)

    private fun cancellation(bodyDelay: Boolean) = runBlocking {
        val canceled = CountDownLatch(1)
        val bodyStarted = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
            override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
        }).build()
        val response = MockResponse.Builder().body("""{"docs":[],"hasNext":false}""")
        server.enqueue(if (bodyDelay) response.throttleBody(1, 10, TimeUnit.SECONDS).build()
            else response.headersDelay(10, TimeUnit.SECONDS).build())
        server.enqueue(page(2))
        val r = repo(client = client)
        val job = async(Dispatchers.Default) { r.popular() }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        if (bodyDelay) assertTrue(bodyStarted.await(3, TimeUnit.SECONDS))
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(canceled.await(3, TimeUnit.SECONDS))
        assertEquals(listOf(2), r.popular().map { it.id })
    }

    @Test fun key_change_during_request_rejects_old_result_and_keeps_new_chain_separate() = runBlocking {
        server.enqueue(MockResponse.Builder().headersDelay(100, TimeUnit.MILLISECONDS)
            .body("""{"docs":[{"id":1}],"next":"old2","hasNext":true}""").build())
        server.enqueue(page(10, "new2")); server.enqueue(page(20))
        val settings = settings()
        val r = repo(settings)
        val old = async(Dispatchers.Default) { r.popular() }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        settings.setPoiskkinoApiKey("NEW")
        try { old.await(); fail("Old credential result must not be returned") }
        catch (_: CancellationException) { }
        assertEquals(listOf(20), r.popular(2).map { it.id })
        assertNull(server.takeRequest().url.queryParameter("next"))
        assertEquals("new2", server.takeRequest().url.queryParameter("next"))
    }
}
