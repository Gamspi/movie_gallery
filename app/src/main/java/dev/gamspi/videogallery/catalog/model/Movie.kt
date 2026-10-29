package dev.gamspi.videogallery.catalog.model

data class Movie(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val year: Int?,
    val posterUrl: String?,
    val overview: String,
)

data class MovieDetails(
    val movie: Movie,
    val imdbId: String?,
    val runtimeMin: Int?,
    val genres: List<String>,
    val backdropUrl: String?,
)

data class Genre(val id: Int, val name: String)
