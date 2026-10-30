package dev.gamspi.videogallery.stream

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
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
        val live = mutableSetOf<String>()
        var addDelayMs = 0L
        var statusError = false
        var spanOffset = 0L
        val prioritized = mutableListOf<Pair<IntRange, Int>>()
        var cleared = 0
        override val pieceFinished = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 64)

        override suspend fun add(magnet: String, saveDir: File): String {
            addError?.let { throw it }
            if (addDelayMs > 0) kotlinx.coroutines.delay(addDelayMs)
            added += magnet
            val hash = magnet.removePrefix("magnet:").lowercase()
            live += hash
            return hash
        }

        override suspend fun awaitMetadata(hash: String): List<TorrentFile> =
            metadata?.invoke() ?: files

        override fun fileSpan(hash: String, fileIndex: Int) =
            FileSpan(spanOffset, files.first { it.index == fileIndex }.size, 1024 * 1024)

        override fun selectFile(hash: String, fileIndex: Int) {
            selected += hash to fileIndex
        }

        override fun havePiece(hash: String, piece: Int) = piece in have
        override fun prioritize(hash: String, pieces: IntRange, deadlineMs: Int) {
            prioritized += pieces to deadlineMs
        }

        override fun clearDeadlines(hash: String) {
            cleared++
        }

        override fun status(hash: String): EngineStatus {
            if (statusError) throw IllegalStateException("status")
            return EngineStatus(2_202_010, 3, 0.5f)
        }
        override fun filePath(hash: String, fileIndex: Int) = File("unused")
        override fun remove(hash: String, deleteFiles: Boolean) {
            removed += hash to deleteFiles
            live -= hash
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

    @Test
    fun stop_duringAdd_removesTorrent() = runTest {
        engine.files = listOf(TorrentFile(0, "a.mkv", 10 * mib))
        engine.addDelayMs = 1_000
        val sm = manager()
        sm.start("magnet:hhh", "Film")
        advanceTimeBy(500)
        sm.stop()
        advanceTimeBy(2_000)
        assertEquals(StreamState.Idle, sm.state.value)
        assertTrue(engine.live.isEmpty())
        assertTrue(engine.removed.contains("hhh" to true))
    }

    @Test
    fun doubleChooseFile_startsOneBuffer() = runTest {
        engine.files = listOf(TorrentFile(0, "a.mkv", 10 * mib), TorrentFile(1, "b.mp4", 20 * mib))
        val sm = manager()
        sm.start("magnet:iii", "Film")
        runCurrent()
        sm.chooseFile(0)
        sm.chooseFile(1)
        runCurrent()
        assertEquals(listOf("iii" to 0), engine.selected)
        sm.stop()
    }

    @Test
    fun statusFailsAfterReady_keepsReady() = runTest {
        engine.files = listOf(TorrentFile(0, "a.mkv", 10 * mib))
        val sm = manager()
        sm.start("magnet:jjj", "Film")
        runCurrent()
        engine.have += 0..9
        advanceTimeBy(1_100)
        assertTrue(sm.state.value is StreamState.Ready)
        engine.statusError = true
        advanceTimeBy(3_000)
        assertTrue(sm.state.value is StreamState.Ready)
        assertTrue(engine.removed.isEmpty())
        sm.stop()
    }

    private fun source(span: FileSpan = FileSpan(3 * mib, 100 * mib, 1024 * 1024)) =
        EnginePieceSource(engine, "h", span, File("unused"), readAheadBytes = 4 * mib)

    @Test
    fun awaitRange_mapsOffsetToPieces_andWaitsForEvent() = runTest {
        val src = source()
        var done = false
        val job = launch { src.awaitRange(0, 10); done = true }
        runCurrent()
        // file offset 3 MiB, piece 1 MiB: byte 0 is piece 3
        assertEquals(3..3, engine.prioritized.first().first)
        assertTrue(!done)
        engine.have += 3
        engine.pieceFinished.emit("h" to 3)
        runCurrent()
        assertTrue(done)
        job.join()
    }

    @Test
    fun awaitRange_missedEvent_pollsHavePiece() = runTest {
        val src = source()
        var done = false
        launch { src.awaitRange(0, 10); done = true }
        runCurrent()
        engine.have += 3 // no event emitted
        advanceTimeBy(1_100)
        assertTrue(done)
    }

    @Test
    fun awaitRange_alreadyHave_returnsImmediately() = runTest {
        engine.have += 3
        var done = false
        launch { source().awaitRange(0, 10); done = true }
        runCurrent()
        assertTrue(done)
    }

    @Test
    fun awaitRange_readAheadStepped_andSeekClears() = runTest {
        engine.have += 3..80
        val src = source()
        src.awaitRange(0, 10)
        val deadlines = engine.prioritized.map { it.second }
        assertEquals(deadlines.sorted(), deadlines)
        assertTrue(deadlines.size > 1)
        assertEquals(0, engine.cleared)
        val before = engine.prioritized.size
        src.awaitRange(1, 11) // same pieces, already deadlined
        assertEquals(before, engine.prioritized.size)
        src.awaitRange(50 * mib, 50 * mib + 10) // seek
        assertEquals(1, engine.cleared)
    }

    @Test
    fun awaitRange_reappliesDeadlineAfterTimeout() = runTest {
        val src = source()
        launch { src.awaitRange(0, 10) }
        runCurrent()
        val before = engine.prioritized.count { it.first == 3..3 }
        advanceTimeBy(1_100)
        assertTrue(engine.prioritized.count { it.first == 3..3 } > before)
        engine.have += 3
        advanceTimeBy(1_100)
    }
}
