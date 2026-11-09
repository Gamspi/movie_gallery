package dev.gamspi.videogallery.watchlist

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ListSyncTest {
    private fun row(time: Long = 10) = ListRecord(ListTable.Titles, "kp-1", time)

    @Test fun cancellationCancelsHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().headersDelay(5, TimeUnit.SECONDS).body("{}").build())
            val cancelled = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            val operation = async(Dispatchers.Default) { HttpListApi(server.url("/"), "test-key", client).changes(0) }
            try {
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                withTimeout(2000) { operation.cancelAndJoin() }
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            } finally { operation.cancelAndJoin() }
        }
    }

    @Test fun uploadPrecedesReadingAndDoesNotSupplyReadCursor() = runTest {
        val store = LocalListStore({null}, {}, StandardTestDispatcher(testScheduler))
        store.edit(row())
        val calls = mutableListOf<String>()
        val api = object : ListApi {
            override suspend fun push(rows: List<ListRecord>) { calls += "push"; store.edit(row(11)) }
            override suspend fun changes(since: Long): ListPage {
                calls += "read:$since"
                return if(since == 0L) ListPage(listOf(row()), 1, true) else ListPage(emptyList(), 1, false)
            }
        }
        ListSync(store, api).exchange()
        assertEquals(listOf("push", "read:0", "read:1"), calls)
        assertEquals(11, store.pending().single().updatedAt)
        assertEquals(1, store.snapshot().cursor)
    }

    @Test fun failedUploadStaysQueuedAndDoesNotRead() = runTest {
        val store = LocalListStore({null}, {}, StandardTestDispatcher(testScheduler))
        store.edit(row())
        val api = object : ListApi {
            override suspend fun push(rows: List<ListRecord>) { throw IOException() }
            override suspend fun changes(since: Long): ListPage = error("Must not read")
        }
        try { ListSync(store, api).exchange(); fail("Expected failure") } catch (_: IOException) { }
        assertEquals(1, store.pending().size)
        assertEquals(0, store.snapshot().cursor)
    }

    @Test fun httpUsesContractAndKeepsKeyOffUrl() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"seq":1,"applied":1}""").build())
            server.enqueue(MockResponse.Builder().body("""{"titles":[{"key":"kp-1","data":{},"updated_at":10,"deleted":false,"seq":1}],"episodes":[],"positions":[],"next":1,"more":false}""").build())
            val api = HttpListApi(server.url("/"), "test-key", okhttp3.OkHttpClient())
            api.push(listOf(row()))
            val sent = server.takeRequest()
            assertEquals("/v1/batch", sent.url.encodedPath)
            assertEquals("test-key", sent.headers["X-VG-List-Key"])
            assertFalse(sent.url.toString().contains("test-key"))
            val page = api.changes(0)
            assertEquals(row(), page.rows.single())
            assertEquals("100", server.takeRequest().url.queryParameter("limit"))
        }
    }

    @Test fun redirectsAreRejected() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/other")).build())
            try { HttpListApi(server.url("/"), "test-key", okhttp3.OkHttpClient()).changes(0); fail("Expected HTTP failure") } catch (_: IOException) { }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun nonAdvancingPageIsRejected() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"titles":[],"episodes":[],"positions":[],"next":0,"more":true}""").build())
            try { HttpListApi(server.url("/"), "test-key", okhttp3.OkHttpClient()).changes(0); fail("Expected invalid page") } catch (_: IllegalArgumentException) { }
        }
    }
}
