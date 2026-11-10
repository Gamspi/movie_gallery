package dev.gamspi.videogallery.catalog.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaKind { Movie, Series }

data class Movie(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val year: Int?,
    val posterUrl: String?,
    val overview: String,
    /** Native rating of the catalog source (Kinopoisk or TMDB), null when absent or zero. */
    val rating: Double? = null,
    val kind: MediaKind = MediaKind.Movie,
    /** World premiere for films, first air date for series (ISO date). */
    val premiereDate: String? = null,
)

/** Zero or negative ratings mean "no votes". */
fun Double?.positiveRating(): Double? = this?.takeIf { it > 0 && !it.isNaN() }

data class MovieDetails(
    val movie: Movie,
    val imdbId: String?,
    val runtimeMin: Int?,
    val genres: List<String>,
    val backdropUrl: String?,
    val seasons: List<SeasonInfo> = emptyList(),
)

data class SeasonInfo(val number: Int, val episodeCount: Int, val airDate: String? = null)

data class Episode(
    val season: Int,
    val number: Int,
    val title: String,
    val runtimeMin: Int?,
    val stillUrl: String?,
    /** ISO date; null when the catalog does not report it. */
    val airDate: String? = null,
)

data class Genre(val id: Int, val name: String)
