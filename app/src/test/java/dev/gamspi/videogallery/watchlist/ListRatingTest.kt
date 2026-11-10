package dev.gamspi.videogallery.watchlist

import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.details.episodeFraction
import dev.gamspi.videogallery.details.releasedEpisodes
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ListRatingTest {
    private val seed = ListRecord(ListTable.Titles, "kp-1", 10, data = buildJsonObject {
        put("status", "unwatched"); put("type", "tv_show")
    })
    private fun TestScope.store() = LocalListStore({ null }, {}, StandardTestDispatcher(testScheduler))
    private fun ListSnapshot.title() = records.getValue(seed.identity)
    private fun ListRecord.rating() = data.getValue("my_rating").jsonPrimitive.int

    @Test fun averageIsRoundedAndRecalculated() = runTest {
        val store = store()
        assertEquals(8, store.rateSeason(seed, 1, 8).title().rating())
        assertEquals(9, store.rateSeason(seed.copy(updatedAt = 20), 2, 9).title().rating()) // 8.5 rounds up
        assertEquals(7, store.rateSeason(seed.copy(updatedAt = 30), 2, 5).title().rating()) // overwrite: 6.5
    }

    @Test fun boundsAreEnforcedWithoutWriting() = runTest {
        val store = store()
        for ((season, rating) in listOf(1 to 0, 1 to 11, -1 to 5, 10000 to 5)) {
            try { store.rateSeason(seed, season, rating); fail("accepted $season/$rating") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(store.snapshot().records.isEmpty())
        store.rateSeason(seed, 0, 1); store.rateSeason(seed.copy(updatedAt = 20), 9999, 10)
        assertEquals(setOf("0", "9999"), seasonRatings(store.snapshot().title().data).keys)
    }

    @Test fun statusStaysForListedAndIsWatchingForNewTitle() = runTest {
        val store = store()
        assertEquals("watching", store.rateSeason(seed, 1, 5).title().text("status"))
        store.edit(seed.copy(updatedAt = 20, data = buildJsonObject { put("status", "dropped"); put("type", "tv_show"); put("notify", false) }))
        val row = store.rateSeason(seed.copy(updatedAt = 30), 1, 6).title()
        assertEquals("dropped", row.text("status"))
        assertEquals("false", row.text("notify"))
    }

    @Test fun oldRecordAndDamagedScoresAreCompatible() = runTest {
        val store = store()
        store.edit(seed.copy(data = buildJsonObject {
            put("status", "watching"); put("my_rating", 3)
            putJsonObject("season_ratings") { put("x", 5); put("2", 40); put("3", 4) }
        }))
        assertEquals(6, store.rateSeason(seed.copy(updatedAt = 20), 1, 8).title().rating())
    }

    @Test fun revisionIsQueuedAndWinsOverLocalClockRegression() = runTest {
        val store = store()
        store.rateSeason(seed.copy(updatedAt = 100), 1, 5)
        val row = store.rateSeason(seed.copy(updatedAt = 50), 2, 5).title()
        assertEquals(101, row.updatedAt)
        assertEquals(row, store.snapshot().pending[row.identity])
    }

    @Test fun missingRatingRules() {
        assertTrue(ratingMissing(null, null))
        val film = seed.copy(data = buildJsonObject { put("my_rating", 7) })
        assertFalse(ratingMissing(film, null))
        val series = seed.copy(data = buildJsonObject { putJsonObject("season_ratings") { put("1", 7) } })
        assertFalse(ratingMissing(series, 1))
        assertTrue(ratingMissing(series, 2))
    }

    @Test fun marksCreateTitleAndCommitTogether() = runTest {
        val store = store()
        val snapshot = store.markEpisodes(seed, listOf(1 to 1, 1 to 2), true)
        assertEquals("watching", snapshot.title().text("status"))
        assertEquals(3, snapshot.pending.size)
        val listed = store.markEpisodes(seed.copy(updatedAt = 20), listOf(1 to 1), false)
        val row = listed.records.getValue("Episodes:kp-1:1:1")
        assertFalse(row.watched); assertEquals(20, row.updatedAt)
        assertEquals("watching", listed.title().text("status"))
    }

    @Test fun clearedMarkBeatsLocalFinishedProgress() {
        assertEquals(0f, episodeFraction(0.95f, false))
        assertEquals(0.4f, episodeFraction(0.4f, false))
        assertEquals(0f, episodeFraction(null, false))
        assertEquals(1f, episodeFraction(0.2f, true))
    }

    @Test fun futureEpisodesAreNotMarked() {
        fun ep(n: Int, date: String?) = Episode(1, n, "", null, null, date)
        val list = listOf(ep(1, "2026-01-01"), ep(2, "2026-11-09T00:00:00.000Z"), ep(3, "2026-11-10"), ep(4, null))
        assertEquals(listOf(1, 2, 4), releasedEpisodes(list, "2026-11-09").map { it.number })
    }

    @Test fun defaultSeasonFollowsLastPositionThenMarks() {
        fun of(vararg rows: ListRecord) = ListSnapshot(records = rows.associateBy { it.identity })
        assertEquals(1, listDefaultSeason(of(), "kp-1"))
        val mark = ListRecord(ListTable.Episodes, "kp-1", 5, season = 3, episode = 1, watched = true)
        assertEquals(3, listDefaultSeason(of(mark), "kp-1"))
        val pos = ListRecord(ListTable.Positions, "kp-1", 9, season = 2, episode = 1)
        assertEquals(2, listDefaultSeason(of(mark, pos), "kp-1"))
    }

    @Test fun ratingStepStaysInRange() {
        assertEquals(1, stepRating(1, -1)); assertEquals(10, stepRating(10, 1)); assertEquals(8, stepRating(7, 1))
    }

    @Test fun catalogLookupIgnoresDeletedAndOtherSources() {
        val row = ListRecord(ListTable.Titles, "tt1234567", 1, data = buildJsonObject { put("catalog_source", "Tmdb"); put("catalog_id", 5) })
        val snap = ListSnapshot(records = mapOf(row.identity to row))
        assertEquals(row, findByCatalog(snap, "Tmdb", 5))
        assertNull(findByCatalog(snap, "Kinopoisk", 5))
        assertNull(findByCatalog(ListSnapshot(records = mapOf(row.identity to row.copy(deleted = true))), "Tmdb", 5))
    }
}
