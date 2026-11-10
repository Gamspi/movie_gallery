package dev.gamspi.videogallery.details

import dev.gamspi.videogallery.catalog.model.*
import dev.gamspi.videogallery.stream.EpisodeRef
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class EpisodeAvailabilityTest {
    private val today = LocalDate.of(2026, 11, 10)
    private val episodes = listOf(Episode(1, 1, "", null, null, "2026-11-10"),
        Episode(1, 2, "", null, null, "2026-11-11"))
    private val content = DetailsUiState.Content(
        MovieDetails(Movie(1, "", "", 2026, null, "", kind = MediaKind.Series), null, null, emptyList(), null),
        seasons = listOf(SeasonInfo(1, 2)), knownEpisodes = mapOf(1 to episodes))

    @Test fun resumeAvailableTodayButNotTomorrow() {
        assertEquals(EpisodeRef(1, 1), availableResume(EpisodeRef(1, 1), content, emptyMap(), today))
        assertNull(availableResume(EpisodeRef(1, 2), content, mapOf(EpisodeRef(1, 1) to 1f), today))
        assertEquals(EpisodeRef(1, 2), availableResume(EpisodeRef(1, 2), content, emptyMap(), today.plusDays(1)))
    }

    @Test fun unavailableSeasonMustBeLoadedBeforeResume() {
        assertNull(availableResume(EpisodeRef(1, 1), content.copy(knownEpisodes = emptyMap()), emptyMap(), today))
    }

    @Test fun seasonWithOnlyFutureEpisodesHasSoonLabelButMixedSeasonDoesNot() {
        assertFalse(content.seasonComingSoon(1, today))
        assertTrue(content.copy(knownEpisodes = mapOf(1 to episodes.drop(1))).seasonComingSoon(1, today))
        assertFalse(content.copy(knownEpisodes = mapOf(1 to listOf(episodes[0].copy(airDate = null)))).seasonComingSoon(1, today))
    }
}
