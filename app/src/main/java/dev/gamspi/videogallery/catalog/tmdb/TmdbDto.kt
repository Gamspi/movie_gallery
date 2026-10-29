package dev.gamspi.videogallery.catalog.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MovieListDto(val results: List<MovieDto>? = null)

@Serializable
data class MovieDto(
    val id: Int,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val overview: String? = null,
)

@Serializable
data class GenreListDto(val genres: List<GenreDto>? = null)

@Serializable
data class GenreDto(val id: Int, val name: String? = null)

@Serializable
data class MovieDetailsDto(
    val id: Int,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    val genres: List<GenreDto>? = null,
    @SerialName("external_ids") val externalIds: ExternalIdsDto? = null,
)

@Serializable
data class ExternalIdsDto(@SerialName("imdb_id") val imdbId: String? = null)
