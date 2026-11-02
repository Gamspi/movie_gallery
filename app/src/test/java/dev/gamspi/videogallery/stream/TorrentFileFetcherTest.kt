package dev.gamspi.videogallery.stream

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TorrentFileFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.close()
        other.close()
    }

    private fun fetcher(
        timeoutMs: Long = 5_000L,
        maxBytes: Long = 1024L,
        maxRedirects: Int = 5,
    ) = TorrentFileFetcher(OkHttpClient(), timeoutMs, maxBytes, maxRedirects)

    private fun redirect(location: String) = MockResponse.Builder()
        .code(302).addHeader("Location", location).build()

    private val body = "d8:announce3:abce"

    @Test
    fun returnsBodyOn200() = runBlocking {
        server.enqueue(MockResponse.Builder().body(body).build())
        assertArrayEquals(body.toByteArray(), fetcher().fetch(server.url("/a.torrent").toString()))
    }

    @Test
    fun nullOnHttpError() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("no").build())
        assertNull(fetcher().fetch(server.url("/a").toString()))
    }

    @Test
    fun nullWhenContentLengthOverLimit() = runBlocking {
        server.enqueue(MockResponse.Builder().body("12345678901")
            .bodyDelay(1, TimeUnit.SECONDS).build())
        val start = System.nanoTime()
        assertNull(fetcher(maxBytes = 10).fetch(server.url("/a").toString()))
        assertTrue(System.nanoTime() - start < 700_000_000L)
    }

    @Test
    fun nullWhenChunkedBodyOverLimit() = runBlocking {
        server.enqueue(MockResponse.Builder().chunkedBody("12345678901", 4).build())
        assertNull(fetcher(maxBytes = 10).fetch(server.url("/a").toString()))
    }

    @Test
    fun followsCrossHostRedirect() = runBlocking {
        other.enqueue(MockResponse.Builder().body("from-b").build())
        server.enqueue(redirect(other.url("/b").newBuilder().host("127.0.0.1").build().toString()))
        assertArrayEquals("from-b".toByteArray(), fetcher().fetch(server.url("/a").toString()))
    }

    @Test
    fun followsRelativeRedirect() = runBlocking {
        server.enqueue(redirect("nested/first"))
        server.enqueue(redirect("../next"))
        server.enqueue(MockResponse.Builder().body("rel").build())
        assertArrayEquals("rel".toByteArray(), fetcher().fetch(server.url("/dir/a").toString()))
        server.takeRequest()
        assertEquals("/dir/nested/first", server.takeRequest().url.encodedPath)
        assertEquals("/dir/next", server.takeRequest().url.encodedPath)
    }

    @Test
    fun nullAfterTooManyRedirects() = runBlocking {
        repeat(6) { server.enqueue(redirect("/r")) }
        server.enqueue(MockResponse.Builder().body("late").build())
        assertNull(fetcher(maxRedirects = 5).fetch(server.url("/a").toString()))
        assertEquals(6, server.requestCount)
    }

    @Test
    fun fiveRedirectsAreAllowed() = runBlocking {
        repeat(5) { server.enqueue(redirect("/r")) }
        server.enqueue(MockResponse.Builder().body("ok").build())
        assertArrayEquals("ok".toByteArray(), fetcher(maxRedirects = 5).fetch(server.url("/a").toString()))
        assertEquals(6, server.requestCount)
    }

    @Test
    fun nullOnNonHttpScheme() = runBlocking {
        assertNull(fetcher().fetch("ftp://x"))
        server.enqueue(redirect("magnet:?xt=urn:btih:abc"))
        assertNull(fetcher().fetch(server.url("/a").toString()))
    }

    @Test
    fun nullOnTimeout() = runBlocking {
        server.enqueue(MockResponse.Builder().body(body).bodyDelay(1, TimeUnit.SECONDS).build())
        val start = System.nanoTime()
        assertNull(fetcher(timeoutMs = 200).fetch(server.url("/a").toString()))
        assertTrue(System.nanoTime() - start < 900_000_000L)
    }

    @Test
    fun cancellationCancelsCall() = runBlocking {
        assertCancellation(duringBody = false)
    }

    @Test
    fun cancellationDuringBodyCancelsCall() = runBlocking {
        assertCancellation(duringBody = true)
    }

    private suspend fun assertCancellation(duringBody: Boolean) = coroutineScope {
        val readingBody = CompletableDeferred<Unit>()
        val bodyClosed = CompletableDeferred<Unit>()
        val cancelledCall = CompletableDeferred<Call>()
        val fetchCancelled = CompletableDeferred<CancellationException>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelledCall.complete(call) }
        }).addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            val observedSource = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    // OkHttp's responseBodyStart fires only AFTER the first read returns.
                    readingBody.complete(Unit)
                    return super.read(sink, byteCount)
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        bodyClosed.complete(Unit)
                    }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = observedSource
            }).build()
        }.build()
        val response = MockResponse.Builder().body(body)
        if (duringBody) response.bodyDelay(1, TimeUnit.SECONDS)
        else response.headersDelay(1, TimeUnit.SECONDS)
        server.enqueue(response.build())
        val job = async(Dispatchers.Default) {
            try {
                TorrentFileFetcher(client).fetch(server.url("/a").toString())
            } catch (e: CancellationException) {
                fetchCancelled.complete(e)
                throw e
            }
        }
        try {
            assertTrue(server.takeRequest(500, TimeUnit.MILLISECONDS) != null)
            if (duringBody) withTimeout(500) { readingBody.await() }
            val start = System.nanoTime()
            job.cancel()
            withTimeout(700) { job.join() }
            assertTrue(System.nanoTime() - start < 700_000_000L)
            assertTrue(job.isCancelled)
            assertTrue(fetchCancelled.isCompleted)
            assertTrue(cancelledCall.isCompleted)
            assertTrue(cancelledCall.await().isCanceled())
            if (duringBody) assertTrue(bodyClosed.isCompleted)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun acceptsBodyExactlyAtLimit() = runBlocking {
        server.enqueue(MockResponse.Builder().body("1234567890").build())
        server.enqueue(MockResponse.Builder().chunkedBody("1234567890", 4).build())
        repeat(2) {
            assertArrayEquals("1234567890".toByteArray(), fetcher(maxBytes = 10).fetch(server.url("/a").toString()))
        }
    }

    @Test
    fun nullOnMissingLocation() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).build())
        assertNull(fetcher().fetch(server.url("/a").toString()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun timeoutIncludesRedirects() = runBlocking {
        repeat(3) {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/next")
                .headersDelay(100, TimeUnit.MILLISECONDS).build())
        }
        server.enqueue(MockResponse.Builder().body(body).build())
        assertNull(fetcher(timeoutMs = 250).fetch(server.url("/a").toString()))
    }
}
