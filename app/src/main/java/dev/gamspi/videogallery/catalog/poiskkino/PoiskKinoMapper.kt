package dev.gamspi.videogallery.catalog.poiskkino

import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import java.util.Locale

private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
internal fun String.genreLabel(): String = replaceFirstChar { it.titlecase() }

internal fun MovieDto.toMovieOrNull(): Movie? {
    val movieId = id?.takeIf { it > 0 } ?: return null
    if (type != null && type != "movie") return null
    val title = name.nonBlank() ?: alternativeName.nonBlank() ?: enName.nonBlank().orEmpty()
    return Movie(movieId, title, alternativeName.nonBlank() ?: enName.nonBlank() ?: title,
        year, poster?.previewUrl.nonBlank() ?: poster?.url.nonBlank(),
        description.nonBlank() ?: shortDescription.nonBlank().orEmpty())
}

internal fun MovieDto.toDetailsOrNull(): MovieDetails? = toMovieOrNull()?.let { movie ->
    MovieDetails(movie, externalId?.imdb.nonBlank(), movieLength,
        genres.orEmpty().mapNotNull { it.name.nonBlank()?.genreLabel() }.distinct(), backdrop?.url.nonBlank())
}

internal fun DictionaryDto.toGenres(): List<Genre> {
    val excluded = setOf("для взрослых", "новости", "реальное тв", "ток-шоу", "церемония", "концерт", "игра")
    return items.orEmpty().mapNotNull {
        val id = it.id?.takeIf { value -> value > 0 } ?: return@mapNotNull null
        val name = it.name.nonBlank() ?: return@mapNotNull null
        if (name.lowercase(Locale.ROOT) in excluded) null else Genre(id, name.genreLabel())
    }.distinctBy { it.id }.sortedBy { it.name }
}
