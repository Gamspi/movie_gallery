package dev.gamspi.videogallery.torrents

private val RANGE_PATTERNS = listOf(
    Regex("(?<![A-Za-z])S(\\d{1,2})\\s*[-\u2013]\\s*S?(\\d{1,2})(?!\\d)", RegexOption.IGNORE_CASE),
    Regex("(\\d{1,2})\\s*[-\u2013]\\s*(\\d{1,2})\\s*(?:\u0441\u0435\u0437\u043e\u043d|season)", RegexOption.IGNORE_CASE),
    Regex("(?:\u0441\u0435\u0437\u043e\u043d\\p{L}*|seasons?)\\s*:?\\s*(\\d{1,2})\\s*[-\u2013]\\s*(\\d{1,2})(?!\\d)", RegexOption.IGNORE_CASE),
)

private val SINGLE_PATTERNS = listOf(
    Regex("(?<![A-Za-z])S(\\d{1,2})(?!\\d)", RegexOption.IGNORE_CASE),
    Regex("(\\d{1,2})\\s*\u0441\u0435\u0437\u043e\u043d", RegexOption.IGNORE_CASE),
    Regex("(?:\u0441\u0435\u0437\u043e\u043d|season)\\s*:?\\s*(\\d{1,2})(?!\\d)", RegexOption.IGNORE_CASE),
)

/** 0: the title covers [season]; 1: no season info; 2: only other seasons. */
fun seasonRank(title: String, season: Int): Int {
    val ranges = RANGE_PATTERNS.flatMap { re ->
        re.findAll(title).map { it.groupValues[1].toInt()..it.groupValues[2].toInt() }.toList()
    }
    val singles = SINGLE_PATTERNS.flatMap { re ->
        re.findAll(title).map { it.groupValues[1].toInt()..it.groupValues[1].toInt() }.toList()
    }
    val all = ranges + singles
    if (all.isEmpty()) return 1
    return if (all.any { season in it }) 0 else 2
}
