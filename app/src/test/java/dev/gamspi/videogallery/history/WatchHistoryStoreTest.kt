package dev.gamspi.videogallery.history

import dev.gamspi.videogallery.catalog.model.MediaKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchHistoryStoreTest {
    @Test fun legacyJsonWithoutNewFieldsDecodes() {
        val legacy = """[{"movieId":7,"title":"Фильм","positionKey":"bt:x:0","positionMs":1000,"durationMs":10000,"updatedAt":1}]"""
        val decoded = decodeHistory(legacy).single()
        assertEquals(MediaKind.Movie, decoded.kind)
        assertNull(decoded.source)
        assertNull(decoded.season)
        assertEquals("-:Movie:7", decoded.identity)
    }

    @Test fun movieAndSeriesWithSameIdCoexist() {
        val movie = entry(7).copy(source = "Tmdb")
        val series = movie.copy(kind = MediaKind.Series, season = 1, episode = 1)
        assertEquals(setOf(movie, series), listOf(movie).upserting(series).toSet())
        assertEquals("Tmdb:Series:7", series.identity)
    }

    @Test fun seriesUpsertReplacesEpisodeOfSameSeries() {
        val first = entry(7).copy(kind = MediaKind.Series, source = "Tmdb", season = 1, episode = 1)
        val next = first.copy(episode = 2, updatedAt = 100, seasonEpisodes = 8, seasonCount = 3)
        assertEquals(listOf(next), listOf(first).upserting(next))
        assertEquals(listOf(next), decodeHistory(encodeHistory(listOf(next))))
    }

    @Test fun sourcesAreDistinctAndLegacyMatchesEitherDirection() {
        val legacy = entry(7)
        val tmdb = legacy.copy(source = "Tmdb")
        val kp = legacy.copy(source = "Kinopoisk")
        assertEquals(setOf(tmdb, kp), listOf(tmdb).upserting(kp).toSet())
        assertEquals(listOf(tmdb), listOf(legacy).upserting(tmdb))
        assertEquals(listOf(legacy), listOf(tmdb).upserting(legacy))
        assertEquals(listOf(kp), listOf(tmdb, kp).upserting(tmdb.copy(positionMs = 9_000)))
    }

    @Test fun removeUsesIdentityAndLegacyWildcard() = runTest {
        var persisted: String? = null
        fun create() = PersistentWatchHistoryStore(
            { persisted }, { persisted = it }, this, StandardTestDispatcher(testScheduler),
        )
        val movie = entry(7).copy(source = "Tmdb")
        val series = movie.copy(kind = MediaKind.Series)
        val kp = movie.copy(source = "Kinopoisk")
        val s = create()
        listOf(movie, series, kp).forEach(s::upsert)
        advanceUntilIdle()
        val reopened = create()
        advanceUntilIdle()
        assertEquals(setOf(movie, series, kp), reopened.entries.value.toSet())
        reopened.remove(movie.identity)
        advanceUntilIdle()
        assertEquals(setOf(series, kp), reopened.entries.value.toSet())
        reopened.remove(entry(7).identity)
        advanceUntilIdle()
        assertEquals(listOf(series), reopened.entries.value)
        reopened.upsert(entry(8))
        reopened.remove(entry(8).copy(source = "Tmdb").identity)
        advanceUntilIdle()
        assertEquals(listOf(series), reopened.entries.value)
    }

    @Test fun awaitLoadedReturnsPersistedEntries() = runTest {
        val saved = encodeHistory(listOf(entry(7)))
        val store = PersistentWatchHistoryStore({ saved }, {}, this, StandardTestDispatcher(testScheduler))
        assertTrue(store.entries.value.isEmpty())
        store.awaitLoaded()
        assertEquals(listOf(entry(7)), store.entries.value)
    }

    private fun entry(id: Int, pos: Long = 1_000, dur: Long = 10_000, at: Long = id.toLong()) = WatchEntry(
        movieId = id, title = "Фильм $id", year = 2000 + id, posterUrl = "p$id", rating = 7.5,
        magnet = "magnet:$id", torrentUrl = "", imdb = "tt$id", positionKey = "bt:$id:0",
        positionMs = pos, durationMs = dur, updatedAt = at,
    )

    @Test fun progress_isPositionOverDuration_clamped() {
        assertEquals(0.5f, entry(1, pos = 5_000).progress, 1e-6f)
        assertEquals(1f, entry(1, pos = 20_000).progress, 1e-6f)
        assertEquals(0f, entry(1, dur = 0).progress, 1e-6f)
    }

    @Test fun upsert_replacesByMovieId_freshFirst() {
        val list = emptyList<WatchEntry>().upserting(entry(1, at = 1)).upserting(entry(2, at = 2))
            .upserting(entry(1, pos = 3_000, at = 3))
        assertEquals(listOf(1, 2), list.map { it.movieId })
        assertEquals(3_000L, list.first().positionMs)
    }

    @Test fun upsert_finishedRemoves() {
        val list = listOf(entry(1)).upserting(entry(1, pos = 9_000, at = 5))
        assertTrue(list.isEmpty())
        assertTrue(emptyList<WatchEntry>().upserting(entry(2, pos = 9_500)).isEmpty())
    }

    @Test fun upsert_withoutDuration_isIgnored() {
        val start = listOf(entry(1))
        assertEquals(start, start.upserting(entry(1, dur = 0, at = 9)))
        assertTrue(emptyList<WatchEntry>().upserting(entry(2, dur = -1)).isEmpty())
    }

    @Test fun upsert_keepsAtMost20_dropsOldest() {
        var list = emptyList<WatchEntry>()
        for (i in 1..25) list = list.upserting(entry(i, at = i.toLong()))
        assertEquals(20, list.size)
        assertEquals((25 downTo 6).toList(), list.map { it.movieId })
    }

    @Test fun codec_roundTrip_andCorruptedIsEmpty() {
        val list = listOf(entry(2), entry(1))
        assertEquals(list, decodeHistory(encodeHistory(list)))
        assertTrue(decodeHistory(null).isEmpty())
        assertTrue(decodeHistory("{not json").isEmpty())
        assertTrue(decodeHistory("""{"a":1}""").isEmpty())
        // Invalid items are dropped, valid ones survive; unknown keys are tolerated.
        val mixed = """[{"movieId":0,"title":"x","positionKey":"k","positionMs":1,"durationMs":2,"updatedAt":1},
            {"movieId":5,"title":"Ок","positionKey":"k","positionMs":1,"durationMs":2,"updatedAt":1,"extra":true}]"""
        assertEquals(listOf(5), decodeHistory(mixed).map { it.movieId })
    }

    private fun TestScope.store(initial: String?, saved: MutableList<String>) = PersistentWatchHistoryStore(
        read = { initial }, write = { saved += it },
        scope = backgroundScope, dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test fun store_loadsPersistsAndIsTolerant() = runTest {
        val saved = mutableListOf<String>()
        val s = store("garbage", saved)
        advanceUntilIdle()
        assertTrue(s.entries.value.isEmpty())
        s.upsert(entry(1, at = 1))
        s.upsert(entry(2, at = 2))
        advanceUntilIdle()
        assertEquals(listOf(2, 1), s.entries.value.map { it.movieId })
        assertEquals(listOf(2, 1), decodeHistory(saved.last()).map { it.movieId })
        s.upsert(entry(2, pos = 9_900, at = 3))
        advanceUntilIdle()
        assertEquals(listOf(1), s.entries.value.map { it.movieId })
        s.remove(entry(1).identity)
        advanceUntilIdle()
        assertTrue(s.entries.value.isEmpty())
        assertNull(s.entries.value.firstOrNull())
    }

    @Test fun store_readsExisting() = runTest {
        val s = store(encodeHistory(listOf(entry(3, at = 3), entry(4, at = 4))), mutableListOf())
        advanceUntilIdle()
        assertEquals(listOf(4, 3), s.entries.value.map { it.movieId })
    }

    @Test fun store_readFailure_startsEmpty() = runTest {
        val s = PersistentWatchHistoryStore(
            read = { throw ClassCastException("not a string") }, write = {},
            scope = backgroundScope, dispatcher = StandardTestDispatcher(testScheduler),
        )
        s.upsert(entry(1))
        advanceUntilIdle()
        assertEquals(listOf(1), s.entries.value.map { it.movieId })
    }
}
