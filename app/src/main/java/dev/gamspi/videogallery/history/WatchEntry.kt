package dev.gamspi.videogallery.history

import kotlinx.serialization.Serializable
import dev.gamspi.videogallery.catalog.model.MediaKind

/** Entries at or past this share of the duration count as watched and leave the history. */
const val FINISHED_PROGRESS = 0.90f

/** History keeps at most this many movies. */
const val MAX_HISTORY = 20

/** A started movie with the release it was played from and the saved position. */
@Serializable
data class WatchEntry(
    val movieId: Int,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val rating: Double? = null,
    val magnet: String = "",
    val torrentUrl: String = "",
    val imdb: String = "",
    /** PlaybackStore key of the played file. */
    val positionKey: String,
    val positionMs: Long,
    val durationMs: Long,
    /** Wall-clock time of the last update, ms. */
    val updatedAt: Long,
    val kind: MediaKind = MediaKind.Movie,
    val source: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val seasonEpisodes: Int? = null,
    val seasonCount: Int? = null,
) {
    val identity: String get() = "${source ?: "-"}:${kind}:${movieId}"

    /** Watched share in 0..1; 0 without a duration. */
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
}

/** Legacy entries without a source match any catalog, but never another media kind. */
internal fun WatchEntry.matchesIdentity(identity: String): Boolean {
    val parts = identity.split(':', limit = 3)
    return parts.size == 3 && parts[1] == kind.name && parts[2] == movieId.toString() &&
        (source == null || parts[0] == "-" || parts[0] == source)
}

/** Upsert by identity, drop watched, freshest first, cap the size. */
internal fun List<WatchEntry>.upserting(entry: WatchEntry): List<WatchEntry> {
    if (entry.durationMs <= 0) return this
    val rest = filterNot { it.matchesIdentity(entry.identity) }
    if (entry.progress >= FINISHED_PROGRESS) return rest
    return (rest + entry).sortedByDescending { it.updatedAt }.take(MAX_HISTORY)
}
