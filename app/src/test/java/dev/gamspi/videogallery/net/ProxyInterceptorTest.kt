package dev.gamspi.videogallery.net

import java.net.ProtocolException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ProxyInterceptorTest {
    private lateinit var server: MockWebServer
    private var config: ProxyConfig? = ProxyConfig("http://worker.test/", "secret")

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() = server.close()

    // Every request (worker or direct) reaches MockWebServer through its HTTP proxy,
    // so the absolute target shows where it would have gone.
    private fun client(follow: Boolean = true): OkHttpClient = OkHttpClient.Builder()
        .proxy(server.proxyAddress)
        .followRedirects(follow)
        .proxiedBy(ProxyInterceptor { config })
        .build()

    private fun get(url: String, follow: Boolean = true) =
        client(follow).newCall(Request.Builder().url(url).build()).execute()

    private fun ok(body: String = "ok") = MockResponse.Builder().body(body).build()

    private fun redirect(code: Int, location: String) =
        MockResponse.Builder().code(code).addHeader("Location", location).build()

    @Test
    fun proxiedHostsAndSubdomains() {
        for (host in listOf("api.themoviedb.org", "image.tmdb.org", "rutor.info", "d.rutor.info", "rutracker.org", "x.rutracker.org")) {
            assertTrue(host, ProxyInterceptor.isProxiedHost(host))
        }
        for (host in listOf("themoviedb.org", "notrutor.info", "rutor.info.evil.com", "api.opensubtitles.com",
            "kinopoiskapiunofficial.tech", "api.poiskkino.dev", "apibay.org", "yts.lt", "tmdb.org")) {
            assertFalse(host, ProxyInterceptor.isProxiedHost(host))
        }
    }

    @Test
    fun rewritePreservesEncodedPathAndQuery() {
        val url = "https://rutor.info/search/0/0/000/0/%D0%9C%D0%B0%D1%82%D1%80%D0%B8%D1%86%D0%B0%201999?a=%2B1+2&b".toHttpUrl()
        assertEquals(
            "https://w.workers.dev/rutor.info/search/0/0/000/0/%D0%9C%D0%B0%D1%82%D1%80%D0%B8%D1%86%D0%B0%201999?a=%2B1+2&b",
            ProxyInterceptor.proxiedUrl(url, "https://w.workers.dev//")?.toString(),
        )
        assertEquals(
            "https://w.workers.dev/image.tmdb.org/t/p/w342/x.jpg",
            ProxyInterceptor.proxiedUrl("http://image.tmdb.org/t/p/w342/x.jpg".toHttpUrl(), "https://w.workers.dev")?.toString(),
        )
    }

    @Test
    fun nonDefaultPortOrOtherHostIsNotRewritten() {
        assertNull(ProxyInterceptor.proxiedUrl("https://rutor.info:8443/x".toHttpUrl(), "https://w.dev"))
        assertNull(ProxyInterceptor.proxiedUrl("https://apibay.org/q.php?q=x".toHttpUrl(), "https://w.dev"))
    }

    @Test
    fun configToStringHidesKey() {
        assertFalse(ProxyConfig("https://w.dev", "secret").toString().contains("secret"))
    }

    @Test
    fun proxiedRequestGoesToWorkerWithKey() {
        server.enqueue(ok("body"))
        get("http://api.themoviedb.org/3/movie/1?api_key=k&q=%D0%B0+b").use { resp ->
            assertEquals("body", resp.body.string())
            // Callers see the upstream request, never the worker URL or the key.
            assertEquals("http://api.themoviedb.org/3/movie/1?api_key=k&q=%D0%B0+b", resp.request.url.toString())
            assertNull(resp.request.header("X-VG-Key"))
        }
        val rec = server.takeRequest()
        assertEquals("http://worker.test/api.themoviedb.org/3/movie/1?api_key=k&q=%D0%B0+b", rec.target)
        assertEquals("secret", rec.headers["X-VG-Key"])
    }

    @Test
    fun otherHostPassesThrough() {
        server.enqueue(ok())
        get("http://apibay.org/q.php?q=x").close()
        val rec = server.takeRequest()
        assertEquals("http://apibay.org/q.php?q=x", rec.target)
        assertNull(rec.headers["X-VG-Key"])
    }

    @Test
    fun disabledPassesThrough() {
        config = null
        server.enqueue(ok())
        get("http://rutor.info/x").close()
        val rec = server.takeRequest()
        assertEquals("http://rutor.info/x", rec.target)
        assertNull(rec.headers["X-VG-Key"])
    }

    @Test
    fun blankKeyOmitsHeader() {
        config = ProxyConfig("http://worker.test", null)
        server.enqueue(ok())
        get("http://rutor.info/x").close()
        assertNull(server.takeRequest().headers["X-VG-Key"])
    }

    @Test
    fun absoluteRedirectToOriginalHostIsReproxied() {
        server.enqueue(redirect(302, "https://rutor.info/download/5?x=%20y"))
        server.enqueue(ok("torrent"))
        get("http://rutor.info/torrent/5").use { resp ->
            assertEquals("torrent", resp.body.string())
            assertEquals("https://rutor.info/download/5?x=%20y", resp.request.url.toString())
        }
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("http://worker.test/rutor.info/download/5?x=%20y", second.target)
        assertEquals("secret", second.headers["X-VG-Key"])
    }

    @Test
    fun relativeRedirectResolvesAgainstUpstream() {
        server.enqueue(redirect(301, "/torrent/7"))
        server.enqueue(ok())
        get("http://d.rutor.info/a/b").close()
        server.takeRequest()
        assertEquals("http://worker.test/d.rutor.info/torrent/7", server.takeRequest().target)
    }

    @Test
    fun redirectToOtherHostGoesDirect() {
        server.enqueue(redirect(302, "http://cdn.example/f.torrent"))
        server.enqueue(ok())
        get("http://rutor.info/x").close()
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("http://cdn.example/f.torrent", second.target)
        assertNull(second.headers["X-VG-Key"])
    }

    @Test
    fun noFollowClientSeesAbsoluteUpstreamLocation() {
        server.enqueue(redirect(302, "/torrent/7"))
        get("http://rutor.info/x", follow = false).use { resp ->
            assertEquals(302, resp.code)
            assertEquals("http://rutor.info/torrent/7", resp.header("Location"))
            assertNull(resp.header("X-VG-Upstream-Location"))
            assertEquals("http://rutor.info/x", resp.request.url.toString())
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun postRedirectMethodRules() {
        val body = "a=1".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        server.enqueue(redirect(303, "/after"))
        server.enqueue(ok())
        client().newCall(Request.Builder().url("http://rutracker.org/login").post(body).build()).execute().close()
        assertEquals("POST", server.takeRequest().method)
        val afterSeeOther = server.takeRequest()
        assertEquals("GET", afterSeeOther.method)
        assertEquals("http://worker.test/rutracker.org/after", afterSeeOther.target)

        server.enqueue(redirect(307, "/again"))
        server.enqueue(ok())
        client().newCall(Request.Builder().url("http://rutracker.org/login").post(body).build()).execute().close()
        server.takeRequest()
        val kept = server.takeRequest()
        assertEquals("POST", kept.method)
        assertEquals("a=1", kept.body?.utf8())
        assertEquals("http://worker.test/rutracker.org/again", kept.target)
    }

    @Test
    fun tooManyRedirectsFails() {
        repeat(12) { server.enqueue(redirect(302, "/loop")) }
        try {
            get("http://rutor.info/loop").close()
            fail("expected ProtocolException")
        } catch (e: ProtocolException) {
            assertFalse(e.message.orEmpty().contains("secret"))
        }
    }

    @Test
    fun toggleAppliesPerRequest() {
        val c = client()
        server.enqueue(ok())
        server.enqueue(ok())
        c.newCall(Request.Builder().url("http://rutor.info/a").build()).execute().close()
        config = null
        c.newCall(Request.Builder().url("http://rutor.info/a").build()).execute().close()
        assertEquals("http://worker.test/rutor.info/a", server.takeRequest().target)
        assertEquals("http://rutor.info/a", server.takeRequest().target)
    }
}
