package dev.gamspi.videogallery.catalog.model

data class Movie(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val year: Int?,
    val posterUrl: String?,
    val overview: String,
    /** Native rating of the catalog source (Kinopoisk or TMDB), null when absent or zero. */
    val rating: Double? = null,
)

/** Zero or negative ratings mean "no votes". */
fun Double?.positiveRating(): Double? = this?.takeIf { it > 0 && !it.isNaN() }

data class MovieDetails(
    val movie: Movie,
    val imdbId: String?,
    val runtimeMin: Int?,
    val genres: List<String>,
    val backdropUrl: String?,
)

data class Genre(val id: Int, val name: String)
