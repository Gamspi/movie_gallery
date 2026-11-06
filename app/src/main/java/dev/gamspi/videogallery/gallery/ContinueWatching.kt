package dev.gamspi.videogallery.gallery

import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.history.WatchEntry

/** Stable LazyColumn/focus key of the «Продолжить просмотр» row. */
internal const val CONTINUE_ROW_KEY = "continue"

/** Stable key of a catalog row; independent of the continue row's presence. */
internal fun catalogRowKey(index: Int): String = "row-$index"

/** Card data for a history entry. */
internal fun WatchEntry.toMovie(): Movie = Movie(
    id = movieId,
    title = title,
    originalTitle = "",
    year = year,
    posterUrl = posterUrl,
    overview = "",
    rating = rating,
)

/** Movie id to watched share, built once per history change. */
internal fun progressByMovie(entries: List<WatchEntry>): Map<Int, Float> =
    entries.associate { it.movieId to it.progress }

/** A card position: row key and the movie id inside the row. */
internal data class CardKey(val row: String, val movieId: Int)

/**
 * Card to focus: [wanted] when it still exists, else the first card of its row,
 * else the first card of the first non-empty row. [rows] are (row key, movie ids) in screen order.
 */
internal fun resolveFocusTarget(rows: List<Pair<String, List<Int>>>, wanted: CardKey?): CardKey? {
    val first = rows.firstOrNull { it.second.isNotEmpty() }?.let { CardKey(it.first, it.second.first()) }
    if (wanted == null) return first
    val row = rows.firstOrNull { it.first == wanted.row } ?: return first
    return when {
        wanted.movieId in row.second -> wanted
        row.second.isNotEmpty() -> CardKey(row.first, row.second.first())
        else -> first
    }
}
