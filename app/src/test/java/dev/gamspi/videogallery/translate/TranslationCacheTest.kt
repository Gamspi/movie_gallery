package dev.gamspi.videogallery.translate

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class TranslationCacheTest {
    private lateinit var db: TranslationDatabase
    private var now = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            TranslationDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun cache(limit: Long = TRANSLATION_CACHE_LIMIT_BYTES) = TranslationCache(
        dao = db.dao(),
        tx = { block -> db.withTransaction { block() } },
        limitBytes = limit,
        dispatcher = Dispatchers.Unconfined,
        clock = { now },
    )

    private fun key(file: Long = 1, version: String = "v1") = CacheKey(file, TranslateLang.RU, version)

    @Test
    fun putThenGetReturnsSameIndices() = runTest {
        val c = cache()
        val cues = mapOf(0 to "привет", 5 to "мир", 9 to "ёж")
        c.put(key(), cues)
        assertEquals(cues, c.get(key()))
    }

    @Test
    fun getOnMissIsEmpty() = runTest {
        assertTrue(cache().get(key()).isEmpty())
    }

    @Test
    fun otherModelVersionOrTargetIsMiss() = runTest {
        val c = cache()
        c.put(key(), mapOf(0 to "a"))
        assertTrue(c.get(key(version = "v2")).isEmpty())
        assertTrue(c.get(CacheKey(1, TranslateLang.EN, "v1")).isEmpty())
    }

    @Test
    fun putReplacesPreviousCuesOfSameKey() = runTest {
        val c = cache()
        c.put(key(), mapOf(0 to "a", 1 to "b"))
        c.put(key(), mapOf(2 to "c"))
        assertEquals(mapOf(2 to "c"), c.get(key()))
    }

    @Test
    fun getUpdatesLastUsed() = runTest {
        val c = cache()
        c.put(key(), mapOf(0 to "a"))
        assertEquals(1_000L, db.dao().entry(1, TranslateLang.RU, "v1")!!.lastUsedMs)
        now = 5_000L
        c.get(key())
        assertEquals(5_000L, db.dao().entry(1, TranslateLang.RU, "v1")!!.lastUsedMs)
    }

    @Test
    fun evictsLeastRecentlyUsedWhenOverLimitButKeepsCurrent() = runTest {
        val c = cache(limit = 1024)
        val big = mapOf(0 to "x".repeat(400))
        now = 1; c.put(key(1), big)
        now = 2; c.put(key(2), big)
        now = 3; c.get(key(1)) // file 2 is now the oldest
        now = 4; c.put(key(3), big)
        assertTrue(c.get(key(2)).isEmpty())
        assertEquals(big, c.get(key(1)))
        assertEquals(big, c.get(key(3)))
        assertEquals(0, db.dao().cueCount(2, TranslateLang.RU, "v1"))
    }

    @Test
    fun currentEntryKeptEvenIfItAloneExceedsLimit() = runTest {
        val c = cache(limit = 100)
        val big = mapOf(0 to "y".repeat(500))
        c.put(key(), big)
        assertEquals(big, c.get(key()))
    }

    @Test
    fun bytesAreUtf8Length() = runTest {
        cache().put(key(), mapOf(0 to "ёж", 1 to "ab"))
        assertEquals(6, db.dao().entry(1, TranslateLang.RU, "v1")!!.bytes)
    }

    @Test
    fun clearRemovesEverything() = runTest {
        val c = cache()
        c.put(key(1), mapOf(0 to "a"))
        c.put(key(2), mapOf(0 to "b"))
        c.clear()
        assertTrue(c.get(key(1)).isEmpty())
        assertTrue(c.get(key(2)).isEmpty())
        assertEquals(0L, db.dao().totalBytes())
    }

    private fun throwingDao(error: () -> Throwable): TranslationDao =
        java.lang.reflect.Proxy.newProxyInstance(
            TranslationDao::class.java.classLoader,
            arrayOf(TranslationDao::class.java),
        ) { _, _, _ -> throw error() } as TranslationDao

    @Test
    fun daoFailureInGetGivesEmptyAndInPutIsSwallowed() = runTest {
        val c = TranslationCache(
            dao = throwingDao { IOException("boom") },
            tx = { block -> block() },
            limitBytes = 1024,
            dispatcher = Dispatchers.Unconfined,
            clock = { now },
        )
        assertTrue(c.get(key()).isEmpty())
        c.put(key(), mapOf(0 to "a")) // must not throw
        c.clear() // must not throw
    }

    @Test
    fun cancellationFromDaoIsRethrown() = runTest {
        val c = TranslationCache(
            dao = throwingDao { CancellationException("cancelled") },
            tx = { block -> block() },
            limitBytes = 1024,
            dispatcher = Dispatchers.Unconfined,
            clock = { now },
        )
        for (op in listOf<suspend () -> Unit>({ c.get(key()) }, { c.put(key(), mapOf(0 to "a")) }, { c.clear() })) {
            try {
                op()
                fail("expected CancellationException")
            } catch (_: CancellationException) {
            }
        }
    }

    @Test
    fun cancellationIsRethrown() = runTest {
        val c = TranslationCache(
            dao = db.dao(),
            tx = { throw CancellationException("cancelled") },
            limitBytes = 1024,
            dispatcher = Dispatchers.Unconfined,
            clock = { now },
        )
        try {
            c.put(key(), mapOf(0 to "a"))
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        val failing = TranslationCache(
            dao = db.dao(),
            tx = { throw IOException("boom") },
            limitBytes = 1024,
            dispatcher = Dispatchers.Unconfined,
            clock = { now },
        )
        failing.put(key(), mapOf(0 to "a")) // IOException swallowed
    }
}
