package dev.gamspi.videogallery.catalog.poiskkino

import dev.gamspi.videogallery.catalog.kinopoisk.LenientIntSerializer
import kotlinx.serialization.Serializable

@Serializable
data class MovieCursorDto(
    val docs: List<MovieDto>? = null,
    val next: String? = null,
    val hasNext: Boolean = false,
)

@Serializable
data class MovieSearchDto(val docs: List<MovieDto>? = null)

@Serializable
data class DictionaryDto(val items: List<DictionaryItemDto>? = null)

@Serializable
data class DictionaryItemDto(val id: Int? = null, val name: String? = null)

@Serializable
data class ImageDto(val url: String? = null, val previewUrl: String? = null)

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
)
