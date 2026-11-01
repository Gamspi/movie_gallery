package dev.gamspi.videogallery.subs

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleHttpCancellationTest {
    @Test fun download_cancel_before_headers_cancels_call() = downloadCancellation(bodyDelay = false)
    @Test fun download_cancel_during_body_cancels_call() = downloadCancellation(bodyDelay = true)
    @Test fun hash_cancel_head_before_headers_cancels_call() = hashCancellation(tail = false, bodyDelay = false)
    @Test fun hash_cancel_head_during_body_cancels_call() = hashCancellation(tail = false, bodyDelay = true)
    @Test fun hash_cancel_tail_before_headers_cancels_call() = hashCancellation(tail = true, bodyDelay = false)
    @Test fun hash_cancel_tail_during_body_cancels_call() = hashCancellation(tail = true, bodyDelay = true)

    private fun downloadCancellation(bodyDelay: Boolean) {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"link":"${server.url("/file.srt")}","remaining":1}""").build())
            server.enqueue(delayedResponse(MockResponse.Builder().body("subtitle"), bodyDelay))
            assertCancellation(server, "/file.srt", bodyDelay, requestsBeforeTarget = 1) { http ->
                val settings = object : SubtitleSettingsSource {
                    override val openSubtitlesKey = "KEY"
                    override val openSubtitlesUser: String? = null
                    override val openSubtitlesPassword: String? = null
                }
                OpenSubtitlesClient(server.url("/api/v1/").toString(), settings, http).download(1)
            }
        }
    }

    private fun hashCancellation(tail: Boolean, bodyDelay: Boolean) {
        MockWebServer().use { server ->
            server.start()
            if (tail) server.enqueue(rangeResponse(from = 0).build())
            server.enqueue(delayedResponse(rangeResponse(if (tail) 65536 else 0), bodyDelay))
            assertCancellation(server, "/video", bodyDelay, requestsBeforeTarget = if (tail) 1 else 0) { http ->
                fetchHashOverHttp(http, server.url("/video").toString())
            }
            assertEquals(if (tail) 2 else 1, server.requestCount)
        }
    }

    private fun rangeResponse(from: Int) = MockResponse.Builder()
        .code(206)
        .addHeader("Content-Range", "bytes $from-${from + 65535}/131072")
        .body(Buffer().write(ByteArray(65536)))

    private fun delayedResponse(builder: MockResponse.Builder, body: Boolean): MockResponse =
        // Send one byte so responseBodyStart proves reading began, then stall the rest.
        if (body) builder.throttleBody(1, 10, TimeUnit.SECONDS).build()
        else builder.headersDelay(10, TimeUnit.SECONDS).build()

    private fun assertCancellation(
        server: MockWebServer,
        targetPath: String,
        bodyDelay: Boolean,
        requestsBeforeTarget: Int,
        operation: suspend (OkHttpClient) -> Any?,
    ) = runBlocking {
        val bodyStarted = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val bodyCount = java.util.concurrent.atomic.AtomicInteger()
        val returnedNormally = AtomicBoolean()
        val http = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) {
                    if (call.request().url.encodedPath == targetPath &&
                        bodyCount.incrementAndGet() > if (targetPath == "/video") requestsBeforeTarget else 0
                    ) bodyStarted.countDown()
                }

                override fun canceled(call: Call) {
                    if (call.request().url.encodedPath == targetPath) canceled.countDown()
                }
            })
            .build()
        val result = async(Dispatchers.Default) {
            operation(http)
            returnedNormally.set(true)
        }
        try {
            repeat(requestsBeforeTarget + 1) {
                checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) { "request not received" }
            }
            if (bodyDelay) assertTrue("body read did not start", bodyStarted.await(3, TimeUnit.SECONDS))
            withTimeout(2_000) { result.cancelAndJoin() }
            assertTrue("underlying call not canceled", canceled.await(1, TimeUnit.SECONDS))
            assertTrue("cancellation returned Failed/null instead of throwing", !returnedNormally.get())
            assertTrue(result.isCancelled)
        } finally {
            result.cancelAndJoin()
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }
}
