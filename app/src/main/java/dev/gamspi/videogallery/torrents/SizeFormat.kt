package dev.gamspi.videogallery.torrents

private const val MIB = 1024L * 1024
private const val GIB = MIB * 1024

fun formatSize(bytes: Long): String =
    if (bytes < GIB) "${bytes / MIB} МБ"
    else String.format(java.util.Locale.forLanguageTag("ru"), "%.1f ГБ", bytes.toDouble() / GIB)
