package dev.gamspi.videogallery.catalog.tmdb

import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.catalog.model.positiveRating

private const val POSTER_BASE = "https://image.tmdb.org/t/p/w342"
private const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"

private fun yearOf(date: String?): Int? =
    date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()

private fun movie(
    id: Int,
    title: String?,
    originalTitle: String?,
    releaseDate: String?,
    posterPath: String?,
    overview: String?,
    rating: Double?,
): Movie {
    val t = title?.takeIf { it.isNotBlank() } ?: originalTitle.orEmpty()
    return Movie(
        id = id,
        title = t,
        originalTitle = originalTitle?.takeIf { it.isNotBlank() } ?: t,
        year = yearOf(releaseDate),
        posterUrl = posterPath?.takeIf { it.isNotEmpty() }?.let { POSTER_BASE + it },
        overview = overview.orEmpty(),
        rating = rating.positiveRating(),
    )
}

fun MovieDto.toMovie(): Movie = movie(id, title, originalTitle, releaseDate, posterPath, overview, voteAverage)

fun GenreDto.toGenre(): Genre = Genre(id, name.orEmpty())

fun MovieDetailsDto.toDetails(): MovieDetails = MovieDetails(
    movie = movie(id, title, originalTitle, releaseDate, posterPath, overview, voteAverage),
    imdbId = externalIds?.imdbId?.takeIf { it.isNotEmpty() },
    runtimeMin = runtime?.takeIf { it > 0 },
    genres = genres.orEmpty().mapNotNull { it.name },
    backdropUrl = backdropPath?.takeIf { it.isNotEmpty() }?.let { BACKDROP_BASE + it },
)
