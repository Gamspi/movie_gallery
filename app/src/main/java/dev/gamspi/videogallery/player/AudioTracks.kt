package dev.gamspi.videogallery.player

import java.util.Locale

data class AudioTrack(
    val group: Int,
    val index: Int,
    val language: String?,
    val label: String?,
    val codec: String?,
    val supported: Boolean,
)

data class AudioPreference(val language: String, val label: String?)

private fun String?.realLanguage(): String? =
    this?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }

fun AudioTrack.displayName(ordinal: Int): String {
    val lang = language.realLanguage()?.let { l ->
        (runCatching { Locale.forLanguageTag(l).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: l)
            .lowercase().replaceFirstChar { it.uppercase() }
    }
    val lbl = label?.takeIf { it.isNotBlank() }
    val base = if (lang == null && lbl == null) {
        "Дорожка $ordinal"
    } else {
        val head = listOfNotNull(lang, lbl).joinToString(", ")
        val cod = codec?.takeIf { it.isNotBlank() }?.uppercase()
        if (cod != null) "$head · $cod" else head
    }
    return if (supported) base else "$base — не поддерж."
}

fun chooseAudio(tracks: List<AudioTrack>, pref: AudioPreference?): AudioTrack? {
    if (pref == null) return null
    val sameLang = tracks.filter { it.supported && it.language.equals(pref.language, ignoreCase = true) }
    return sameLang.firstOrNull { it.label.equals(pref.label, ignoreCase = true) }
        ?: sameLang.firstOrNull()
}
