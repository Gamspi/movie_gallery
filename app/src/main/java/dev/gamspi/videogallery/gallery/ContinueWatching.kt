package dev.gamspi.videogallery.gallery

import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MediaKind
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
    kind = kind,
)

/** «S02E05» for a series entry with a known episode; null otherwise. */
internal fun WatchEntry.episodeCaption(): String? {
    val s = season ?: return null
    val e = episode ?: return null
    return if (kind == MediaKind.Series) dev.gamspi.videogallery.player.episodeCaption(s, e) else null
}

/** The active catalog's entries; legacy records belong to any source. */
internal fun historyForSource(entries: List<WatchEntry>, source: String): List<WatchEntry> =
    entries.filter { it.source == null || it.source == source }.distinctBy { it.kind to it.movieId }

/** Media identity to watched share, built once per history change. */
internal fun progressByMovie(entries: List<WatchEntry>): Map<Pair<MediaKind, Int>, Float> =
    entries.associate { (it.kind to it.movieId) to it.progress }

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
