package dev.gamspi.videogallery.history

import java.net.URLEncoder

/** Movie and release data carried as route args releases → stream → player. */
data class PlaybackMeta(
    val movieId: Int = 0,
    val movieTitle: String = "",
    val movieYear: Int? = null,
    val poster: String? = null,
    val rating: Double? = null,
    val magnet: String = "",
    val torrent: String = "",
    val imdb: String = "",
    /** Resume from the saved position without the «Продолжить/Сначала» offer. */
    val autoResume: Boolean = false,
) {
    /** Query part with every arg, URL-encoded; absent values are empty. */
    fun query(): String = listOf(
        "movieId" to if (movieId > 0) movieId.toString() else "",
        "movieTitle" to movieTitle,
        "movieYear" to movieYear?.toString().orEmpty(),
        "poster" to poster.orEmpty(),
        "rating" to rating?.toString().orEmpty(),
        "magnet" to magnet,
        "torrent" to torrent,
        "imdb" to imdb,
        "autoResume" to if (autoResume) "true" else "",
    ).joinToString("&") { (k, v) -> "$k=${encodeArg(v)}" }

    companion object {
        val ARGS = listOf("movieId", "movieTitle", "movieYear", "poster", "rating", "magnet", "torrent", "imdb", "autoResume")

        /** Reads args by name (e.g. from SavedStateHandle); blanks become defaults. */
        fun from(arg: (String) -> String?): PlaybackMeta {
            fun s(name: String) = arg(name)?.trim().orEmpty()
            return PlaybackMeta(
                movieId = s("movieId").toIntOrNull()?.takeIf { it > 0 } ?: 0,
                movieTitle = s("movieTitle"),
                movieYear = s("movieYear").toIntOrNull(),
                poster = s("poster").ifEmpty { null },
                rating = s("rating").toDoubleOrNull()?.takeIf { it > 0 },
                magnet = s("magnet"),
                torrent = s("torrent"),
                imdb = s("imdb"),
                autoResume = s("autoResume").toBoolean(),
            )
        }
    }
}

/** Percent-encodes a route arg; spaces become %20, never '+'. */
fun encodeArg(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
