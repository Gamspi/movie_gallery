package dev.gamspi.videogallery.translate

import dev.gamspi.videogallery.subs.subtitleLanguage

enum class TranslateLang(val tag: String) { EN("en"), RU("ru") }

fun TranslateLang.other(): TranslateLang = if (this == TranslateLang.EN) TranslateLang.RU else TranslateLang.EN

/** Maps a track language (any form accepted by subtitleLanguage) to a supported language. */
fun translateLang(language: String?): TranslateLang? = when (subtitleLanguage(language)) {
    "eng" -> TranslateLang.EN
    "rus" -> TranslateLang.RU
    else -> null
}

interface SubtitleTranslator {
    suspend fun translate(text: String, from: TranslateLang, to: TranslateLang): String

    /** Changes when output may change; part of the cache key. */
    val modelVersion: String
}
