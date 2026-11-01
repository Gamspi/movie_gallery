package dev.gamspi.videogallery.stream

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TorrentCacheStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)
    private val limit = 10_000L
    private fun store(root: File, free: () -> Long = { Long.MAX_VALUE }, now: () -> Long = { 10L }) =
        TorrentCacheStore(root, free, now)

    private suspend fun retain(store: TorrentCacheStore, root: File, hash: String, size: Int) {
        store.acquire(hash)
        store.select(hash, 0, "movie.mkv", size.toLong(), limit)
        File(root, "$hash/movie.mkv").writeBytes(ByteArray(size))
        store.release(hash, true, limit)
    }

    @Test fun retained_files_survive_restart_but_unknown_crash_files_are_removed() = runTest {
        val root = temp.newFolder()
        retain(store(root), root, a, 100)
        File(root, "$b/crash.mkv").apply { requireNotNull(parentFile).mkdirs(); writeText("crash") }
        val restarted = store(root)
        restarted.initialize()
        assertTrue(File(root, "$a/movie.mkv").exists())
        assertFalse(File(root, b).exists())
        assertEquals(100L, restarted.usedBytes())
    }

    @Test fun default_delete_removes_previously_retained_hash_too() = runTest {
        val root = temp.newFolder()
        val cache = store(root)
        retain(cache, root, a, 100)
        cache.acquire(a)
        cache.release(a, false, limit)
        assertFalse(File(root, a).exists())
        assertEquals(0L, store(root).usedBytes())
    }

    @Test fun clear_protects_active_hash_and_returns_deleted_payload_size() = runTest {
        val root = temp.newFolder()
        val cache = store(root)
        retain(cache, root, a, 100)
        retain(cache, root, b, 200)
        cache.acquire(a)
        assertEquals(200L, cache.clearInactive())
        assertTrue(File(root, "$a/movie.mkv").exists())
        assertFalse(File(root, b).exists())
        cache.release(a, true, limit)
        assertEquals(100L, cache.clearInactive())
    }

    @Test fun limit_evicts_least_recent_inactive_entry() = runTest {
        val root = temp.newFolder()
        var clock = 1L
        val cache = store(root, now = { clock++ })
        retain(cache, root, a, 100)
        retain(cache, root, b, 100)
        cache.acquire(c)
        cache.select(c, 0, "movie.mkv", 100, 200)
        assertFalse(File(root, a).exists())
        assertTrue(File(root, b).exists())
        assertEquals(200L, cache.usedBytes())
    }

    @Test fun existing_hash_reservations_are_not_double_counted_and_another_file_adds_to_total() = runTest {
        val root = temp.newFolder()
        val cache = store(root)
        retain(cache, root, a, 100)
        cache.acquire(a)
        cache.select(a, 0, "movie.mkv", 100, 200)
        assertEquals(100L, cache.usedBytes())
        cache.select(a, 1, "bonus.mkv", 80, 200)
        assertEquals(180L, cache.usedBytes())
    }

    @Test fun logical_size_is_reserved_before_sparse_file_exists() = runTest {
        val root = temp.newFolder()
        val cache = store(root)
        cache.acquire(a)
        cache.select(a, 0, "movie.mkv", 500, 500)
        assertEquals(500L, cache.usedBytes())
        expectStorageFailure { cache.select(a, 1, "second.mkv", 1, 500) }
        assertEquals(500L, cache.usedBytes())
        assertTrue(File(root, a).exists())
    }

    @Test fun impossible_active_reservation_does_not_evict_older_files() = runTest {
        val root = temp.newFolder()
        val cache = store(root)
        retain(cache, root, a, 100)
        cache.acquire(b)
        expectStorageFailure { cache.select(b, 0, "movie.mkv", 501, 500) }
        assertTrue(File(root, "$a/movie.mkv").exists())
    }

    @Test fun free_space_requires_full_new_selection_and_reserve() = runTest {
        val root = temp.newFolder()
        val cache = store(root, free = { TorrentCacheStore.MIN_FREE_BYTES + 99 })
        cache.acquire(a)
        expectStorageFailure { cache.select(a, 0, "movie.mkv", 100, limit) }
        assertEquals(0L, cache.usedBytes())
    }

    @Test fun periodic_space_check_detects_disk_exhaustion_without_removing_active_files() = runTest {
        val root = temp.newFolder()
        var free = Long.MAX_VALUE
        val cache = store(root, free = { free })
        cache.acquire(a)
        cache.select(a, 0, "movie.mkv", 100, limit)
        File(root, "$a/movie.mkv").writeText("active")
        free = TorrentCacheStore.MIN_FREE_BYTES - 1
        expectStorageFailure { cache.checkSpace(a, limit) }
        assertTrue(File(root, "$a/movie.mkv").exists())
    }

    @Test fun free_space_eviction_uses_only_inactive_hashes() = runTest {
        val root = temp.newFolder()
        var low = false
        val cache = store(root, free = {
            if (low && File(root, a).exists()) 0 else Long.MAX_VALUE
        })
        retain(cache, root, a, 100)
        cache.acquire(b)
        low = true
        cache.select(b, 0, "movie.mkv", 100, limit)
        assertFalse(File(root, a).exists())
        assertTrue(File(root, b).exists())
    }

    @Test fun duplicate_acquire_is_rejected_until_release() = runTest {
        val root = temp.newFolder()
        val cache = store(root)
        cache.acquire(a)
        expectStorageFailure { cache.acquire(a) }
        cache.release(a, false, limit)
        cache.acquire(a)
    }

    @Test fun invalid_hash_and_paths_cannot_escape_cache() = runTest {
        val root = temp.newFolder()
        val outside = temp.newFile("outside.mkv").apply { writeText("safe") }
        val cache = store(root)
        expectStorageFailure { cache.acquire("../outside") }
        cache.acquire(a)
        expectStorageFailure { cache.select(a, 0, "../../outside.mkv", 1, limit) }
        expectStorageFailure { cache.select(a, 0, outside.absolutePath, 1, limit) }
        assertEquals("safe", outside.readText())
    }

    @Test fun malformed_index_does_not_preserve_unmarked_files() = runTest {
        val root = temp.newFolder()
        File(root, ".cache-index.json").writeText("{invalid")
        File(root, "$a/movie.mkv").apply { requireNotNull(parentFile).mkdirs(); writeText("crash") }
        store(root).initialize()
        assertFalse(File(root, a).exists())
    }

    @Test fun unknown_symbolic_link_is_deleted_without_touching_target() = runTest {
        val root = temp.newFolder()
        val outside = temp.newFolder()
        val target = File(outside, "safe.mkv").apply { writeText("safe") }
        Files.createSymbolicLink(File(root, a).toPath(), outside.toPath())
        store(root).initialize()
        assertEquals("safe", target.readText())
        assertFalse(Files.exists(File(root, a).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun directory_io_error_is_reported_as_storage_failure() = runTest {
        val notADirectory = temp.newFile()
        expectStorageFailure { store(notADirectory).initialize() }
    }

    @Test fun failed_index_write_does_not_delete_payload_or_release_another_active_hash() = runTest {
        val root = temp.newFolder()
        val cache = store(root)
        cache.acquire(a)
        cache.select(a, 0, "movie.mkv", 100, limit)
        File(root, "$a/movie.mkv").writeText("safe")
        cache.acquire(b)
        cache.select(b, 0, "movie.mkv", 100, limit)
        File(root, ".cache-index.tmp").mkdir()
        expectStorageFailure { cache.release(a, true, limit) }
        assertEquals("safe", File(root, "$a/movie.mkv").readText())
        expectStorageFailure { cache.acquire(b) }
    }

    @Test fun cancellation_while_waiting_for_mutex_does_not_acquire_a_hash() = runBlocking {
        val root = temp.newFolder()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val cache = store(root, free = {
            entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
            Long.MAX_VALUE
        })
        cache.acquire(a)
        val selection = async(Dispatchers.Default) { cache.select(a, 0, "movie.mkv", 100, limit) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val waiting = async(Dispatchers.Default) { cache.acquire(b) }
            waiting.cancelAndJoin()
            assertFalse(File(root, b).exists())
        } finally {
            unblock.countDown()
        }
        selection.await()
        cache.acquire(b)
    }

    private suspend fun expectStorageFailure(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected TorrentStorageException")
        } catch (_: TorrentStorageException) {
            // Expected domain failure.
        }
    }
}
