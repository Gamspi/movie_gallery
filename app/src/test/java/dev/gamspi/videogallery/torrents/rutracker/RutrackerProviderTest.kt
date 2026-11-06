package dev.gamspi.videogallery.torrents.rutracker

import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.torrents.ProviderUnavailableException
import dev.gamspi.videogallery.torrents.UnavailableReason
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RutrackerProviderTest {
    private lateinit var server: MockWebServer
    private var creds: RutrackerCredentials? = RutrackerCredentials("fake_user", "fake_pass")

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun provider(): RutrackerProvider {
        val client = OkHttpClient()
        val base = { server.url("/").toString() }
        return RutrackerProvider(client, base, RutrackerSession(client, base) { creds })
    }

    private fun fixture(name: String) = javaClass.getResource("/torrents/$name")!!.readText()

    private fun page(html: String, charsetHeader: Boolean = false) {
        val builder = MockResponse.Builder().code(200).body(Buffer().write(html.toByteArray(CP1251)))
        if (charsetHeader) builder.addHeader("Content-Type", "text/html; charset=windows-1251")
        server.enqueue(builder.build())
    }

    private fun loginOk(session: String = "0-100500-FakeSession") {
        server.enqueue(
            MockResponse.Builder().code(302)
                .addHeader("Location", "/forum/index.php")
                .addHeader("Set-Cookie", "bb_session=$session; expires=Fri, 01-Jan-2038 00:00:00 GMT; path=/forum/; HttpOnly")
                .build(),
        )
    }

    private fun movie(title: String = "Матрица", original: String = "The Matrix") =
        MovieDetails(Movie(1, title, original, 1999, null, ""), null, null, emptyList(), null)

    private fun reason(block: suspend () -> Unit): UnavailableReason? {
        val e = runCatching { runBlocking { block() } }.exceptionOrNull()
        return (e as? ProviderUnavailableException)?.reason
    }

    @Test
    fun search_logsInWithoutFollowingRedirect_thenSearchesWithCookie() = runBlocking {
        loginOk()
        page(fixture("rutracker_search.html"), charsetHeader = true)
        val results = provider().search(movie())
        assertEquals(
            listOf("1000001", "1000002", "1000006"),
            results.map { it.topicUrl!!.substringAfter("t=") },
        )
        assertEquals(2, server.requestCount)
        val login = server.takeRequest()
        assertEquals("POST", login.method)
        assertEquals("/forum/login.php", login.url.encodedPath)
        val form = login.body!!.utf8()
        assertTrue(form, form.contains("login_username=fake_user"))
        assertTrue(form, form.contains("login_password=fake_pass"))
        assertTrue(form, form.contains("login=%E2%F5%EE%E4"))
        val search = server.takeRequest()
        assertEquals("/forum/tracker.php", search.url.encodedPath)
        assertEquals("%CC%E0%F2%F0%E8%F6%E0+1999", search.url.encodedQuery!!.substringAfter("nm="))
        assertEquals("bb_session=0-100500-FakeSession", search.headers["Cookie"])
    }

    @Test
    fun session_reusedAcrossSearches() = runBlocking {
        loginOk()
        page(fixture("rutracker_search.html"))
        page(fixture("rutracker_search.html"))
        val p = provider()
        p.search(movie())
        p.search(movie())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun searchReturnsLoginForm_reloginOnce() = runBlocking {
        loginOk("old")
        page(fixture("rutracker_login.html"))
        loginOk("new")
        page(fixture("rutracker_search.html"))
        val results = provider().search(movie())
        assertEquals(3, results.size)
        assertEquals(4, server.requestCount)
        server.takeRequest()
        assertEquals("bb_session=old", server.takeRequest().headers["Cookie"])
        assertEquals("/forum/login.php", server.takeRequest().url.encodedPath)
        assertEquals("bb_session=new", server.takeRequest().headers["Cookie"])
    }

    @Test
    fun searchStillLoginFormAfterRelogin_unavailable() {
        loginOk("a")
        page(fixture("rutracker_login.html"))
        loginOk("b")
        page(fixture("rutracker_login.html"))
        assertEquals(UnavailableReason.Unavailable, reason { provider().search(movie(original = "")) })
        assertEquals(4, server.requestCount)
    }

    @Test
    fun captcha_reported() {
        page(fixture("rutracker_login_captcha.html"))
        assertEquals(UnavailableReason.Captcha, reason { provider().search(movie()) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun badCredentials_reported() {
        page(fixture("rutracker_login.html"))
        assertEquals(UnavailableReason.BadCredentials, reason { provider().search(movie()) })
    }

    @Test
    fun noCredentials_reportedWithoutNetwork() {
        creds = null
        assertEquals(UnavailableReason.NoCredentials, reason { provider().search(movie()) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun loginServerError_unavailable() {
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(UnavailableReason.Unavailable, reason { provider().search(movie()) })
    }

    @Test
    fun searchHttpError_unavailable() {
        loginOk()
        server.enqueue(MockResponse.Builder().code(502).build())
        assertEquals(UnavailableReason.Unavailable, reason { provider().search(movie(original = "")) })
    }

    @Test
    fun credentialsChanged_logsInAgain() = runBlocking {
        loginOk("first")
        page(fixture("rutracker_search.html"))
        loginOk("second")
        page(fixture("rutracker_search.html"))
        val p = provider()
        p.search(movie())
        creds = RutrackerCredentials("other_user", "fake_pass")
        p.search(movie())
        assertEquals(4, server.requestCount)
        repeat(3) { server.takeRequest() }
        assertEquals("bb_session=second", server.takeRequest().headers["Cookie"])
    }

    @Test
    fun emptyRussian_fallsBackToOriginalTitle() = runBlocking {
        loginOk()
        page("<html><body><a id=\"logged-in-username\">u</a></body></html>")
        page(fixture("rutracker_search.html"))
        val results = provider().search(movie())
        assertEquals(3, results.size)
        server.takeRequest()
        server.takeRequest()
        assertEquals("The+Matrix+1999", server.takeRequest().url.encodedQuery!!.substringAfter("nm="))
    }

    @Test
    fun resolve_takesMagnetFromTopicPage() = runBlocking {
        loginOk()
        page(fixture("rutracker_search.html"))
        page(fixture("rutracker_topic.html"))
        val p = provider()
        val found = p.search(movie()).first()
        val resolved = p.resolve(found)
        assertEquals("0123456789abcdef0123456789abcdef01234567", resolved.infoHash)
        assertTrue(resolved.magnet.startsWith("magnet:?xt=urn:btih:"))
        assertEquals(found.title, resolved.title)
        assertEquals(found.topicUrl, resolved.topicUrl)
        repeat(2) { server.takeRequest() }
        val topic = server.takeRequest()
        assertEquals("/forum/viewtopic.php", topic.url.encodedPath)
        assertEquals("t=1000001", topic.url.encodedQuery)
        assertEquals("bb_session=0-100500-FakeSession", topic.headers["Cookie"])
    }

    @Test
    fun resolve_noMagnet_unavailable() = runBlocking {
        loginOk()
        page(fixture("rutracker_search.html"))
        page("<html><body><a id=\"logged-in-username\">u</a>Тема удалена</body></html>")
        val p = provider()
        val found = p.search(movie()).first()
        assertEquals(UnavailableReason.Unavailable, reason { p.resolve(found) })
    }
}
