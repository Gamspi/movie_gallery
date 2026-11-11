package dev.gamspi.videogallery.player

import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.subs.Layer
import dev.gamspi.videogallery.subs.LayerSource
import dev.gamspi.videogallery.subs.RemoteSubtitle
import dev.gamspi.videogallery.subs.sameLanguage
import dev.gamspi.videogallery.subs.subtitleLanguage
import dev.gamspi.videogallery.translate.TranslateLang
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Manual subtitle choice of one layer, stored by description rather than object identity. */
@Serializable
sealed interface SubtitleChoice {
    @Serializable @SerialName("off")
    data object Off : SubtitleChoice

    @Serializable @SerialName("embedded")
    data class Embedded(val language: String?, val label: String?) : SubtitleChoice

    @Serializable @SerialName("remote")
    data class Remote(val language: String, val fileId: Long, val release: String) : SubtitleChoice

    @Serializable @SerialName("translated")
    data class Translated(val from: Layer, val sourceLang: TranslateLang) : SubtitleChoice
}

private val choiceJson = Json { ignoreUnknownKeys = true }

fun encodeSubtitleChoice(choice: SubtitleChoice): String =
    choiceJson.encodeToString(SubtitleChoice.serializer(), choice)

/** Null for missing or unreadable values. */
fun decodeSubtitleChoice(raw: String?): SubtitleChoice? {
    if (raw.isNullOrBlank()) return null
    return try { choiceJson.decodeFromString(SubtitleChoice.serializer(), raw) }
    catch (_: IllegalArgumentException) { null }
}

/** Descriptor of a selected source; null for None (auto), which is never stored. */
fun LayerSource.toChoice(): SubtitleChoice? = when (this) {
    LayerSource.Off -> SubtitleChoice.Off
    is LayerSource.Embedded -> SubtitleChoice.Embedded(track.language, track.label)
    is LayerSource.Remote -> SubtitleChoice.Remote(sub.language, sub.fileId, sub.release)
    is LayerSource.Translated -> SubtitleChoice.Translated(from, sourceLang)
    LayerSource.None -> null
}

/**
 * Title-level key for stored subtitle choices: a series shares one key across episodes.
 * Falls back to the playback key (per video) without a stable title id.
 */
fun subtitleTitleKey(meta: PlaybackMeta, playbackKey: String): String = when {
    meta.movieId > 0 && !meta.source.isNullOrBlank() -> "${meta.source}:${meta.kind.name}:${meta.movieId}"
    meta.imdb.isNotBlank() -> "imdb:${meta.kind.name}:${meta.imdb}"
    else -> "key:$playbackKey"
}

/**
 * Source matching a stored [choice] among what is available now; null means it cannot be
 * restored yet and the caller falls back to automatic selection. [taken] is the other
 * layer's embedded track: both layers never share an embedded group.
 */
fun restoreSource(
    choice: SubtitleChoice,
    embedded: List<TextTrack>,
    remote: List<RemoteSubtitle>,
    taken: TextTrack?,
    translations: List<LayerSource.Translated>,
): LayerSource? = when (choice) {
    SubtitleChoice.Off -> LayerSource.Off
    is SubtitleChoice.Embedded -> {
        val free = embedded.filter { it.supported && it.group != taken?.group }
        val exact = free.firstOrNull {
            (it.language == choice.language || sameLanguage(it.language, choice.language)) && it.label == choice.label
        }
        val byLanguage = if (subtitleLanguage(choice.language) == null) null
            else free.firstOrNull { sameLanguage(it.language, choice.language) }
        (exact ?: byLanguage)?.let { LayerSource.Embedded(it) }
    }
    is SubtitleChoice.Remote -> (remote.firstOrNull { it.fileId == choice.fileId }
        ?: remote.firstOrNull { sameLanguage(it.language, choice.language) })?.let { LayerSource.Remote(it) }
    is SubtitleChoice.Translated ->
        LayerSource.Translated(choice.from, choice.sourceLang).takeIf { it in translations }
}
