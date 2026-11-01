package dev.gamspi.videogallery.subs

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class OpenSubtitlesHashTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun file(size: Int) = ByteArray(size) { (it % 256).toByte() }

    @Test
    fun hash_known_vector() {
        // Reference (py -I -c):
        //   import struct; d=bytes(i%256 for i in range(131072)); h=131072
        //   for b in (d[:65536], d[-65536:]): h+=sum(struct.unpack('<8192Q', b))
        //   print('%016x' % (h & (2**64-1)))
        val d = file(131072)
        assertEquals("a0601fdf9f610000", openSubtitlesHash(131072, d.copyOfRange(0, 65536), d.copyOfRange(65536, 131072)))
    }

    @Test
    fun hash_small_file() {
        val d = file(1000)
        val h = openSubtitlesHash(1000, d, d)
        assertEquals(16, h.length)
    }

    @Test
    fun fetchHash_over_range() = runBlocking {
        val d = file(200000)
        server.enqueue(rangeResponse(d, 0, 65535))
        server.enqueue(rangeResponse(d, 200000 - 65536, 199999))
        val url = server.url("/v").newBuilder().host("127.0.0.1").build()
        val h = fetchHashOverHttp(OkHttpClient(), url.toString())
        assertEquals(openSubtitlesHash(200000, d.copyOfRange(0, 65536), d.copyOfRange(200000 - 65536, 200000)), h)
        val head = server.takeRequest()
        val tail = server.takeRequest()
        assertEquals("bytes=0-65535", head.headers["Range"])
        assertEquals("bytes=134464-199999", tail.headers["Range"])
        assertEquals("subtitle-hash", head.headers["X-VideoGallery-Request"])
        assertEquals("subtitle-hash", tail.headers["X-VideoGallery-Request"])
    }

    @Test
    fun fetchHash_external_host_does_not_send_internal_header() = runBlocking {
        val d = file(200000)
        server.enqueue(rangeResponse(d, 0, 65535))
        server.enqueue(rangeResponse(d, 200000 - 65536, 199999))
        // Resolve the external-looking test host locally; no external server is contacted.
        val http = OkHttpClient.Builder()
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }.build()
        val url = server.url("/v").newBuilder().host("video.example").build()
        val hash = fetchHashOverHttp(http, url.toString())
        assertEquals(openSubtitlesHash(200000, d.copyOfRange(0, 65536), d.copyOfRange(200000 - 65536, 200000)), hash)
        assertNull(server.takeRequest().headers["X-VideoGallery-Request"])
        assertNull(server.takeRequest().headers["X-VideoGallery-Request"])
    }

    @Test
    fun fetchHash_no_range_returns_null() = runBlocking {
        server.enqueue(MockResponse.Builder().body("whole file").build())
        assertNull(fetchHashOverHttp(OkHttpClient(), server.url("/v").toString()))
    }

    private fun rangeResponse(d: ByteArray, from: Int, to: Int) =
        MockResponse.Builder()
            .code(206)
            .addHeader("Content-Range", "bytes $from-$to/${d.size}")
            .body(Buffer().write(d, from, to - from + 1))
            .build()
}
