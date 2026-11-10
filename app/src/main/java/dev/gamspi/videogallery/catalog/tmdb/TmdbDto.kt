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
    @SerialName("vote_average") val voteAverage: Double? = null,
    // tv fields
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
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
    @SerialName("vote_average") val voteAverage: Double? = null,
    // tv fields
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int>? = null,
    val seasons: List<SeasonSummaryDto>? = null,
)

@Serializable
data class SeasonSummaryDto(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
data class SeasonDto(val episodes: List<EpisodeDto>? = null)

@Serializable
data class EpisodeDto(
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String? = null,
    val runtime: Int? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
data class ExternalIdsDto(@SerialName("imdb_id") val imdbId: String? = null)
