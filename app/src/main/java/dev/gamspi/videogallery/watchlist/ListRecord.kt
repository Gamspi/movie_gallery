package dev.gamspi.videogallery.watchlist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

private val imdbPattern = Regex("tt\\d{5,10}")
private val keyPattern = Regex("(tt\\d{5,10}|kp-\\d{1,10}|tmdb-(movie|tv)-\\d{1,10}|ai-[a-z0-9-]{1,80})")

fun watchlistKey(imdb: String?, kinopoiskId: Long?, tmdbId: Long?, series: Boolean): String? = when {
    imdb != null && imdbPattern.matches(imdb) -> imdb
    kinopoiskId != null && kinopoiskId in 1..9_999_999_999L -> "kp-$kinopoiskId"
    tmdbId != null && tmdbId in 1..9_999_999_999L -> "tmdb-${if (series) "tv" else "movie"}-$tmdbId"
    else -> null
}

@Serializable
enum class ListTable { Titles, Episodes, Positions }

@Serializable
data class ListRecord(
    val table: ListTable,
    val titleKey: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val data: JsonObject = JsonObject(emptyMap()),
    val season: Int = 0,
    val episode: Int = 0,
    val watched: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    val identity: String get() = if (table == ListTable.Titles) "$table:$titleKey" else "$table:$titleKey:$season:$episode"

    fun validated(): ListRecord = apply {
        require(keyPattern.matches(titleKey))
        require(updatedAt in 0 until 9_007_199_254_740_991L)
        require(season in 0..9999 && episode in 0..9999)
        require(positionMs in 0..9_007_199_254_740_991L && durationMs in 0..9_007_199_254_740_991L)
        require(table != ListTable.Titles || data.toString().toByteArray(Charsets.UTF_8).size <= 16384)
        require(table != ListTable.Titles || (season == 0 && episode == 0))
    }
}

@Serializable
data class ListSnapshot(
    val records: Map<String, ListRecord> = emptyMap(),
    val pending: Map<String, ListRecord> = emptyMap(),
    val cursor: Long = 0,
    val historyImported: Boolean = false,
)
