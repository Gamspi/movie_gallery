package dev.gamspi.videogallery.watchlist

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackListTest {
    private fun title(time: Long=10) = ListRecord(ListTable.Titles,"kp-1",time,data=buildJsonObject { put("status","watching");put("notify",true) })
    @Test fun playbackPreservesUserFieldsAndCommitsPositionTogether() = runTest {
        val store=LocalListStore({null},{},StandardTestDispatcher(testScheduler))
        store.edit(title().copy(data=buildJsonObject {put("status","unwatched");put("my_rating",8);put("notify",false)}))
        val position=ListRecord(ListTable.Positions,"kp-1",20,positionMs=500,durationMs=1000)
        store.recordPlayback(listOf(title(20),position),false)
        val snapshot=store.snapshot()
        val data=snapshot.records.getValue(title().identity).data
        assertEquals(8,data.getValue("my_rating").jsonPrimitive.int)
        assertFalse(data.getValue("notify").jsonPrimitive.boolean)
        assertEquals("watching",data.getValue("status").jsonPrimitive.content)
        assertEquals(position,snapshot.pending[position.identity])
    }
    @Test fun completedSeriesOnlyChangesStatusWhenConfirmed() = runTest {
        val store=LocalListStore({null},{},StandardTestDispatcher(testScheduler))
        store.recordPlayback(listOf(title()),false)
        assertEquals("watching",store.snapshot().records.values.single().data.getValue("status").jsonPrimitive.content)
        store.recordPlayback(listOf(title(20)),true)
        assertEquals("watched",store.snapshot().records.values.single().data.getValue("status").jsonPrimitive.content)
    }
    @Test fun unchangedTitleIsNotRequeuedAndDeletedTitleCanBeWatchedAgain() = runTest {
        val store=LocalListStore({null},{},StandardTestDispatcher(testScheduler))
        store.recordPlayback(listOf(title()),false)
        store.acknowledge(store.pending())
        store.recordPlayback(listOf(title(20)),false)
        assertTrue(store.pending().isEmpty())
        store.edit(title(30).copy(deleted=true))
        store.recordPlayback(listOf(title(40)),false)
        assertFalse(store.snapshot().records.values.single().deleted)
    }
}
