package dev.gamspi.videogallery.stream

import java.util.Locale

private const val KIB = 1024L
private const val MIB = KIB * 1024

fun formatSpeed(bytesPerSec: Long): String =
    if (bytesPerSec < MIB) "${bytesPerSec / KIB} КБ/с"
    else String.format(Locale.forLanguageTag("ru"), "%.1f МБ/с", bytesPerSec.toDouble() / MIB)

fun peersText(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    val word = when {
        mod100 in 11..14 -> "пиров"
        mod10 == 1 -> "пир"
        mod10 in 2..4 -> "пира"
        else -> "пиров"
    }
    return "$n $word"
}

fun statusLine(speed: Long, peers: Int, progressPercent: Int?): String {
    val base = "↓ ${formatSpeed(speed)} · ${peersText(peers)}"
    return if (progressPercent == null) base else "$base · загружено $progressPercent %"
}
