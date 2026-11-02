package dev.gamspi.videogallery.stream

import dev.gamspi.videogallery.settings.TorrentStoragePrefs
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamManagerTorrentFileTest {
    @get:Rule val temp = TemporaryFolder()
    private val hash = "a".repeat(40)
    private val magnet = "magnet:$hash"
    private val url = "https://provider.example/download/123?token=private"
    private val bytes = "valid metadata".toByteArray()

    private inner class Fixture(
        useCache: Boolean = false,
        knownHash: Boolean = true,
        enableSource: Boolean = true,
    ) {
        val files = temp.newFolder()
        val root = File(files, "torrents")
        val stored = File(root, "$hash/meta.torrent")
        val temporary = File(root, "$hash/meta.torrent.tmp")
        val cache = if (useCache) TorrentCacheStore(root, freeBytes = { Long.MAX_VALUE }) else null
        val fake = FakeTorrentEngine().apply {
            files = listOf(TorrentFile(7, "movie.mkv", 100))
            have += 0
            torrentHash = { if (it.contentEquals(bytes)) hash else null }
        }
        val engine = object : TorrentEngine by fake {
            override fun hashOf(magnet: String): String? =
                if (knownHash) magnet.removePrefix("magnet:").lowercase() else null
        }
        val fetched = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        var fetch: suspend (String) -> ByteArray? = { bytes }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = StreamManager(
            engine, StreamServer(scope), files, scope,
            cache = cache,
            storagePrefs = { TorrentStoragePrefs(keepAfterPlayback = true) },
            torrentFiles = if (enableSource) TorrentFileSource { fetched += it; fetch(it) } else null,
            log = { logs += it },
        )

        fun store(data: ByteArray = bytes) {
            stored.parentFile!!.mkdirs()
            stored.writeBytes(data)
        }

        suspend fun ready(title: String = "Film"): StreamState.Ready = withTimeout(5_000) {
            val state = manager.state.first {
                (it is StreamState.Ready && it.title == title) || it is StreamState.Failed
            }
            assertTrue("Expected Ready, was $state", state is StreamState.Ready)
            state as StreamState.Ready
        }

        suspend fun drain() = withTimeout(5_000) {
            while (scope.coroutineContext.job.children.any()) {
                scope.coroutineContext.job.children.toList().forEach { it.join() }
            }
        }

        suspend fun close() {
            manager.stop()
            try { drain() } finally { scope.cancel() }
        }
    }

    @Test fun usesStoredTorrentWithoutFetching() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.store()
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertTrue(f.fetched.isEmpty())
            assertEquals(listOf(f.stored), f.fake.addedFiles)
            assertArrayEquals(bytes, f.stored.readBytes())
            assertEquals(listOf("source=stored host=provider.example"), f.logs.toList())
        } finally { f.close() }
    }

    @Test fun fetchesAndStoresWhenMissing() = runBlocking<Unit> {
        val f = Fixture(useCache = true)
        try {
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertEquals(listOf(url), f.fetched.toList())
            assertEquals(listOf(f.stored), f.fake.addedFiles)
            assertArrayEquals(bytes, f.stored.readBytes())
            assertFalse(f.temporary.exists())
            assertEquals(listOf("source=fetched host=provider.example"), f.logs.toList())
            assertEquals(0L, f.cache!!.clearInactive())
            assertTrue(f.stored.exists())
        } finally { f.close() }
    }

    @Test fun fallsBackToMagnetWhenFetchFails() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.fetch = { null }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertEquals(listOf(magnet), f.fake.added)
            assertEquals(listOf<File?>(null), f.fake.addedFiles)
            assertFalse(f.stored.exists())
            assertEquals(listOf("source=magnet host=provider.example"), f.logs.toList())
        } finally { f.close() }
    }

    @Test fun fallsBackOnHashMismatch() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.fake.torrentHash = { "b".repeat(40) }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertNull(f.fake.addedFiles.single())
            assertFalse(f.stored.exists())
        } finally { f.close() }
    }

    @Test fun replacesCorruptStoredFile() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.store("corrupt".toByteArray())
            f.fetch = { assertFalse(f.stored.exists()); bytes }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertEquals(listOf(url), f.fetched.toList())
            assertArrayEquals(bytes, f.stored.readBytes())
            assertEquals(f.stored, f.fake.addedFiles.single())
        } finally { f.close() }
    }

    @Test fun noUrlUsesMagnet() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.manager.start(magnet, "Film")
            f.ready()
            assertTrue(f.fetched.isEmpty())
            assertNull(f.fake.addedFiles.single())
            assertEquals(listOf("source=magnet"), f.logs.toList())
        } finally { f.close() }
    }

    @Test fun cancelDuringFetchSkipsAdd() = runBlocking<Unit> {
        val f = Fixture(useCache = true)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        try {
            f.fetch = {
                entered.complete(Unit)
                try { awaitCancellation() } catch (e: CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
            }
            f.manager.start(magnet, "Film", url)
            withTimeout(5_000) { entered.await() }
            assertFalse(f.root.exists())
            f.manager.stopIf(f.manager.currentSessionId())
            withTimeout(5_000) { cancelled.await() }
            f.drain()
            assertTrue(f.fake.added.isEmpty())
            assertTrue(f.fake.removed.isEmpty())
            assertFalse(f.stored.exists())
            assertEquals(StreamState.Idle, f.manager.state.value)
        } finally { f.close() }
    }

    @Test fun readyKeyUnchanged() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.manager.start(magnet, "Film", url)
            assertEquals("bt:$hash:7", f.ready().key)
            assertEquals(f.stored, f.fake.addedFiles.single())
        } finally { f.close() }
    }

    @Test fun unknownMagnetHashSkipsTorrentFilePath() = runBlocking<Unit> {
        val f = Fixture(knownHash = false)
        try {
            f.store()
            f.fake.torrentHash = { error("Must not parse") }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertTrue(f.fetched.isEmpty())
            assertNull(f.fake.addedFiles.single())
            assertArrayEquals(bytes, f.stored.readBytes())
        } finally { f.close() }
    }

    @Test fun comparesHashesIgnoringCase() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.fake.torrentHash = { hash.uppercase() }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertEquals(f.stored, f.fake.addedFiles.single())
        } finally { f.close() }
    }

    @Test fun skipsOversizedStoredFileWithoutParsingIt() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.store()
            RandomAccessFile(f.stored, "rw").use { it.setLength(TORRENT_FILE_MAX_BYTES + 1) }
            f.fake.torrentHash = { assertArrayEquals(bytes, it); hash }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertEquals(listOf(url), f.fetched.toList())
            assertArrayEquals(bytes, f.stored.readBytes())
        } finally { f.close() }
    }

    @Test fun invalidFetchedMetadataUsesMagnet() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.fetch = { "<html>blocked</html>".toByteArray() }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertNull(f.fake.addedFiles.single())
            assertFalse(f.stored.exists())
        } finally { f.close() }
    }

    @Test fun acceptsStoredFileAtSizeLimitWithoutUrlOrSource() = runBlocking<Unit> {
        val f = Fixture(enableSource = false)
        try {
            val large = ByteArray(TORRENT_FILE_MAX_BYTES.toInt())
            f.store(large)
            f.fake.torrentHash = { assertArrayEquals(large, it); hash }
            f.manager.start(magnet, "Film")
            f.ready()
            assertEquals(f.stored, f.fake.addedFiles.single())
            assertEquals(TORRENT_FILE_MAX_BYTES, f.stored.length())
            assertEquals(listOf("source=stored"), f.logs.toList())
        } finally { f.close() }
    }

    @Test fun oversizedFetchedMetadataUsesMagnetWithoutParsing() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.fetch = { ByteArray(TORRENT_FILE_MAX_BYTES.toInt() + 1) }
            f.fake.torrentHash = { throw AssertionError("Must not parse oversized metadata") }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertNull(f.fake.addedFiles.single())
            assertFalse(f.stored.exists())
        } finally { f.close() }
    }

    @Test fun missingSourceUsesMagnet() = runBlocking<Unit> {
        val f = Fixture(enableSource = false)
        try {
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertTrue(f.fetched.isEmpty())
            assertNull(f.fake.addedFiles.single())
        } finally { f.close() }
    }

    @Test fun fetchExceptionDoesNotPoisonNextStart() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.fetch = { throw IOException("unavailable") }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertNull(f.fake.addedFiles.single())
            f.fetch = { bytes }
            f.manager.start(magnet, "Second", url)
            f.ready("Second")
            assertEquals(listOf(null, f.stored), f.fake.addedFiles)
        } finally { f.close() }
    }

    @Test fun parserExceptionDeletesStoredFileAndFallsBack() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.store()
            f.fake.torrentHash = { throw IllegalArgumentException("bad bencode") }
            f.fetch = { assertFalse(f.stored.exists()); bytes }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertEquals(listOf(url), f.fetched.toList())
            assertNull(f.fake.addedFiles.single())
            assertFalse(f.stored.exists())
        } finally { f.close() }
    }

    @Test fun writeFailureDoesNotPoisonNextStart() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.temporary.mkdirs()
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertNull(f.fake.addedFiles.single())
            assertEquals(listOf("source=magnet host=provider.example"), f.logs.toList())
            f.temporary.delete()
            f.manager.start(magnet, "Second", url)
            f.ready("Second")
            assertEquals(listOf(null, f.stored), f.fake.addedFiles)
            assertFalse(f.temporary.exists())
        } finally { f.close() }
    }

    @Test fun replacesExistingDestinationWithFetchedBytes() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.fetch = { f.store("stale metadata".toByteArray()); bytes }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertEquals(f.stored, f.fake.addedFiles.single())
            assertArrayEquals(bytes, f.stored.readBytes())
            assertFalse(f.temporary.exists())
        } finally { f.close() }
    }

    @Test fun moveFailureUsesMagnetAndRemovesTemporaryFile() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.stored.mkdirs()
            File(f.stored, "obstacle").writeText("occupied")
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertNull(f.fake.addedFiles.single())
            assertFalse(f.temporary.exists())
            assertEquals("occupied", File(f.stored, "obstacle").readText())
        } finally { f.close() }
    }

    @Test fun storedBytesSurviveCacheInitialization() = runBlocking<Unit> {
        val f = Fixture(useCache = true)
        try {
            f.store()
            val orphan = File(f.stored.parentFile, "orphan").apply { writeText("old") }
            f.manager.start(magnet, "Film", url)
            f.ready()
            assertFalse(orphan.exists())
            assertTrue(f.fetched.isEmpty())
            assertArrayEquals(bytes, f.stored.readBytes())
            assertEquals(f.stored, f.fake.addedFiles.single())
            f.manager.stop()
            f.drain()
            assertTrue(f.stored.exists())
            f.cache!!.clearInactive()
            assertFalse(f.stored.exists())
        } finally { f.close() }
    }

    @Test fun replacingSessionCancelsFetchAndOldStopCannotStopNewSession() = runBlocking<Unit> {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        try {
            f.fetch = {
                entered.complete(Unit)
                try { awaitCancellation() } catch (e: CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
            }
            f.manager.start(magnet, "Film", url)
            val oldId = f.manager.currentSessionId()
            withTimeout(5_000) { entered.await() }
            f.manager.start(magnet, "Second")
            withTimeout(5_000) { cancelled.await() }
            f.ready("Second")
            f.manager.stopIf(oldId)
            assertEquals("Second", (f.manager.state.value as StreamState.Ready).title)
            assertEquals(listOf(magnet), f.fake.added)
            assertTrue(f.fake.removed.isEmpty())
        } finally { f.close() }
    }
}
