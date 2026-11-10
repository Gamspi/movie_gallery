package dev.gamspi.videogallery.catalog.model

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReleaseDatesTest {
    private val today = LocalDate.of(2026, 11, 10)

    @Test fun dateTakesPriorityOverYear() {
        assertTrue(isComingSoon("2026-11-11", 2020, today))
        assertFalse(isComingSoon("2026-11-10", 2027, today))
        assertFalse(isComingSoon("2026-11-09", 2027, today))
    }

    @Test fun missingOrInvalidDateFallsBackToYear() {
        for (date in listOf(null, "", "bad", "2026-02-30")) {
            assertTrue(isComingSoon(date, 2027, today))
            assertFalse(isComingSoon(date, 2026, today))
            assertFalse(isComingSoon(date, null, today))
        }
    }

    @Test fun calendarDateIgnoresTimestampAndHandlesYearBoundary() {
        assertFalse(isComingSoon("2026-11-10T23:59:59Z", null, today))
        assertTrue(isComingSoon("2027-01-01", null, LocalDate.of(2026, 12, 31)))
        assertFalse(isComingSoon(null, 2027, LocalDate.of(2027, 1, 1)))
    }

    @Test fun russianLabels() {
        assertEquals("Скоро · 12 мар 2027", comingSoonLabel("2027-03-12", 2027, today))
        assertEquals("Скоро · 2027", comingSoonLabel(null, 2027, today))
        assertEquals("12 мар", formatReleaseDate("2027-03-12", includeYear = false))
        assertNull(comingSoonLabel("2026-11-10", 2026, today))
    }

    @Test fun episodeAvailabilityUsesEpisodeThenSeasonAndTitle() {
        val episode = Episode(1, 1, "", null, null)
        assertTrue(isEpisodeAvailable(episode, today))
        assertTrue(isEpisodeAvailable(episode.copy(airDate = today.toString()), today))
        assertFalse(isEpisodeAvailable(episode.copy(airDate = "2026-11-11"), today))
        assertFalse(isEpisodeAvailable(episode, today, seasonAirDate = "2026-11-11"))
        assertFalse(isEpisodeAvailable(episode, today, titleComingSoon = true))
        assertTrue(isEpisodeAvailable(episode.copy(airDate = "2026-11-09"), today, seasonAirDate = "2026-11-11"))
    }

    @Test fun upcomingSortedByDateAndTypeIdentityKept() {
        fun movie(id: Int, date: String?, year: Int = 2027, kind: MediaKind = MediaKind.Movie) =
            Movie(id, "", "", year, null, "", kind = kind, premiereDate = date)
        val soon = movie(1, "2026-11-11")
        val series = movie(1, "2026-11-12", kind = MediaKind.Series)
        val unknown = movie(2, null)
        assertEquals(listOf(soon, series, unknown), upcomingTitles(
            listOf(unknown, series, soon, soon, movie(3, "2026-11-10")), today))
    }
}
