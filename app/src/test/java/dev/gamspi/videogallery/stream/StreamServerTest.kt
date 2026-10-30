package dev.gamspi.videogallery.stream

import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamServerTest {
    private class FakeSource(
        val data: ByteArray,
        override val fileName: String = "movie.mkv",
        val onAwait: suspend (Long, Long) -> Unit = { _, _ -> },
    ) : PieceSource {
        override val length: Long = data.size.toLong()
        override suspend fun awaitRange(start: Long, endInclusive: Long) = onAwait(start, endInclusive)
        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(count.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val server = StreamServer(scope)
    private val client = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(2, TimeUnit.SECONDS)
        .build()
    private val bytes = ByteArray(1000) { (it % 251).toByte() }

    @After fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun get(url: String, range: String? = null, method: String = "GET") =
        client.newCall(
            Request.Builder().url(url).method(method, null).apply { if (range != null) header("Range", range) }.build(),
        ).execute()

    @Test fun get_full_200_withLengthAndType() {
        val url = server.start(FakeSource(bytes))
        assertTrue(url.startsWith("http://127.0.0.1:"))
        assertTrue(url.endsWith("/stream"))
        get(url).use { r ->
            assertEquals(200, r.code)
            assertEquals("bytes", r.header("Accept-Ranges"))
            assertEquals("1000", r.header("Content-Length"))
            assertEquals("video/x-matroska", r.header("Content-Type"))
            assertTrue(bytes.contentEquals(r.body.bytes()))
        }
    }

    @Test fun get_largerThanOneBlock_andQueryString() {
        val big = ByteArray(600_000) { (it % 253).toByte() }
        val url = server.start(FakeSource(big, "a.mp4"))
        get("$url?x=1").use { r ->
            assertEquals(200, r.code)
            assertEquals("video/mp4", r.header("Content-Type"))
            assertTrue(big.contentEquals(r.body.bytes()))
        }
    }

    @Test fun range_206_contentRange() {
        val url = server.start(FakeSource(bytes))
        get(url, "bytes=100-199").use { r ->
            assertEquals(206, r.code)
            assertEquals("bytes 100-199/1000", r.header("Content-Range"))
            assertEquals("100", r.header("Content-Length"))
            assertTrue(bytes.copyOfRange(100, 200).contentEquals(r.body.bytes()))
        }
    }

    @Test fun openRange_toEnd() {
        val url = server.start(FakeSource(bytes))
        get(url, "bytes=900-").use { r ->
            assertEquals(206, r.code)
            assertEquals("bytes 900-999/1000", r.header("Content-Range"))
            assertTrue(bytes.copyOfRange(900, 1000).contentEquals(r.body.bytes()))
        }
    }

    @Test fun head_noBody() {
        val url = server.start(FakeSource(bytes))
        get(url, method = "HEAD").use { r ->
            assertEquals(200, r.code)
            assertEquals("1000", r.header("Content-Length"))
            assertEquals("bytes", r.header("Accept-Ranges"))
            assertEquals(0, r.body.bytes().size)
        }
    }

    @Test fun outOfRange_416() {
        val url = server.start(FakeSource(bytes))
        get(url, "bytes=1000-").use { r ->
            assertEquals(416, r.code)
            assertEquals("bytes */1000", r.header("Content-Range"))
        }
    }

    @Test fun unknownPath_404_andPost_405() {
        val url = server.start(FakeSource(bytes))
        get(url.replace("/stream", "/other")).use { assertEquals(404, it.code) }
        client.newCall(
            Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build(),
        ).execute().use { assertEquals(405, it.code) }
    }

    @Test fun waitsForData() {
        val ready = CompletableDeferred<Unit>()
        val src = FakeSource(bytes) { _, _ -> ready.await() }
        val url = server.start(src)
        scope.launch { delay(300); ready.complete(Unit) }
        val t0 = System.nanoTime()
        get(url, "bytes=500-599").use { r ->
            assertEquals(206, r.code)
            assertTrue(bytes.copyOfRange(500, 600).contentEquals(r.body.bytes()))
        }
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 250)
    }

    @Test fun clientDisconnect_cancelsAwait() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val src = FakeSource(bytes) { _, _ ->
            entered.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val url = server.start(src)
        val port = url.substringAfter("127.0.0.1:").substringBefore("/").toInt()
        val sock = Socket("127.0.0.1", port)
        sock.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        sock.getOutputStream().flush()
        withTimeout(2000) { entered.await() }
        assertTrue(!cancelled.isCompleted)
        sock.close()
        withTimeout(2000) { cancelled.await() }
    }

    @Test fun stop_closesSocket() {
        val url = server.start(FakeSource(bytes))
        val port = url.substringAfter("127.0.0.1:").substringBefore("/").toInt()
        server.stop()
        try {
            Socket("127.0.0.1", port).close()
            fail("expected connection refused")
        } catch (e: ConnectException) {
            assertNull(null)
        }
    }

    @Test fun stop_cancelsInFlightAwait() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val src = FakeSource(bytes) { _, _ ->
            entered.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val url = server.start(src)
        val port = url.substringAfter("127.0.0.1:").substringBefore("/").toInt()
        val sock = Socket("127.0.0.1", port)
        sock.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        sock.getOutputStream().flush()
        withTimeout(2000) { entered.await() }
        server.stop()
        withTimeout(2000) { cancelled.await() }
        sock.close()
    }
}
