package dev.gamspi.videogallery.stream

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamManagerTest {
    private val mib = 1024L * 1024

    private class FakeEngine : TorrentEngine {
        var files: List<TorrentFile> = emptyList()
        var metadata: (suspend () -> List<TorrentFile>)? = null
        var addError: Throwable? = null
        val have = mutableSetOf<Int>()
        val removed = mutableListOf<Pair<String, Boolean>>()
        val selected = mutableListOf<Pair<String, Int>>()
        val added = mutableListOf<String>()
        override val pieceFinished = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 64)

        override suspend fun add(magnet: String, saveDir: File): String {
            addError?.let { throw it }
            added += magnet
            return magnet.removePrefix("magnet:").lowercase()
        }

        override suspend fun awaitMetadata(hash: String): List<TorrentFile> =
            metadata?.invoke() ?: files

        override fun fileSpan(hash: String, fileIndex: Int) =
            FileSpan(0, files.first { it.index == fileIndex }.size, 1024 * 1024)

        override fun selectFile(hash: String, fileIndex: Int) {
            selected += hash to fileIndex
        }

        override fun havePiece(hash: String, piece: Int) = piece in have
        override fun prioritize(hash: String, pieces: IntRange, deadlineMs: Int) {}
        override fun status(hash: String) = EngineStatus(2_202_010, 3, 0.5f)
        override fun filePath(hash: String, fileIndex: Int) = File("unused")
        override fun remove(hash: String, deleteFiles: Boolean) {
            removed += hash to deleteFiles
        }
    }

    private val engine = FakeEngine()
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("smtest", "").also { it.delete(); it.mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun TestScope.manager(): StreamManager =
        StreamManager(engine, StreamServer(backgroundScope), dir, backgroundScope)

    @Test
    fun metadataTimeout_failsNoPeers() = runTest {
        engine.metadata = { awaitCancellation() }
        val sm = manager()
        sm.start("magnet:aaa", "Film")
        runCurrent()
        assertEquals(StreamState.FetchingMetadata, sm.state.value)
        advanceTimeBy(59_000)
        assertEquals(StreamState.FetchingMetadata, sm.state.value)
        advanceTimeBy(2_000)
        assertEquals(StreamState.Failed("Нет пиров — выберите другую раздачу"), sm.state.value)
        assertTrue(engine.removed.contains("aaa" to true))
        sm.stop()
    }

    @Test
    fun noVideo_failsNotFound() = runTest {
        engine.files = listOf(TorrentFile(0, "a.rar", 10 * mib), TorrentFile(1, "b.iso", 5 * mib))
        val sm = manager()
        sm.start("magnet:bbb", "Film")
        runCurrent()
        assertEquals(StreamState.Failed("Видеофайлы не найдены"), sm.state.value)
        assertTrue(engine.removed.contains("bbb" to true))
        sm.stop()
    }

    @Test
    fun singleVideo_buffersThenReady() = runTest {
        engine.files = listOf(TorrentFile(0, "a.mkv", 10 * mib), TorrentFile(1, "readme.txt", 1))
        val sm = manager()
        sm.start("magnet:ccc", "Film")
        runCurrent()
        val buffering = sm.state.value
        assertTrue("was $buffering", buffering is StreamState.Buffering)
        assertEquals(0, (buffering as StreamState.Buffering).percent)
        assertEquals(listOf("ccc" to 0), engine.selected)
        engine.have += 0..9
        advanceTimeBy(1_100)
        val ready = sm.state.value as StreamState.Ready
        assertEquals("bt:ccc:0", ready.key)
        assertEquals("Film", ready.title)
        assertTrue(ready.url.startsWith("http://127.0.0.1:"))
        assertTrue(sm.statusLine.value!!.contains("пира"))
        sm.stop()
    }

    @Test
    fun multipleVideos_chooseFile_thenBuffers() = runTest {
        engine.files = listOf(TorrentFile(0, "a.mkv", 10 * mib), TorrentFile(1, "b.mp4", 20 * mib))
        val sm = manager()
        sm.start("magnet:ddd", "Film")
        runCurrent()
        val choose = sm.state.value as StreamState.ChooseFile
        assertEquals(listOf(1, 0), choose.files.map { it.index })
        sm.chooseFile(0)
        runCurrent()
        assertTrue(sm.state.value is StreamState.Buffering)
        engine.have += 0..9
        advanceTimeBy(1_100)
        assertEquals("bt:ddd:0", (sm.state.value as StreamState.Ready).key)
        sm.stop()
    }

    @Test
    fun stop_removesAndDeletes_backToIdle() = runTest {
        engine.files = listOf(TorrentFile(0, "a.mkv", 10 * mib))
        val sm = manager()
        sm.start("magnet:eee", "Film")
        runCurrent()
        sm.stop()
        assertEquals(StreamState.Idle, sm.state.value)
        assertNull(sm.statusLine.value)
        assertEquals(listOf("eee" to true), engine.removed)
    }

    @Test
    fun stop_beforeMetadata_removes() = runTest {
        engine.metadata = { awaitCancellation() }
        val sm = manager()
        sm.start("magnet:fff", "Film")
        runCurrent()
        sm.stop()
        runCurrent()
        assertEquals(StreamState.Idle, sm.state.value)
        assertEquals(listOf("fff" to true), engine.removed)
    }

    @Test
    fun start_whileActive_stopsPrevious() = runTest {
        engine.files = listOf(TorrentFile(0, "a.mkv", 10 * mib))
        val sm = manager()
        sm.start("magnet:one", "A")
        runCurrent()
        sm.start("magnet:two", "B")
        runCurrent()
        assertEquals(listOf("one" to true), engine.removed)
        assertEquals(listOf("magnet:one", "magnet:two"), engine.added)
        assertTrue(sm.state.value is StreamState.Buffering)
        sm.stop()
    }

    @Test
    fun engineThrows_failsGeneric() = runTest {
        engine.addError = IllegalStateException("boom")
        val sm = manager()
        sm.start("magnet:ggg", "Film")
        runCurrent()
        assertEquals(StreamState.Failed("Не удалось запустить раздачу"), sm.state.value)
        sm.stop()
    }
}
