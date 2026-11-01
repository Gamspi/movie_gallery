package dev.gamspi.videogallery.catalog

import dev.gamspi.videogallery.catalog.kinopoisk.KinopoiskCatalogRepository
import dev.gamspi.videogallery.catalog.tmdb.TmdbCatalogRepository
import dev.gamspi.videogallery.settings.FakeSecretStore
import dev.gamspi.videogallery.settings.SettingsRepository
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpCacheTest {
    private lateinit var dir: File
    private lateinit var server: MockWebServer
    private lateinit var base: OkHttpClient

    @Before fun setUp() {
        dir = File.createTempFile("catalog-cache", "").also { it.delete(); it.mkdirs() }
        server = MockWebServer().apply { start() }
        base = cachingClient(dir)
    }

    @After fun tearDown() {
        base.cache?.close()
        base.connectionPool.evictAll()
        base.dispatcher.executorService.shutdownNow()
        server.close()
        dir.deleteRecursively()
    }

    private fun request(client: OkHttpClient = base, path: String, key: String? = null) = client.newCall(
        Request.Builder().url(server.url(path)).apply { if (key != null) header("X-API-KEY", key) }.build(),
    ).execute()

    @Test fun lists_search_and_genres_from_all_sources_cache_for_hour() {
        val paths = listOf(
            "/3/movie/popular", "/3/search/movie?query=matrix", "/3/genre/movie/list",
            "/api/v2.2/films/collections", "/api/v2.2/films?keyword=matrix", "/api/v2.2/films/filters",
            "/v1.5/movie", "/v1.5/movie/search?query=matrix", "/v1.5/dictionary/genres",
        )
        for (path in paths) {
            server.enqueue(MockResponse.Builder().body(path).addHeader("Cache-Control", "no-cache").addHeader("Pragma", "no-cache").build())
            request(path = path).use {
                assertEquals("public, max-age=3600", it.header("Cache-Control"))
                assertNull(it.header("Pragma"))
                assertEquals(path, it.body.string())
            }
            request(path = path).use {
                assertTrue("not cached: $path", it.cacheResponse != null)
                assertEquals(path, it.body.string())
            }
        }
        assertEquals(paths.size, server.requestCount)
        assertEquals(File(dir, "http"), base.cache!!.directory)
        assertEquals(20L * 1024 * 1024, base.cache!!.maxSize())
    }

    @Test fun details_all_api_versions_cached_for_day() {
        val paths = listOf("/3/movie/301", "/api/v2.2/films/301", "/v1.4/movie/301", "/v1.5/movie/301")
        for (path in paths) {
            server.enqueue(MockResponse.Builder().body("movie").build())
            request(path = path).use { assertEquals("public, max-age=86400", it.header("Cache-Control")); it.body.string() }
            request(path = path).use { assertTrue(it.cacheResponse != null); it.body.string() }
        }
        assertEquals(paths.size, server.requestCount)
    }

    @Test fun list_expires_after_hour_but_two_hour_old_details_remain_fresh() {
        val oldDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).minusHours(2))
        server.enqueue(MockResponse.Builder().addHeader("Date", oldDate).body("old list").build())
        server.enqueue(MockResponse.Builder().body("new list").build())
        server.enqueue(MockResponse.Builder().addHeader("Date", oldDate).body("old details").build())
        request(path = "/v1.5/movie").use { assertEquals("old list", it.body.string()) }
        request(path = "/v1.5/movie").use {
            assertEquals("new list", it.body.string())
            assertTrue(it.networkResponse != null)
        }
        request(path = "/v1.5/movie/301").use { assertEquals("old details", it.body.string()) }
        request(path = "/v1.5/movie/301").use {
            assertEquals("old details", it.body.string())
            assertNull(it.networkResponse)
            assertTrue(it.cacheResponse != null)
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun auth_and_server_errors_never_cache_even_with_server_max_age() {
        for (code in listOf(401, 403, 500, 503)) {
            val path = "/v1.5/movie?case=$code"
            server.enqueue(MockResponse.Builder().code(code).addHeader("Cache-Control", "public, max-age=86400").body("error").build())
            server.enqueue(MockResponse.Builder().body("success").build())
            request(path = path).use {
                assertEquals(code, it.code)
                assertEquals("no-store", it.header("Cache-Control"))
                it.body.string()
            }
            request(path = path).use { assertEquals("success", it.body.string()); assertNull(it.cacheResponse) }
        }
        assertEquals(8, server.requestCount)
    }

    @Test fun changed_header_key_does_not_reuse_other_credential_cache_or_cache_bad_key_error() {
        val client = base.withCatalogCredentials("poiskkino")
        server.enqueue(MockResponse.Builder().body("key A result").addHeader("Vary", "X-API-KEY").build())
        server.enqueue(MockResponse.Builder().code(401).body("bad key").addHeader("Cache-Control", "max-age=86400").build())
        server.enqueue(MockResponse.Builder().body("key B result").build())
        repeat(2) { request(client, "/v1.5/movie", "SECRET_A").use { assertEquals("key A result", it.body.string()) } }
        request(client, "/v1.5/movie", "SECRET_B").use { assertEquals(401, it.code); it.body.string() }
        repeat(2) { request(client, "/v1.5/movie", "SECRET_B").use { assertEquals("key B result", it.body.string()) } }
        assertEquals(3, server.requestCount)
        assertEquals("SECRET_A", server.takeRequest().headers["X-API-KEY"])
        assertEquals("SECRET_B", server.takeRequest().headers["X-API-KEY"])
        assertEquals("SECRET_B", server.takeRequest().headers["X-API-KEY"])
        // OkHttp stores one variant per URL: switching back may miss, but must never return B.
        server.enqueue(MockResponse.Builder().body("key A result").build())
        request(client, "/v1.5/movie", "SECRET_A").use { assertEquals("key A result", it.body.string()) }
        assertEquals("SECRET_A", server.takeRequest().headers["X-API-KEY"])
        assertEquals(4, server.requestCount)
    }

    @Test fun source_and_base_url_are_isolated_even_with_same_credential() {
        server.enqueue(MockResponse.Builder().body("source A").build())
        server.enqueue(MockResponse.Builder().body("source B").build())
        server.enqueue(MockResponse.Builder().body("different base path").build())
        val a = base.withCatalogCredentials("a")
        val b = base.withCatalogCredentials("b")
        request(a, "/same/movie", "KEY").use { assertEquals("source A", it.body.string()) }
        request(b, "/same/movie", "KEY").use { assertEquals("source B", it.body.string()) }
        request(b, "/other/movie", "KEY").use { assertEquals("different base path", it.body.string()) }
        assertEquals(3, server.requestCount)
        assertTrue(a.cache === b.cache && a.cache === base.cache)
    }

    @Test fun changed_query_key_does_not_reuse_another_credentials_cached_response() {
        val client = base.withCatalogCredentials("tmdb")
        server.enqueue(MockResponse.Builder().body("key A result").build())
        server.enqueue(MockResponse.Builder().body("key B result").build())
        for ((key, expected) in listOf("QUERY_A" to "key A result", "QUERY_B" to "key B result")) {
            repeat(2) {
                request(client, "/3/movie/301?api_key=$key&language=ru-RU").use {
                    assertEquals(expected, it.body.string())
                    assertNull(it.request.url.queryParameter("api_key"))
                }
            }
        }
        assertEquals(2, server.requestCount)
        assertEquals("QUERY_A", server.takeRequest().url.queryParameter("api_key"))
        assertEquals("QUERY_B", server.takeRequest().url.queryParameter("api_key"))
        server.enqueue(MockResponse.Builder().body("key A refreshed").build())
        request(client, "/3/movie/301?api_key=QUERY_A&language=ru-RU").use {
            assertEquals("key A refreshed", it.body.string())
        }
        assertEquals(3, server.requestCount)
        assertEquals("QUERY_A", server.takeRequest().url.queryParameter("api_key"))
    }

    @Test fun cache_disk_metadata_and_urls_never_contain_raw_credentials() {
        val client = base.withCatalogCredentials("tmdb")
        server.enqueue(MockResponse.Builder().body("header result").addHeader("Vary", "X-API-KEY").build())
        server.enqueue(MockResponse.Builder().body("query result").build())
        repeat(2) {
            request(client, "/header", "HEADER_SECRET_SENTINEL_49").use {
                assertEquals("header result", it.body.string())
                assertNull(it.request.header("X-API-KEY"))
            }
            request(client, "/3/movie/301?api_key=QUERY_SECRET_SENTINEL_87&language=ru-RU").use {
                assertEquals("query result", it.body.string())
                assertNull(it.request.url.queryParameter("api_key"))
            }
        }
        assertEquals(2, server.requestCount)
        assertEquals("HEADER_SECRET_SENTINEL_49", server.takeRequest().headers["X-API-KEY"])
        assertEquals("QUERY_SECRET_SENTINEL_87", server.takeRequest().url.queryParameter("api_key"))
        base.cache!!.flush()
        base.cache!!.close()
        val disk = File(dir, "http").walkTopDown().filter { it.isFile }
            .joinToString("\n") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(disk.contains("HEADER_SECRET_SENTINEL_49"))
        assertFalse(disk.contains("QUERY_SECRET_SENTINEL_87"))
        assertTrue(disk.contains("X-VideoGallery-Catalog-Identity"))
    }

    @Test fun redirect_to_other_origin_is_not_followed_or_cached() {
        MockWebServer().use { other ->
            other.start()
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/target")).build())
            request(base.withCatalogCredentials("poiskkino"), "/redirect", "PRIVATE_KEY").use {
                assertEquals(302, it.code)
                assertEquals("no-store", it.header("Cache-Control"))
                it.body.string()
            }
            assertEquals("PRIVATE_KEY", server.takeRequest().headers["X-API-KEY"])
            assertEquals(0, other.requestCount)
        }
    }

    @Test fun redirect_echoing_query_key_cannot_reintroduce_plaintext_cache_url() {
        val client = base.withCatalogCredentials("tmdb")
        val secret = "REDIRECT_SECRET_SENTINEL_927"
        server.enqueue(MockResponse.Builder().code(302)
            .addHeader("Location", server.url("/3/movie/301?api_key=$secret"))
            .addHeader("Cache-Control", "public, max-age=86400").build())
        request(client, "/3/movie/301?api_key=$secret").use {
            assertEquals(302, it.code)
            assertNull(it.request.url.queryParameter("api_key"))
            it.body.string()
        }
        assertEquals(1, server.requestCount)
        base.cache!!.flush()
        val disk = File(dir, "http").walkTopDown().filter { it.isFile }
            .joinToString("\n") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(disk.contains(secret))
    }

    @Test fun repositories_share_cache_and_read_changed_credentials_per_request() = runBlocking {
        val settings = SettingsRepository(FakeSecretStore())
        settings.setKinopoiskApiKey("KP_A")
        settings.setTmdbApiKey("TMDB_A")
        val kp = KinopoiskCatalogRepository.create(server.url("/").toString(), settings, base)
        val tmdb = TmdbCatalogRepository.create(server.url("/3/").toString(), settings, base)
        server.enqueue(MockResponse.Builder().body("""{"items":[]}""").build())
        server.enqueue(MockResponse.Builder().body("""{"results":[]}""").build())
        repeat(2) { kp.popular(); tmdb.popular() }
        assertEquals(2, server.requestCount)
        assertEquals("KP_A", server.takeRequest().headers["X-API-KEY"])
        assertEquals("TMDB_A", server.takeRequest().url.queryParameter("api_key"))
        settings.setKinopoiskApiKey("KP_B")
        settings.setTmdbApiKey("TMDB_B")
        server.enqueue(MockResponse.Builder().body("""{"items":[]}""").build())
        server.enqueue(MockResponse.Builder().body("""{"results":[]}""").build())
        kp.popular(); tmdb.popular()
        assertEquals(4, server.requestCount)
        assertEquals("KP_B", server.takeRequest().headers["X-API-KEY"])
        assertEquals("TMDB_B", server.takeRequest().url.queryParameter("api_key"))
    }
}
