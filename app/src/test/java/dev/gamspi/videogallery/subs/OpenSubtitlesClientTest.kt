package dev.gamspi.videogallery.subs

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenSubtitlesClientTest {
    private class FakeSettings(
        override val openSubtitlesKey: String? = "KEY",
        override val openSubtitlesUser: String? = null,
        override val openSubtitlesPassword: String? = null,
    ) : SubtitleSettingsSource

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun client(settings: SubtitleSettingsSource = FakeSettings()) =
        OpenSubtitlesClient(server.url("/api/v1/").toString(), settings, OkHttpClient())

    private fun fixture(name: String) = javaClass.getResource("/opensubtitles/$name")!!.readText()

    private fun downloadJson() = fixture("download.json").replace("LINK_PLACEHOLDER", server.url("/file.srt").toString())

    @Test
    fun search_sends_headers_and_sorts() = runBlocking {
        server.enqueue(MockResponse.Builder().body(fixture("search.json")).build())
        val result = client().search(null, "tt0063350", listOf("ru", "en", "en"))
        val list = (result as SubsResult.Ok).value
        assertEquals(listOf(102L, 101L, 103L), list.map { it.fileId })
        assertTrue(list[0].hashMatch)
        assertEquals("ru", list[0].language)
        assertEquals("Hash.Match.Release", list[0].release)
        val req = server.takeRequest()
        assertEquals("KEY", req.headers["Api-Key"])
        assertEquals("VideoGallery v1.0", req.headers["User-Agent"])
        assertEquals("/api/v1/subtitles", req.url.encodedPath)
        assertEquals("63350", req.url.queryParameter("imdb_id"))
        assertNull(req.url.queryParameter("moviehash"))
        assertNull(req.url.queryParameter("moviehash_match"))
        assertEquals("en,ru", req.url.queryParameter("languages"))
    }

    @Test
    fun search_without_ids_makes_no_request() = runBlocking {
        val result = client().search(null, null, listOf("en"))
        assertEquals(SubsResult.Ok(emptyList<RemoteSubtitle>()), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun search_without_key_is_NoKey() = runBlocking {
        val result = client(FakeSettings(openSubtitlesKey = null)).search("abc", null, listOf("en"))
        assertEquals(SubsResult.NoKey, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun search_401_is_BadKey() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(SubsResult.BadKey, client().search("abc", "tt1", listOf("en")))
        assertEquals(1, server.requestCount)
    }

    private fun searchJson(vararg entries: Triple<Long, String, Boolean>) =
        """{"data":[${entries.joinToString(",") { (id, lang, match) ->
            """{"attributes":{"language":"$lang","moviehash_match":$match,"download_count":$id,"files":[{"file_id":$id}]}}"""
        }}]}"""

    @Test fun search_hash_missing_languages_fall_back_to_imdb_only() = runBlocking {
        server.enqueue(MockResponse.Builder().body(searchJson(Triple(1, "en", true), Triple(99, "ru", false))).build())
        server.enqueue(MockResponse.Builder().body(searchJson(Triple(2, "ru", false))).build())
        val result = client().search("abc", "tt0063350", listOf("ru", "en")) as SubsResult.Ok
        assertEquals(listOf(1L, 2L), result.value.map { it.fileId })
        val hash = server.takeRequest().url
        assertEquals("abc", hash.queryParameter("moviehash"))
        assertEquals("only", hash.queryParameter("moviehash_match"))
        assertNull(hash.queryParameter("imdb_id"))
        assertEquals("en,ru", hash.queryParameter("languages"))
        val imdb = server.takeRequest().url
        assertEquals("63350", imdb.queryParameter("imdb_id"))
        assertEquals("ru", imdb.queryParameter("languages"))
        assertNull(imdb.queryParameter("moviehash"))
        assertNull(imdb.queryParameter("moviehash_match"))
    }

    @Test fun search_no_hash_match_falls_back_for_all_languages() = runBlocking {
        server.enqueue(MockResponse.Builder().body(searchJson(Triple(9, "en", false))).build())
        server.enqueue(MockResponse.Builder().body(searchJson(Triple(1, "en", false), Triple(2, "ru", false))).build())
        val result = client().search("abc", "tt1", listOf("en", "ru")) as SubsResult.Ok
        assertEquals(listOf(2L, 1L), result.value.map { it.fileId })
        server.takeRequest()
        assertEquals("en,ru", server.takeRequest().url.queryParameter("languages"))
    }

    @Test fun search_no_imdb_returns_only_exact_matches_without_retry() = runBlocking {
        server.enqueue(MockResponse.Builder().body(searchJson(Triple(1, "en", true), Triple(2, "ru", false))).build())
        val result = client().search("abc", null, listOf("en", "ru")) as SubsResult.Ok
        assertEquals(listOf(1L), result.value.map { it.fileId })
        assertEquals(1, server.requestCount)
    }

    @Test fun search_all_languages_exact_skips_imdb_and_deduplicates() = runBlocking {
        server.enqueue(MockResponse.Builder().body(searchJson(Triple(1, "en", true), Triple(1, "en", true), Triple(2, "ru", true))).build())
        val result = client().search("abc", "tt1", listOf("en", "ru")) as SubsResult.Ok
        assertEquals(listOf(2L, 1L), result.value.map { it.fileId })
        assertEquals(1, server.requestCount)
    }

    @Test fun search_merge_deduplicates_ids_preserving_exact_match() = runBlocking {
        server.enqueue(MockResponse.Builder().body(searchJson(Triple(1, "en", true))).build())
        server.enqueue(MockResponse.Builder().body(searchJson(Triple(1, "ru", false), Triple(2, "ru", false))).build())
        val result = client().search("abc", "tt1", listOf("en", "ru")) as SubsResult.Ok
        assertEquals(listOf(1L, 2L), result.value.map { it.fileId })
        assertEquals("en", result.value.first().language)
    }

    @Test fun search_first_request_limit_does_not_retry_imdb() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).build())
        assertEquals(SubsResult.LimitReached, client().search("abc", "tt1", listOf("en", "ru")))
        assertEquals(1, server.requestCount)
    }

    @Test fun search_first_request_server_error_does_not_retry_imdb() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(SubsResult.Failed("HTTP 503"), client().search("abc", "tt1", listOf("en", "ru")))
        assertEquals(1, server.requestCount)
    }

    @Test fun search_first_request_network_error_does_not_retry_imdb() = runBlocking {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor {
            attempts.incrementAndGet()
            throw java.io.IOException("offline")
        }.build()
        val c = OpenSubtitlesClient(server.url("/api/v1/").toString(), FakeSettings(), http)
        assertEquals(SubsResult.Failed("offline"), c.search("abc", "tt1", listOf("en", "ru")))
        assertEquals(1, attempts.get())
    }

    @Test fun search_fallback_error_preserves_usable_exact_matches() = runBlocking {
        server.enqueue(MockResponse.Builder().body(searchJson(Triple(1, "en", true))).build())
        server.enqueue(MockResponse.Builder().code(429).build())
        val result = client().search("abc", "tt1", listOf("en", "ru")) as SubsResult.Ok
        assertEquals(listOf(1L), result.value.map { it.fileId })
        assertEquals(mapOf("ru" to "лимит OpenSubtitles на сегодня"), result.languageNotes)
        assertEquals(2, server.requestCount)
    }

    @Test fun search_fallback_bad_key_preserves_exact_and_marks_missing_language() = runBlocking {
        server.enqueue(MockResponse.Builder().body(searchJson(Triple(1, "en", true))).build())
        server.enqueue(MockResponse.Builder().code(401).build())
        val result = client().search("abc", "tt1", listOf("en", "ru")) as SubsResult.Ok
        assertEquals(listOf(1L), result.value.map { it.fileId })
        assertEquals(mapOf("ru" to "ключ OpenSubtitles неверный"), result.languageNotes)
        assertEquals(2, server.requestCount)
    }

    @Test fun search_fallback_error_with_no_exact_match_returns_error() = runBlocking {
        server.enqueue(MockResponse.Builder().body(searchJson()).build())
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(SubsResult.BadKey, client().search("abc", "tt1", listOf("en", "ru")))
        assertEquals(2, server.requestCount)
    }

    @Test fun search_cancel_during_fallback_cancels_actual_call() = runBlocking {
        server.enqueue(MockResponse.Builder().body(searchJson(Triple(1, "en", true))).build())
        server.enqueue(MockResponse.Builder().headersDelay(10, TimeUnit.SECONDS).body(searchJson(Triple(2, "ru", false))).build())
        val canceled = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
        }).build()
        val c = OpenSubtitlesClient(server.url("/api/v1/").toString(), FakeSettings(), http)
        val operation = async(Dispatchers.Default) { c.search("abc", "tt1", listOf("en", "ru")) }
        try {
            assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null)
            val fallback = server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("ru", fallback!!.url.queryParameter("languages"))
            withTimeout(2_000) { operation.cancelAndJoin() }
            assertTrue(canceled.await(1, TimeUnit.SECONDS))
            assertTrue(operation.isCancelled)
        } finally {
            operation.cancelAndJoin()
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun download_limit_maps_to_LimitReached() = runBlocking {
        server.enqueue(MockResponse.Builder().code(406).build())
        assertEquals(SubsResult.LimitReached, client().download(5))
    }

    @Test
    fun download_fetches_link_bytes() = runBlocking {
        server.enqueue(MockResponse.Builder().body(downloadJson()).build())
        server.enqueue(MockResponse.Builder().body("1\n00:00:01,000 --> 00:00:02,000\nHi\n").build())
        val result = client().download(77)
        assertArrayEquals("1\n00:00:01,000 --> 00:00:02,000\nHi\n".toByteArray(), (result as SubsResult.Ok).value)
        val req = server.takeRequest()
        assertEquals("/api/v1/download", req.url.encodedPath)
        assertTrue(req.body!!.utf8().contains("\"file_id\":77"))
        assertEquals("/file.srt", server.takeRequest().url.encodedPath)
    }

    @Test
    fun download_relogins_once_on_expired_token() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"token":"OLD"}""").build())
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().body("""{"token":"NEW"}""").build())
        server.enqueue(MockResponse.Builder().body(downloadJson()).build())
        server.enqueue(MockResponse.Builder().body("data").build())
        val c = client(FakeSettings(openSubtitlesUser = "u", openSubtitlesPassword = "p"))
        assertArrayEquals("data".toByteArray(), (c.download(1) as SubsResult.Ok).value)
        val reqs = (1..5).map { server.takeRequest() }
        assertEquals(
            listOf("/api/v1/login", "/api/v1/download", "/api/v1/login", "/api/v1/download", "/file.srt"),
            reqs.map { it.url.encodedPath },
        )
        assertEquals("Bearer OLD", reqs[1].headers["Authorization"])
        assertEquals("Bearer NEW", reqs[3].headers["Authorization"])
    }

    @Test
    fun login_401_is_Failed_not_BadKey() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        val result = client(FakeSettings(openSubtitlesUser = "u", openSubtitlesPassword = "bad")).download(1)
        assertTrue(result is SubsResult.Failed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun download_uses_login_token_when_credentials_set()= runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"token":"TOK","status":200}""").build())
        repeat(2) {
            server.enqueue(MockResponse.Builder().body(downloadJson()).build())
            server.enqueue(MockResponse.Builder().body("data").build())
        }
        val c = client(FakeSettings(openSubtitlesUser = "u", openSubtitlesPassword = "p"))
        assertTrue(c.download(1) is SubsResult.Ok)
        assertTrue(c.download(2) is SubsResult.Ok)
        val paths = (1..5).map { server.takeRequest() }
        assertEquals(
            listOf("/api/v1/login", "/api/v1/download", "/file.srt", "/api/v1/download", "/file.srt"),
            paths.map { it.url.encodedPath },
        )
        assertEquals("Bearer TOK", paths[1].headers["Authorization"])
        assertEquals("Bearer TOK", paths[3].headers["Authorization"])
    }
}
