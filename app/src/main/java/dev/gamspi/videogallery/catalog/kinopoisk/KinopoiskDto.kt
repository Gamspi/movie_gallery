package dev.gamspi.videogallery.catalog.kinopoisk

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Year arrives as a number, a numeric string or null; anything unparsable becomes null. */
object LenientIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return if (element is JsonNull || element !is JsonPrimitive) null else element.intOrNull
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

@Serializable
data class FilmListDto(val items: List<FilmDto>? = null)

@Serializable
data class FilmDto(
    val kinopoiskId: Int,
    val nameRu: String? = null,
    val nameEn: String? = null,
    val nameOriginal: String? = null,
    @Serializable(with = LenientIntSerializer::class) val year: Int? = null,
    val type: String? = null,
    val posterUrl: String? = null,
    val posterUrlPreview: String? = null,
)

@Serializable
data class FiltersDto(val genres: List<GenreDto>? = null)

@Serializable
data class GenreDto(val id: Int? = null, val genre: String? = null)

@Serializable
data class FilmDetailsDto(
    val kinopoiskId: Int,
    val imdbId: String? = null,
    val nameRu: String? = null,
    val nameEn: String? = null,
    val nameOriginal: String? = null,
    val posterUrl: String? = null,
    val posterUrlPreview: String? = null,
    val coverUrl: String? = null,
    @Serializable(with = LenientIntSerializer::class) val year: Int? = null,
    val filmLength: Int? = null,
    val description: String? = null,
    val type: String? = null,
    val genres: List<GenreDto>? = null,
)
