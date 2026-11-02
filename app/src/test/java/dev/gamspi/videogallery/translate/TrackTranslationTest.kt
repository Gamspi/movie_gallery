package dev.gamspi.videogallery.translate

import dev.gamspi.videogallery.subs.SubtitleCue
import dev.gamspi.videogallery.subs.SubtitleTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackTranslationTest {
    private val key = CacheKey(7, TranslateLang.RU, "fake-1")
    private val fake = FakeSubtitleTranslator()
    private val dao = InMemoryTranslationDao()
    private val cache = TranslationCache(dao = dao, tx = { it() }, dispatcher = Dispatchers.Unconfined)

    /** Cue i starts at i seconds and has text "c<i>". */
    private fun track(n: Int) = SubtitleTrack((0 until n).map { SubtitleCue(it * 1000L, it * 1000L + 900, "c$it") })

    private fun TestScope.start(track: SubtitleTrack, positionMs: Long = 0) = TrackTranslation(
        track = track,
        key = key,
        from = TranslateLang.EN,
        translator = fake,
        cache = cache,
        scope = this,
        startPositionMs = positionMs,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun texts(t: TrackTranslation) = t.translated.value.cues.map { it.text }

    @Test
    fun startsAtPositionThenTailThenBeginning() = runTest {
        val t = start(track(100), positionMs = 60_000)
        advanceUntilIdle()
        assertEquals(((60 until 100) + (0 until 60)).map { "c$it" }, fake.texts)
        assertEquals(100, t.translated.value.cues.size)
        assertFalse(t.failed.value)
        assertTrue(fake.calls.all { it.second == TranslateLang.EN && it.third == TranslateLang.RU })
    }

    @Test
    fun translatedCuesKeepOriginalTimings() = runTest {
        val t = start(track(3))
        advanceUntilIdle()
        assertEquals(
            listOf(SubtitleCue(0, 900, "[c0]"), SubtitleCue(1000, 1900, "[c1]"), SubtitleCue(2000, 2900, "[c2]")),
            t.translated.value.cues,
        )
    }

    @Test
    fun seekAfterFirstBatchReordersWithoutRepeats() = runTest {
        lateinit var t: TrackTranslation
        fake.onCall = { if (it == "c79") t.seek(10_000) }
        t = start(track(100), positionMs = 60_000)
        advanceUntilIdle()
        val expected = (60 until 80) + (10 until 60) + (80 until 100) + (0 until 10)
        assertEquals(expected.map { "c$it" }, fake.texts)
        assertEquals(100, fake.calls.size)
        assertEquals(100, t.translated.value.cues.size)
    }

    @Test
    fun cachedCuesAreShownImmediatelyAndNotTranslated() = runTest {
        val cached = mapOf(0 to "к0", 1 to "к1", 25 to "к25")
        cache.put(key, cached)
        dao.inserts = 0
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { gate.await() }
        val t = start(track(45))
        runCurrent()
        assertEquals(listOf("к0", "к1", "к25"), texts(t))
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(fake.texts.any { it in setOf("c0", "c1", "c25") })
        assertEquals(42, fake.calls.size)
        // 42 new cues -> 3 batches, each put carries the full accumulated map.
        assertEquals(3, dao.inserts)
        val stored = cache.get(key)
        assertEquals(45, stored.size)
        assertEquals("к25", stored[25])
        assertEquals("[c44]", stored[44])
    }

    @Test
    fun blankTextSkippedAndMultilineTranslatedPerLine() = runTest {
        val tr = SubtitleTrack(
            listOf(
                SubtitleCue(0, 900, ""),
                SubtitleCue(1000, 1900, "   "),
                SubtitleCue(2000, 2900, "a\nb"),
                SubtitleCue(3000, 3900, "c"),
            ),
        )
        val t = start(tr)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), fake.texts)
        assertEquals(listOf("[a]\n[b]", "[c]"), texts(t))
    }

    @Test
    fun singleFailureSkipsCue() = runTest {
        fake.failWhen = { it == "c1" }
        val t = start(track(3))
        advanceUntilIdle()
        assertEquals(listOf("[c0]", "[c2]"), texts(t))
        assertFalse(t.failed.value)
        assertEquals(3, fake.calls.size)
    }

    @Test
    fun fourFailuresInRowDoNotStop() = runTest {
        fake.failWhen = { it in setOf("c3", "c4", "c5", "c6") }
        val t = start(track(30))
        advanceUntilIdle()
        assertFalse(t.failed.value)
        assertEquals(26, t.translated.value.cues.size)
    }

    @Test
    fun fiveFailuresInRowStopWork() = runTest {
        fake.failWhen = { it in setOf("c3", "c4", "c5", "c6", "c7") }
        val t = start(track(30))
        advanceUntilIdle()
        assertTrue(t.failed.value)
        assertEquals(8, fake.calls.size)
        assertEquals(listOf("[c0]", "[c1]", "[c2]"), texts(t))
        assertEquals(3, cache.get(key).size)
    }

    @Test
    fun cancelStopsCallsAndIsNotFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { if (it == "c2") gate.await() }
        val t = start(track(30))
        runCurrent()
        assertEquals(3, fake.calls.size)
        t.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, fake.calls.size)
        assertFalse(t.failed.value)
    }

    private fun TestScope.runCurrent() = testScheduler.runCurrent()
}

/** Minimal in-memory DAO; counts insertCues calls. */
private class InMemoryTranslationDao : TranslationDao {
    private val cues = mutableMapOf<Triple<Long, TranslateLang, String>, List<TranslatedCueEntity>>()
    private val entries = mutableMapOf<Triple<Long, TranslateLang, String>, CacheEntryEntity>()
    var inserts = 0

    override suspend fun cues(fileId: Long, target: TranslateLang, version: String) =
        cues[Triple(fileId, target, version)].orEmpty()

    override suspend fun cueCount(fileId: Long, target: TranslateLang, version: String) =
        cues(fileId, target, version).size

    override suspend fun entry(fileId: Long, target: TranslateLang, version: String) =
        entries[Triple(fileId, target, version)]

    override suspend fun touch(fileId: Long, target: TranslateLang, version: String, now: Long) {}

    override suspend fun insertCues(cues: List<TranslatedCueEntity>) {
        inserts++
        cues.groupBy { Triple(it.fileId, it.target, it.modelVersion) }.forEach { (k, v) ->
            this.cues[k] = this.cues[k].orEmpty().filter { old -> v.none { it.cueIndex == old.cueIndex } } + v
        }
    }

    override suspend fun upsertEntry(entry: CacheEntryEntity) {
        entries[Triple(entry.fileId, entry.target, entry.modelVersion)] = entry
    }

    override suspend fun deleteCues(fileId: Long, target: TranslateLang, version: String) {
        cues.remove(Triple(fileId, target, version))
    }

    override suspend fun deleteEntry(fileId: Long, target: TranslateLang, version: String) {
        entries.remove(Triple(fileId, target, version))
    }

    override suspend fun totalBytes() = entries.values.sumOf { it.bytes }

    override suspend fun oldestExcept(fileId: Long, target: TranslateLang, version: String) =
        entries.values.filter { it.fileId != fileId || it.target != target || it.modelVersion != version }
            .minByOrNull { it.lastUsedMs }

    override suspend fun clearCues() = cues.clear()

    override suspend fun clearEntries() = entries.clear()
}
