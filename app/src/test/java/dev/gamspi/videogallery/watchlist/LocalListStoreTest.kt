package dev.gamspi.videogallery.watchlist

import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class LocalListStoreTest {
    private fun row(time: Long = 10) = ListRecord(ListTable.Titles, "tt0111161", time,
        data = buildJsonObject { put("status", "unwatched") })

    @Test fun rejectsInvalidProtocolValues() {
        for (invalid in listOf(
            row().copy(titleKey = "unknown"),
            row().copy(updatedAt = Long.MAX_VALUE),
            row().copy(positionMs = -1),
            row().copy(season = 10000),
            row().copy(data = buildJsonObject { put("name", "я".repeat(8192)) }),
        )) {
            try { invalid.validated(); fail("Expected invalid record") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun keysPreferExternalIdsAndSeparateMediaTypes() {
        assertEquals("tt0111161", watchlistKey("tt0111161", 1, 2, false))
        assertEquals("kp-1", watchlistKey("invalid", 1, 2, true))
        assertEquals("tmdb-tv-2", watchlistKey(null, null, 2, true))
        assertEquals("tmdb-movie-2", watchlistKey(null, null, 2, false))
        assertNull(watchlistKey(null, null, null, false))
    }

    @Test fun restartKeepsQueueAndTombstones() = runTest {
        var disk: String? = null
        fun open() = LocalListStore({disk}, {disk=it}, StandardTestDispatcher(testScheduler))
        val store = open()
        store.edit(row().copy(deleted=true))
        val reopened = open().snapshot()
        assertTrue(reopened.records.values.single().deleted)
        assertEquals(reopened.records, reopened.pending)
    }

    @Test fun ackDoesNotLoseConcurrentEdit() = runTest {
        val store = LocalListStore({null}, {}, StandardTestDispatcher(testScheduler))
        store.edit(row())
        val sent = store.pending()
        store.edit(row().copy(deleted=true))
        store.acknowledge(sent)
        assertTrue(store.pending().single().deleted)
        assertEquals(11, store.pending().single().updatedAt)
        store.acknowledge(store.pending())
        assertTrue(store.pending().isEmpty())
    }

    @Test fun failedWriteDoesNotPublishOrAcknowledge() = runTest {
        var fail = false
        val store = LocalListStore({null}, {if(fail) throw IOException()}, StandardTestDispatcher(testScheduler))
        store.edit(row())
        fail = true
        try { store.acknowledge(store.pending()); fail("Expected disk failure") } catch (_: IOException) { }
        assertEquals(1, store.pending().size)
        try { store.mergePage(0, 12, listOf(row(12))); fail("Expected disk failure") } catch (_: IOException) { }
        assertEquals(0, store.snapshot().cursor)
        assertEquals(10, store.snapshot().records.values.single().updatedAt)
    }

    @Test fun corruptedStorageIsNotOverwritten() = runTest {
        var writes = 0
        val store = LocalListStore({"broken"}, {writes++}, StandardTestDispatcher(testScheduler))
        try { store.edit(row()); fail("Expected decode failure") } catch (_: IllegalArgumentException) { }
        assertEquals(0, writes)
    }

    @Test fun pagesMergeByTimestampAndPersistCursorTogether() = runTest {
        var disk: String? = null
        fun open() = LocalListStore({disk}, {disk=it}, StandardTestDispatcher(testScheduler))
        val store = open()
        store.edit(row(20).copy(deleted=true))
        store.mergePage(0, 1, listOf(row(10)))
        assertTrue(store.snapshot().records.values.single().deleted)
        store.mergePage(1, 2, listOf(row(30)))
        assertFalse(open().snapshot().records.values.single().deleted)
        assertEquals(2, open().snapshot().cursor)
        try {store.mergePage(0, 3, emptyList()); fail("Expected stale page rejection")} catch (_: IllegalArgumentException) { }
    }

    @Test fun importsOnceAndDoesNotOverwriteExistingEdits() = runTest {
        var disk: String? = null
        fun open() = LocalListStore({disk}, {disk=it}, StandardTestDispatcher(testScheduler))
        val store = open()
        store.edit(row(20))
        store.importHistory(listOf(row(100)))
        assertEquals(20, store.snapshot().records.values.single().updatedAt)
        open().importHistory(listOf(row().copy(titleKey="kp-1")))
        assertEquals(1, open().snapshot().records.size)
        assertTrue(open().snapshot().historyImported)
    }

    @Test fun episodeAndPositionAreIndependentOfTitle() = runTest {
        val store = LocalListStore({null}, {}, StandardTestDispatcher(testScheduler))
        store.edit(row())
        store.edit(row().copy(table=ListTable.Episodes, season=1, episode=1, watched=true))
        store.edit(row().copy(table=ListTable.Positions, season=1, episode=1, positionMs=12))
        assertEquals(3, store.snapshot().records.size)
        assertEquals(2, store.pending(2).size)
    }
}
