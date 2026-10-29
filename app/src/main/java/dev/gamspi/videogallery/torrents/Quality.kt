package dev.gamspi.videogallery.torrents

enum class Quality(val label: String) { Uhd("2160p"), FullHd("1080p"), Hd("720p") }

private val uhdRe = Regex("""\b(2160p|4k|uhd)\b""", RegexOption.IGNORE_CASE)
private val fullHdRe = Regex("""\b(1080p|1080i)\b""", RegexOption.IGNORE_CASE)
private val hdRe = Regex("""\b720p\b""", RegexOption.IGNORE_CASE)

fun parseQuality(title: String): Quality? = when {
    uhdRe.containsMatchIn(title) -> Quality.Uhd
    fullHdRe.containsMatchIn(title) -> Quality.FullHd
    hdRe.containsMatchIn(title) -> Quality.Hd
    else -> null
}
