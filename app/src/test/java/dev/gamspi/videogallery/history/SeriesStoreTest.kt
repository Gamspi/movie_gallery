package dev.gamspi.videogallery.history

import dev.gamspi.videogallery.stream.EpisodeFile
import dev.gamspi.videogallery.stream.EpisodeRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesStoreTest {
    private val key = seriesKey("Tmdb", 7)
    private val release = SeasonRelease("magnet:x", "https://t/x.torrent", "Сезон", listOf(
        EpisodeFile(EpisodeRef(1, 1), 4), EpisodeFile(EpisodeRef(1, 2), 8),
    ))

    @Test fun keyIncludesSourceKindAndId() {
        assertEquals("Tmdb:Series:7", key)
        assertNotEquals(key, seriesKey("Kinopoisk", 7))
    }

    @Test fun releasesAndProgressSurviveRecreation() = runTest {
        var saved: String? = null
        var reads = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        fun create() = PersistentSeriesStore({ reads++; saved }, { saved = it }, this, dispatcher)
        val store = create()
        assertEquals(0, reads)
        assertNull(store.release(key, 1))
        store.saveRelease(key, 1, release)
        store.saveProgress(key, EpisodeRef(1, 1), 0.25f)
        store.saveProgress(key, EpisodeRef(1, 2), 0.75f)
        store.saveProgress(key, EpisodeRef(1, 1), 0.5f)
        assertNull(saved)
        advanceUntilIdle()
        assertEquals(release, store.release(key, 1))
        val reopened = create()
        advanceUntilIdle()
        assertEquals(release, reopened.release(key, 1))
        assertEquals(mapOf(EpisodeRef(1, 1) to 0.5f, EpisodeRef(1, 2) to 0.75f), reopened.progress(key))
        assertTrue(reopened.progress(seriesKey("Kinopoisk", 7)).isEmpty())
        assertNull(reopened.release(key, 2))
    }

    @Test fun multiSeasonPackIsSavedUnderEachCoveredSeason() = runTest {
        var saved: String? = null
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PersistentSeriesStore({ saved }, { saved = it }, this, dispatcher)
        val pack = release.copy(files = release.files + EpisodeFile(EpisodeRef(3, 2), 12))
        store.saveRelease(key, 1, pack)
        advanceUntilIdle()
        assertEquals(pack, store.release(key, 1))
        assertEquals(pack, store.release(key, 3))
        assertNull(store.release(key, 2))
        val reopened = PersistentSeriesStore({ saved }, {}, this, dispatcher)
        advanceUntilIdle()
        assertEquals(pack, reopened.release(key, 1))
        assertEquals(pack, reopened.release(key, 3))
        store.saveRelease(key, 3, release.copy(files = emptyList()))
        advanceUntilIdle()
        assertEquals(pack, store.release(key, 1))
        assertEquals(emptyList<EpisodeFile>(), store.release(key, 3)!!.files)
    }

    @Test fun queuedMutationPreservesLoadedDataAndOtherSources() = runTest {
        var saved: String? = null
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = PersistentSeriesStore({ saved }, { saved = it }, this, dispatcher)
        first.saveRelease(key, 1, release)
        advanceUntilIdle()
        val next = PersistentSeriesStore({ saved }, { saved = it }, this, dispatcher)
        val other = seriesKey("Kinopoisk", 7)
        next.saveProgress(key, EpisodeRef(1, 1), 0.5f)
        next.saveRelease(other, 1, release.copy(title = "Другой каталог"))
        advanceUntilIdle()
        assertEquals(release, next.release(key, 1))
        assertEquals("Другой каталог", next.release(other, 1)!!.title)
        assertEquals(mapOf(EpisodeRef(1, 1) to 0.5f), next.progress(key))
    }

    @Test fun malformedDataAndWriteFailureKeepStoreUsable() = runTest {
        val store = PersistentSeriesStore({ "broken" }, { throw IllegalStateException("disk") }, this,
            StandardTestDispatcher(testScheduler))
        store.saveRelease(key, 1, release)
        store.saveProgress(key, EpisodeRef(1, 1), 2f)
        advanceUntilIdle()
        assertEquals(release, store.release(key, 1))
        assertEquals(mapOf(EpisodeRef(1, 1) to 1f), store.progress(key))
    }

    @Test fun cancelledReadDoesNotBecomeAnEmptyLoadedStore() = runTest {
        var reads = 0
        var writes = 0
        val store = PersistentSeriesStore(
            { reads++; throw CancellationException("cancelled") }, { writes++ }, this,
            StandardTestDispatcher(testScheduler),
        )
        store.saveRelease(key, 1, release)
        advanceUntilIdle()
        assertEquals(2, reads)
        assertEquals(0, writes)
        assertNull(store.release(key, 1))
    }
}
