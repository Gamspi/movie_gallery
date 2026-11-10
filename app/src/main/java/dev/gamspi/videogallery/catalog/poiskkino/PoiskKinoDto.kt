package dev.gamspi.videogallery.catalog.poiskkino

import dev.gamspi.videogallery.catalog.kinopoisk.LenientDoubleSerializer
import dev.gamspi.videogallery.catalog.kinopoisk.LenientIntSerializer
import kotlinx.serialization.Serializable

@Serializable
data class MovieCursorDto(
    val docs: List<MovieDto>? = null,
    val next: String? = null,
    val hasNext: Boolean = false,
)

@Serializable
data class SeasonCursorDto(val docs: List<SeasonDto>? = null)

@Serializable
data class SeasonDto(val number: Int? = null, val episodes: List<EpisodeDto>? = null)

@Serializable
data class EpisodeDto(
    val number: Int? = null,
    val name: String? = null,
    val enName: String? = null,
    val duration: Int? = null,
    val still: ImageDto? = null,
    val airDate: String? = null,
)

@Serializable
data class SeasonInfoDto(val number: Int? = null, val episodesCount: Int? = null)

@Serializable
data class MovieSearchDto(val docs: List<MovieDto>? = null)

@Serializable
data class DictionaryDto(val items: List<DictionaryItemDto>? = null)

@Serializable
data class DictionaryItemDto(val id: Int? = null, val name: String? = null)

@Serializable
data class ImageDto(val url: String? = null, val previewUrl: String? = null)

@Serializable
data class RatingDto(@Serializable(with = LenientDoubleSerializer::class) val kp: Double? = null)

@Serializable
data class ExternalIdDto(val imdb: String? = null)

@Serializable
data class MovieDto(
    val id: Int? = null,
    val name: String? = null,
    val alternativeName: String? = null,
    val enName: String? = null,
    @Serializable(with = LenientIntSerializer::class) val year: Int? = null,
    val type: String? = null,
    val poster: ImageDto? = null,
    val backdrop: ImageDto? = null,
    val description: String? = null,
    val shortDescription: String? = null,
    val externalId: ExternalIdDto? = null,
    val movieLength: Int? = null,
    val genres: List<DictionaryItemDto>? = null,
    val rating: RatingDto? = null,
    val seasonsInfo: List<SeasonInfoDto>? = null,
)
