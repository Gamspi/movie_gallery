package dev.gamspi.videogallery.details

import dev.gamspi.videogallery.catalog.model.*
import dev.gamspi.videogallery.history.FINISHED_PROGRESS
import dev.gamspi.videogallery.stream.EpisodeRef
import java.time.LocalDate

internal fun DetailsUiState.Content.episodeAvailable(ref: EpisodeRef, today: LocalDate): Boolean {
    val episode = knownEpisodes[ref.season]?.firstOrNull { it.number == ref.episode } ?: return false
    return isEpisodeAvailable(episode, today, seasons.firstOrNull { it.number == ref.season }?.airDate,
        details.movie.isComingSoon(today))
}

internal fun DetailsUiState.Content.seasonComingSoon(number: Int, today: LocalDate): Boolean =
    isSeasonComingSoon(seasons.firstOrNull { it.number == number } ?: SeasonInfo(number, 0),
        knownEpisodes[number], today, details.movie.isComingSoon(today))

/** A candidate is shown only after its season is loaded; future candidates never reach playback. */
internal fun availableResume(
    candidate: EpisodeRef,
    c: DetailsUiState.Content,
    progress: Map<EpisodeRef, Float>,
    today: LocalDate,
): EpisodeRef? {
    if (c.episodeAvailable(candidate, today)) return candidate
    return c.knownEpisodes.values.flatten().map { EpisodeRef(it.season, it.number) }.sorted()
        .firstOrNull { c.episodeAvailable(it, today) && (progress[it] ?: 0f) < FINISHED_PROGRESS }
}
