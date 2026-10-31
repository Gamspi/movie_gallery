package dev.gamspi.videogallery.subs

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        val result = client().search("abc", "tt0063350", listOf("en", "ru"))
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
        assertEquals("abc", req.url.queryParameter("moviehash"))
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
        assertEquals(SubsResult.BadKey, client().search("abc", null, listOf("en")))
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
    fun download_uses_login_token_when_credentials_set() = runBlocking {
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
