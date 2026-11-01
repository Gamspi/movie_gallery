package dev.gamspi.videogallery.stream

import dev.gamspi.videogallery.settings.TorrentStoragePrefs
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamStorageIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val hash = "a".repeat(40)
    private val otherHash = "b".repeat(40)

    private class Engine(private val root: File) : TorrentEngine {
        val adds = CopyOnWriteArrayList<String>()
        val removals = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val addEntered = CompletableDeferred<Unit>()
        val removeEntered = CompletableDeferred<Unit>()
        var addGate: CompletableDeferred<Unit>? = null
        var removeGate: CompletableDeferred<Unit>? = null
        var removeFailure: Exception? = null
        val storageError = AtomicReference<TorrentStorageException?>(null)
        var selections = 0
        var priorities = 0
        override val pieceFinished = MutableSharedFlow<Pair<String, Int>>()
        override fun hashOf(magnet: String) = magnet.removePrefix("magnet:")
        override suspend fun add(magnet: String, saveDir: File): String {
            val hash = hashOf(magnet)
            adds += hash
            addEntered.complete(Unit)
            addGate?.await()
            return hash
        }
        override suspend fun awaitMetadata(hash: String) = listOf(TorrentFile(0, "movie.mkv", 100))
        override fun fileSpan(hash: String, fileIndex: Int) = FileSpan(0, 100, 1024)
        override fun selectFile(hash: String, fileIndex: Int) { selections++ }
        override fun havePiece(hash: String, piece: Int) = true
        override fun prioritize(hash: String, pieces: IntRange, deadlineMs: Int) { priorities++ }
        override fun status(hash: String) = EngineStatus(0, 0, 1f)
        override fun filePath(hash: String, fileIndex: Int) = File(root, "$hash/movie.mkv")
        override fun checkStorageError(hash: String) { storageError.get()?.let { throw it } }
        override fun remove(hash: String, deleteFiles: Boolean) { removals += hash to deleteFiles }
        override suspend fun removeAndAwait(hash: String, deleteFiles: Boolean) {
            removeEntered.complete(Unit)
            removeGate?.await()
            removeFailure?.let { throw it }
            remove(hash, deleteFiles)
        }
    }

    private inner class Fixture(keep: Boolean = false) {
        val files = temp.newFolder()
        val root = File(files, "torrents")
        val free = AtomicLong(Long.MAX_VALUE)
        val cache = TorrentCacheStore(root, freeBytes = free::get)
        val engine = Engine(root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = StreamManager(engine, StreamServer(scope), files, scope, tickMs = 20,
            cache = cache, storagePrefs = { TorrentStoragePrefs(keep, 1) })

        suspend fun ready(title: String = "Film") = withTimeout(5_000) {
            manager.state.first { it is StreamState.Ready && it.title == title }
        }
        suspend fun failed() = withTimeout(5_000) {
            manager.state.first { it is StreamState.Failed } as StreamState.Failed
        }
        suspend fun drain() = withTimeout(5_000) {
            while (scope.coroutineContext.job.children.any()) {
                scope.coroutineContext.job.children.toList().forEach { it.join() }
            }
        }
        suspend fun close() {
            engine.addGate?.complete(Unit)
            engine.removeGate?.complete(Unit)
            manager.stop()
            try { drain() } finally { scope.cancel() }
        }
    }

    @Test fun sameHash_restart_waits_for_previous_native_removal() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.manager.start("magnet:$hash", "Film")
            f.ready()
            f.engine.removeGate = CompletableDeferred()
            f.manager.start("magnet:$hash", "Second")
            withTimeout(5_000) { f.engine.removeEntered.await() }
            assertEquals(listOf(hash), f.engine.adds.toList())
            f.engine.removeGate!!.complete(Unit)
            f.ready("Second")
            assertEquals(listOf(hash, hash), f.engine.adds.toList())
        } finally { f.close() }
    }

    @Test fun clear_during_suspended_add_preserves_retained_directory() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.cache.acquire(hash)
            f.cache.select(hash, 0, "movie.mkv", 100, 1024)
            val payload = File(f.root, "$hash/movie.mkv").apply { writeBytes(ByteArray(100)) }
            f.cache.release(hash, true, 1024)
            f.engine.addGate = CompletableDeferred()
            f.manager.start("magnet:$hash", "Film")
            withTimeout(5_000) { f.engine.addEntered.await() }
            assertEquals(0L, f.cache.clearInactive())
            assertTrue(payload.exists())
            f.engine.addGate!!.complete(Unit)
            f.ready()
        } finally { f.close() }
    }

    @Test fun exit_respects_retention_and_removes_native_handle_without_native_deletion() = runBlocking<Unit> {
        for (keep in listOf(false, true)) {
            val f = Fixture(keep)
            try {
                f.manager.start("magnet:$hash", "Film")
                f.ready()
                val payload = File(f.root, "$hash/movie.mkv").apply { writeBytes(ByteArray(100)) }
                f.manager.stop()
                f.drain()
                assertEquals(listOf(hash to false), f.engine.removals.toList())
                TorrentCacheStore(f.root).initialize()
                assertEquals(keep, payload.exists())
            } finally { f.close() }
        }
    }

    @Test fun insufficient_space_fails_before_file_selection_or_piece_priority() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.free.set(TorrentCacheStore.MIN_FREE_BYTES + 50)
            f.manager.start("magnet:$hash", "Film")
            assertTrue(f.failed().message.contains("Недостаточно свободного места"))
            assertEquals(0, f.engine.selections)
            assertEquals(0, f.engine.priorities)
        } finally { f.close() }
    }

    @Test fun native_write_failure_after_ready_becomes_terminal_storage_message() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.manager.start("magnet:$hash", "Film")
            f.ready()
            val reason = "Не удалось записать файл раздачи. Проверьте свободное место"
            f.engine.storageError.set(TorrentStorageException(reason))
            assertEquals(reason, f.failed().message)
        } finally { f.close() }
    }

    @Test fun failed_native_removal_blocks_next_add_and_protects_old_files() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.manager.start("magnet:$hash", "Film")
            f.ready()
            val payload = File(f.root, "$hash/movie.mkv").apply { writeBytes(ByteArray(100)) }
            f.engine.removeFailure = IOException("native removal not acknowledged")
            f.manager.start("magnet:$otherHash", "Second")
            assertTrue(f.failed().message.contains("Перезапустите приложение"))
            assertEquals(listOf(hash), f.engine.adds.toList())
            assertTrue(payload.exists())
            assertEquals(0L, f.cache.clearInactive())
            assertFalse(f.engine.removals.any())
        } finally { f.close() }
    }
}
