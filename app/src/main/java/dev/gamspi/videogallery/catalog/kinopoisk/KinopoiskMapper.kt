package dev.gamspi.videogallery.catalog.kinopoisk

import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails

private val EXCLUDED_GENRES = setOf(
    "для взрослых", "новости", "реальное тв", "ток-шоу", "церемония", "концерт", "игра",
)

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }

private fun movie(
    id: Int,
    nameRu: String?,
    nameEn: String?,
    nameOriginal: String?,
    year: Int?,
    posterUrl: String?,
    posterUrlPreview: String?,
    overview: String?,
): Movie {
    val title = nameRu.nonBlank() ?: nameOriginal.nonBlank() ?: nameEn.nonBlank() ?: ""
    return Movie(
        id = id,
        title = title,
        originalTitle = nameOriginal.nonBlank() ?: title,
        year = year,
        posterUrl = posterUrlPreview.nonBlank() ?: posterUrl.nonBlank(),
        overview = overview.orEmpty(),
    )
}

/** Only films are shown; an absent type is kept. */
fun FilmDto.isFilm(): Boolean = type == null || type == "FILM"

fun FilmDto.toMovie(): Movie =
    movie(kinopoiskId, nameRu, nameEn, nameOriginal, year, posterUrl, posterUrlPreview, null)

fun GenreDto.toGenreOrNull(): Genre? {
    val name = genre.nonBlank() ?: return null
    if (name.lowercase() in EXCLUDED_GENRES) return null
    val genreId = id ?: return null
    return Genre(genreId, name.capitalized())
}

fun FilmDetailsDto.toDetails(): MovieDetails = MovieDetails(
    movie = movie(kinopoiskId, nameRu, nameEn, nameOriginal, year, posterUrl, posterUrlPreview, description),
    imdbId = imdbId.nonBlank(),
    runtimeMin = filmLength?.takeIf { it > 0 },
    genres = genres.orEmpty().mapNotNull { it.genre.nonBlank()?.capitalized() },
    backdropUrl = coverUrl.nonBlank(),
)
