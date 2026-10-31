package dev.gamspi.videogallery.stream

import androidx.lifecycle.SavedStateHandle
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamViewModelTest {
    private val mib = 1024L * 1024
    private val engine = FakeTorrentEngine()
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("svmtest", "").also { it.delete(); it.mkdirs() }
        engine.files = listOf(TorrentFile(0, "a.mkv", 10 * mib))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun TestScope.manager() =
        StreamManager(engine, StreamServer(backgroundScope), dir, backgroundScope)

    private fun vm(sm: StreamManager) = StreamViewModel(
        SavedStateHandle(mapOf("magnet" to "magnet:abc", "title" to "Film")),
        sm,
    )

    @Test
    fun init_startsSession() = runTest {
        val sm = manager()
        vm(sm)
        runCurrent()
        assertEquals(listOf("magnet:abc"), engine.added)
        assertTrue(sm.state.value is StreamState.Buffering)
        sm.stop()
    }

    @Test
    fun imdb_readFromSavedState() = runTest {
        val sm = manager()
        val withImdb = StreamViewModel(
            SavedStateHandle(mapOf("magnet" to "magnet:abc", "title" to "Film", "imdb" to "tt1")),
            sm,
        )
        assertEquals("tt1", withImdb.imdb)
        assertEquals("", vm(sm).imdb)
        sm.stop()
    }

    @Test
    fun cancel_stops() = runTest {
        val sm = manager()
        val vm = vm(sm)
        runCurrent()
        vm.cancel()
        assertEquals(StreamState.Idle, sm.state.value)
        assertTrue(engine.removed.contains("abc" to true))
    }

    @Test
    fun cleared_beforeReady_stops() = runTest {
        val sm = manager()
        val vm = vm(sm)
        runCurrent()
        vm.clearViaStore()
        assertEquals(StreamState.Idle, sm.state.value)
    }

    @Test
    fun cleared_afterReady_keepsSession() = runTest {
        val sm = manager()
        val vm = vm(sm)
        runCurrent()
        engine.have += 0..9
        advanceTimeBy(1_100)
        assertTrue(sm.state.value is StreamState.Ready)
        vm.clearViaStore()
        assertTrue(sm.state.value is StreamState.Ready)
        sm.stop()
    }

    @Test
    fun oldVmCleared_afterNewSession_newSurvives() = runTest {
        val sm = manager()
        val old = vm(sm)
        runCurrent()
        val fresh = vm(sm)
        runCurrent()
        old.clearViaStore()
        assertTrue(sm.state.value is StreamState.Buffering)
        fresh.clearViaStore()
        assertEquals(StreamState.Idle, sm.state.value)
    }
}
