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
            Movie(id, "Премьера", "", year, "https://images.example/poster.jpg", "", kind = kind, premiereDate = date)
        val soon = movie(1, "2026-11-11")
        val series = movie(1, "2026-11-12", kind = MediaKind.Series)
        val unknown = movie(2, null)
        assertEquals(listOf(soon, series), upcomingTitles(
            listOf(unknown, series, soon, soon, movie(3, "2026-11-10")), today))
    }

    private fun candidate(date: String? = "2026-11-11") =
        Movie(1, "Премьера", "Premiere", 2027, "https://images.example/poster.jpg", "", premiereDate = date)

    @Test fun cyrillicTitleAcceptsMixedNamesAndYoButRejectsOtherScripts() {
        for (title in listOf("Премьера", "Ё", "ё", "WALL·E: ВАЛЛ·И", "Ї")) {
            assertTrue(title, hasCyrillicTitle(title))
        }
        for (title in listOf("", "  ", "Premiere", "東京", "한국", "123!?", "\u0483")) {
            assertFalse(title, hasCyrillicTitle(title))
        }
    }

    @Test fun upcomingWindowIncludesTomorrowAndSixMonthsButNotTodayOrLater() {
        assertEquals(LocalDate.of(2026, 11, 11)..LocalDate.of(2027, 5, 10), upcomingDateWindow(today))
        for (date in listOf("2026-11-11", "2027-05-10", "2027-05-10T23:59:59Z")) {
            assertTrue(date, candidate(date).isUpcomingCandidate(today))
        }
        for (date in listOf("2026-11-09", "2026-11-10", "2027-05-11")) {
            assertFalse(date, candidate(date).isUpcomingCandidate(today))
        }
    }

    @Test fun upcomingWindowUsesCalendarMonthsAtYearEndAndLeapDay() {
        assertEquals(LocalDate.of(2027, 1, 1)..LocalDate.of(2027, 6, 30), upcomingDateWindow(LocalDate.of(2026, 12, 31)))
        assertEquals(LocalDate.of(2023, 9, 1)..LocalDate.of(2024, 2, 29), upcomingDateWindow(LocalDate.of(2023, 8, 31)))
    }

    @Test fun upcomingRowRequiresKnownDateEvenWhenBadgeUsesFutureYear() {
        for (date in listOf(null, "", "bad", "2027-02-30")) {
            assertFalse(candidate(date).isUpcomingCandidate(today))
            assertTrue(candidate(date).isComingSoon(today))
        }
    }

    @Test fun upcomingRowRequiresLocalizedTitleAndPosterButNoRating() {
        assertTrue(candidate().isUpcomingCandidate(today))
        for (poster in listOf(null, "", " \t")) {
            assertFalse(candidate().copy(posterUrl = poster).isUpcomingCandidate(today))
        }
        assertFalse(candidate().copy(title = "Premiere", originalTitle = "Премьера", rating = 10.0).isUpcomingCandidate(today))
        assertTrue(upcomingTitles(listOf(candidate().copy(title = "東京"), candidate().copy(posterUrl = null)), today).isEmpty())
    }

    @Test fun upcomingLimitAppliedAfterFilteringDeduplicationAndDateSorting() {
        val eligible = (1..25).map { candidate(today.plusDays(it.toLong()).toString()).copy(id = it) }
        val rejected = (26..50).map { candidate().copy(id = it, title = "Untranslated") }
        assertEquals(eligible.take(20), upcomingTitles(rejected + eligible.reversed() + eligible, today))
    }
}
