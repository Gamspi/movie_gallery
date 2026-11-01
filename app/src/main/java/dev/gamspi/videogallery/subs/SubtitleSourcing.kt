package dev.gamspi.videogallery.subs

import dev.gamspi.videogallery.player.TextTrack
import dev.gamspi.videogallery.settings.LayerStyle
import java.util.Locale
import java.util.MissingResourceException

interface SubtitleClient {
    suspend fun search(hash: String?, imdbId: String?, languages: List<String>): SubsResult<List<RemoteSubtitle>>
    suspend fun download(fileId: Long): SubsResult<ByteArray>
}

enum class Layer { A, B }

sealed interface LayerSource {
    data object Off : LayerSource
    data class Embedded(val track: TextTrack) : LayerSource
    data class Remote(val sub: RemoteSubtitle) : LayerSource
    data object None : LayerSource
}

data class LayerState(
    val source: LayerSource = LayerSource.None,
    val track: SubtitleTrack? = null,
    val note: String? = null,
    val offsetMs: Long = 0,
    val style: LayerStyle = LayerStyle(),
)

/** Invalid and undefined tags never match each other. */
fun subtitleLanguage(language: String?): String? {
    val tag = language?.trim()?.replace('_', '-') ?: return null
    if (!tag.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*"))) return null
    val locale = Locale.forLanguageTag(tag)
    if (locale.language.isBlank() || locale.language == "und") return null
    return try { locale.isO3Language.takeIf { it.isNotBlank() && it != "und" } }
    catch (_: MissingResourceException) { null }
}

fun sameLanguage(a: String?, b: String?): Boolean =
    subtitleLanguage(a)?.let { it == subtitleLanguage(b) } ?: false

fun chooseSource(
    language: String,
    embedded: List<TextTrack>,
    remote: List<RemoteSubtitle>,
    taken: TextTrack?,
): LayerSource {
    val normalized = subtitleLanguage(language) ?: return LayerSource.None
    embedded.firstOrNull {
        it.supported && it.group != taken?.group && subtitleLanguage(it.language) == normalized
    }?.let { return LayerSource.Embedded(it) }
    remote.firstOrNull { subtitleLanguage(it.language) == normalized }
        ?.let { return LayerSource.Remote(it) }
    return LayerSource.None
}
