package dev.gamspi.videogallery.watchlist

import dev.gamspi.videogallery.gallery.neighbourAfterRemoval
import dev.gamspi.videogallery.gallery.savedEntryTarget
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ListFirstEditTest {
    @Test fun mixedRowMenuKeepsTmdbMovieAndSeriesWithSameIdSeparate() {
        fun seed(kind: dev.gamspi.videogallery.catalog.model.MediaKind) = titleSeed(
            dev.gamspi.videogallery.catalog.model.Movie(1, "", "", 2027, null, "", kind = kind), "", "Tmdb")!!
        val film = seed(dev.gamspi.videogallery.catalog.model.MediaKind.Movie)
        val tv = seed(dev.gamspi.videogallery.catalog.model.MediaKind.Series)
        val snapshot = ListSnapshot(records = mapOf(film.identity to film, tv.identity to tv))
        assertEquals(tv, findByCatalog(snapshot, "Tmdb", 1, dev.gamspi.videogallery.catalog.model.MediaKind.Series))
        assertEquals(film, findByCatalog(snapshot, "Tmdb", 1, dev.gamspi.videogallery.catalog.model.MediaKind.Movie))
    }
    @Test fun premiereSurvivesListRestartAndNotifyKeepsUnwatched() = runTest {
        var disk: String? = null
        fun open() = LocalListStore({ disk }, { disk = it }, StandardTestDispatcher(testScheduler))
        val movie = dev.gamspi.videogallery.catalog.model.Movie(1, "Сериал", "Series", 2027, null, "",
            kind = dev.gamspi.videogallery.catalog.model.MediaKind.Series, premiereDate = "2027-03-12")
        val seed = titleSeed(movie, "", "Tmdb")!!
        val store = open()
        store.changeTitle(seed, "status", JsonPrimitive("unwatched"))
        store.changeTitle(seed, "notify", JsonPrimitive(true))
        val row = open().snapshot().records.getValue(seed.identity)
        assertEquals("unwatched", row.text("status"))
        assertEquals("true", row.text("notify"))
        assertEquals("2027-03-12", row.text("premiere_date"))
        assertEquals("Скоро · 12 мар 2027", dev.gamspi.videogallery.catalog.model.comingSoonLabel(
            row.text("premiere_date"), row.text("year").toInt(), java.time.LocalDate.of(2026, 11, 10)))
    }

    @Test fun currentPremiereRefreshPreservesExistingUserFields() = runTest {
        val store = LocalListStore({ null }, {}, StandardTestDispatcher(testScheduler))
        val old = imported.copy(data = JsonObject(imported.data + mapOf(
            "premiere_date" to JsonPrimitive("2027-01-01"), "my_rating" to JsonPrimitive(8))))
        store.edit(old)
        val seed = old.copy(data = JsonObject(old.data + ("premiere_date" to JsonPrimitive("2027-03-12"))))
        val row = store.changeTitle(seed, "status", JsonPrimitive("unwatched")).records.getValue(seed.identity)
        assertEquals("2027-03-12", row.text("premiere_date"))
        assertEquals("8", row.text("my_rating"))
        assertEquals("true", row.text("notify"))
    }
    private val imported = ListRecord(ListTable.Titles, "kp-1", 10, data = buildJsonObject {
        put("status", "watching"); put("notify", true); put("type", "tv_show")
    })
    private fun ListRecord.status() = text("status")

    @Test fun cardSeedIsUnwatchedWithoutNotifications() {
        val seed = listCardSeed(imported)
        assertEquals("unwatched", seed.status())
        assertEquals("false", seed.text("notify"))
    }

    @Test fun notifyOnUnlistedSeriesStaysUnwatched() = runTest {
        val store = LocalListStore({ null }, {}, StandardTestDispatcher(testScheduler))
        val seed = firstEditSeed(listCardSeed(imported), "notify")
        val row = store.changeTitle(seed, "notify", JsonPrimitive(true)).records.getValue(seed.identity)
        assertEquals("unwatched", row.status())
        assertEquals("true", row.text("notify"))
    }

    @Test fun ratingUnlistedSeriesMarksItWatched() = runTest {
        val store = LocalListStore({ null }, {}, StandardTestDispatcher(testScheduler))
        val seed = firstEditSeed(listCardSeed(imported), "my_rating")
        assertEquals("watched", store.changeTitle(seed, "my_rating", JsonPrimitive(8)).records.getValue(seed.identity).status())
    }

    @Test fun ratingListedSeriesKeepsItsStatus() = runTest {
        val store = LocalListStore({ null }, {}, StandardTestDispatcher(testScheduler))
        store.edit(imported)
        assertEquals("watching", store.changeTitle(imported.copy(updatedAt = 20), "my_rating", JsonPrimitive(8)).records.getValue(imported.identity).status())
    }

    @Test fun tombstoneFieldsDoNotReturnOnReAdd() = runTest {
        val store = LocalListStore({ null }, {}, StandardTestDispatcher(testScheduler))
        store.edit(imported.copy(deleted = true, data = buildJsonObject { put("status", "dropped"); put("my_rating", 3); put("type", "tv_show") }))
        val seed = firstEditSeed(listCardSeed(imported), "notify").copy(updatedAt = 20)
        val row = store.changeTitle(seed, "notify", JsonPrimitive(true)).records.getValue(seed.identity)
        assertFalse(row.deleted)
        assertEquals("unwatched", row.status())
        assertEquals("", row.text("my_rating"))
    }

    @Test fun removedRowHandsFocusToNextThenPrevious() {
        val before = listOf("a", "b", "c")
        assertEquals("c", neighbourAfterRemoval("b", before, listOf("a", "c")))
        assertEquals("b", neighbourAfterRemoval("c", before, listOf("a", "b")))
        assertEquals("b", neighbourAfterRemoval("a", before, listOf("b", "c")))
    }

    @Test fun neighbourSkipsRowsRemovedTogether() {
        val before = listOf("a", "b", "c", "d")
        assertEquals("d", neighbourAfterRemoval("b", before, listOf("a", "d")))
        assertEquals("a", neighbourAfterRemoval("c", before, listOf("a")))
    }

    @Test fun neighbourIsNullForEmptyListAndFallsBackForUnknownRow() {
        assertNull(neighbourAfterRemoval("a", listOf("a"), emptyList()))
        assertEquals("x", neighbourAfterRemoval("q", listOf("a"), listOf("x", "y")))
        assertEquals("x", neighbourAfterRemoval("a", listOf("a"), listOf("x")))
    }

    @Test fun railEntryUsesOnlyComposedRows() {
        val targets = mapOf("a" to 1, "b" to 2, "c" to 3)
        val order = listOf("a", "b", "c")
        assertEquals(2, savedEntryTarget("b", order, setOf("a", "b"), targets))
        assertEquals(1, savedEntryTarget("c", order, setOf("a", "b"), targets))
        assertNull(savedEntryTarget("c", order, emptySet(), targets))
    }
}
