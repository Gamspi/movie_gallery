package dev.gamspi.videogallery.gallery

import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.history.WatchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingTest {
    private fun entry(id: Int, pos: Long = 30, dur: Long = 100) = WatchEntry(
        movieId = id, title = "T$id", year = 2001, posterUrl = "p$id", rating = 7.5,
        positionKey = "k$id", positionMs = pos, durationMs = dur, updatedAt = id.toLong(),
    )

    private val rows = listOf(
        CONTINUE_ROW_KEY to listOf(5, 6),
        catalogRowKey(0) to listOf(1, 2, 3),
        catalogRowKey(1) to listOf(4),
    )

    @Test
    fun toMovie_copiesCardFields() {
        val m = entry(7).toMovie()
        assertEquals(7, m.id)
        assertEquals("T7", m.title)
        assertEquals(2001, m.year)
        assertEquals("p7", m.posterUrl)
        assertEquals(7.5, m.rating!!, 0.0)
    }

    @Test
    fun progressByMovie_mapsIdToShare() {
        val map = progressByMovie(listOf(entry(1, 25, 100), entry(2, 50, 200)))
        assertEquals(mapOf((MediaKind.Movie to 1) to 0.25f, (MediaKind.Movie to 2) to 0.25f), map)
    }

    @Test fun progressSeparatesMovieAndSeriesWithSameId() {
        val movie = entry(7, 20)
        val series = entry(7, 60).copy(kind = MediaKind.Series)
        val map = progressByMovie(listOf(movie, series))
        assertEquals(0.2f, map[MediaKind.Movie to 7]!!, 0f)
        assertEquals(0.6f, map[MediaKind.Series to 7]!!, 0f)
        assertNull(progressByMovie(listOf(movie))[MediaKind.Series to 7])
        assertEquals(MediaKind.Series, series.toMovie().kind)
    }

    @Test fun historyFiltersOtherSourcesButKeepsLegacy() {
        val legacy = entry(1)
        val movie = entry(7).copy(source = "Tmdb")
        val series = movie.copy(kind = MediaKind.Series)
        val other = movie.copy(source = "Kinopoisk")
        assertEquals(listOf(legacy, movie, series), historyForSource(listOf(legacy, movie, series, other), "Tmdb"))
        assertEquals(listOf(legacy, other), historyForSource(listOf(legacy, movie, series, other), "Kinopoisk"))
    }

    @Test fun seriesCaptionUsesSeasonAndEpisode() {
        val series = entry(7).copy(kind = MediaKind.Series, season = 2, episode = 5)
        assertEquals("S02E05", series.episodeCaption())
        assertEquals("S12E105", series.copy(season = 12, episode = 105).episodeCaption())
        assertNull(entry(7).episodeCaption())
        assertNull(series.copy(episode = null).episodeCaption())
    }

    @Test
    fun rowKeys_unique() {
        assertEquals("row-0", catalogRowKey(0))
        assert(CONTINUE_ROW_KEY != catalogRowKey(0))
    }

    @Test
    fun resolve_existingCard_kept() {
        assertEquals(CardKey("row-0", 3), resolveFocusTarget(rows, CardKey("row-0", 3)))
        assertEquals(CardKey(CONTINUE_ROW_KEY, 6), resolveFocusTarget(rows, CardKey(CONTINUE_ROW_KEY, 6)))
    }

    @Test
    fun resolve_noWanted_firstCardOfFirstRow() {
        assertEquals(CardKey(CONTINUE_ROW_KEY, 5), resolveFocusTarget(rows, null))
        assertEquals(CardKey("row-0", 1), resolveFocusTarget(rows.drop(1), null))
    }

    @Test
    fun resolve_vanishedCard_firstCardOfSameRow() {
        assertEquals(CardKey(CONTINUE_ROW_KEY, 5), resolveFocusTarget(rows, CardKey(CONTINUE_ROW_KEY, 9)))
    }

    @Test
    fun resolve_vanishedRow_firstCardOfFirstRow() {
        val noContinue = rows.drop(1)
        assertEquals(CardKey("row-0", 1), resolveFocusTarget(noContinue, CardKey(CONTINUE_ROW_KEY, 5)))
        val emptyContinue = listOf(CONTINUE_ROW_KEY to emptyList<Int>()) + noContinue
        assertEquals(CardKey("row-0", 1), resolveFocusTarget(emptyContinue, CardKey(CONTINUE_ROW_KEY, 5)))
    }

    @Test
    fun resolve_noRows_null() {
        assertNull(resolveFocusTarget(emptyList(), CardKey("row-0", 1)))
        assertNull(resolveFocusTarget(emptyList(), null))
    }
}
