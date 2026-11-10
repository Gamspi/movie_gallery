package dev.gamspi.videogallery.catalog.model

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Keep the catalog's calendar date, including ISO timestamps, without timezone conversion. */
fun releaseDate(value: String?): LocalDate? = try {
    value?.takeIf { it.isNotBlank() }?.take(10)?.let(LocalDate::parse)
} catch (_: DateTimeParseException) {
    null
}

fun isComingSoon(premiereDate: String?, year: Int?, today: LocalDate): Boolean =
    releaseDate(premiereDate)?.isAfter(today) ?: (year != null && year > today.year)

fun Movie.isComingSoon(today: LocalDate): Boolean = isComingSoon(premiereDate, year, today)

private val months = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

fun formatReleaseDate(value: String?, includeYear: Boolean = true): String? = releaseDate(value)?.let {
    "${it.dayOfMonth} ${months[it.monthValue - 1]}" + if (includeYear) " ${it.year}" else ""
}

fun comingSoonLabel(premiereDate: String?, year: Int?, today: LocalDate): String? =
    if (isComingSoon(premiereDate, year, today)) "Скоро · ${formatReleaseDate(premiereDate) ?: year}" else null

/** Missing episode dates keep the old availability unless the season/title is known to be future. */
fun isEpisodeAvailable(
    episode: Episode,
    today: LocalDate,
    seasonAirDate: String? = null,
    titleComingSoon: Boolean = false,
): Boolean {
    val date = releaseDate(episode.airDate) ?: releaseDate(seasonAirDate)
    return !titleComingSoon && date?.isAfter(today) != true
}

fun isSeasonComingSoon(season: SeasonInfo, episodes: List<Episode>?, today: LocalDate, titleComingSoon: Boolean): Boolean =
    titleComingSoon || if (!episodes.isNullOrEmpty()) {
        episodes.none { isEpisodeAvailable(it, today, season.airDate) }
    } else releaseDate(season.airDate)?.isAfter(today) == true

/** Keep movie/series id collisions distinct; unknown dates follow dated premieres. */
fun upcomingTitles(movies: List<Movie>, today: LocalDate): List<Movie> = movies
    .filter { it.isComingSoon(today) }
    .distinctBy { it.kind to it.id }
    .sortedWith(compareBy<Movie> { releaseDate(it.premiereDate) == null }
        .thenBy { releaseDate(it.premiereDate) }.thenBy { it.year })
