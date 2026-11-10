package dev.gamspi.videogallery.catalog.poiskkino

import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.catalog.model.SeasonInfo
import dev.gamspi.videogallery.catalog.model.positiveRating
import java.util.Locale

private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
internal fun String.genreLabel(): String = replaceFirstChar { it.titlecase() }

internal fun MovieDto.toMovieOrNull(): Movie? {
    val movieId = id?.takeIf { it > 0 } ?: return null
    val kind = when (type) {
        null, "movie", "cartoon" -> MediaKind.Movie
        "tv-series", "mini-series", "anime", "animated-series", "tv-show" -> MediaKind.Series
        else -> return null
    }
    val title = name.nonBlank() ?: alternativeName.nonBlank() ?: enName.nonBlank().orEmpty()
    return Movie(movieId, title, alternativeName.nonBlank() ?: enName.nonBlank() ?: title,
        year, poster?.previewUrl.nonBlank() ?: poster?.url.nonBlank(),
        description.nonBlank() ?: shortDescription.nonBlank().orEmpty(), rating?.kp.positiveRating(), kind,
        dev.gamspi.videogallery.catalog.model.releaseDate(premiere?.world)?.toString())
}

internal fun MovieDto.toDetailsOrNull(): MovieDetails? = toMovieOrNull()?.let { movie ->
    MovieDetails(movie, externalId?.imdb.nonBlank(), movieLength,
        genres.orEmpty().mapNotNull { it.name.nonBlank()?.genreLabel() }.distinct(), backdrop?.url.nonBlank(),
        seasonsInfo.orEmpty().filter { (it.number ?: 0) > 0 }.map { SeasonInfo(it.number!!, it.episodesCount ?: 0, it.airDate) })
}

internal fun SeasonCursorDto.toEpisodes(season: Int): List<Episode> = docs.orEmpty()
    .filter { it.number == null || it.number == season }
    .flatMap { it.episodes.orEmpty() }
    .mapNotNull { e ->
        val n = e.number ?: return@mapNotNull null
        Episode(season, n, e.name.nonBlank() ?: e.enName.nonBlank() ?: "Серия $n",
            e.duration?.takeIf { it > 0 }, e.still?.url.nonBlank(), e.airDate)
    }

internal fun DictionaryDto.toGenres(): List<Genre> {
    val excluded = setOf("для взрослых", "новости", "реальное тв", "ток-шоу", "церемония", "концерт", "игра")
    return items.orEmpty().mapNotNull {
        val id = it.id?.takeIf { value -> value > 0 } ?: return@mapNotNull null
        val name = it.name.nonBlank() ?: return@mapNotNull null
        if (name.lowercase(Locale.ROOT) in excluded) null else Genre(id, name.genreLabel())
    }.distinctBy { it.id }.sortedBy { it.name }
}
