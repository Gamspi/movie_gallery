package dev.gamspi.videogallery.ui.nav

import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.history.WatchEntry
import dev.gamspi.videogallery.history.encodeArg

private val META_PATTERN = PlaybackMeta.ARGS.joinToString("&") { "$it={$it}" }

internal const val MOVIE_ROUTE = "movie/{id}?kind={kind}"
internal const val RELEASES_ROUTE = "releases/{id}?kind={kind}&season={season}"

fun movieRoute(id: Int, kind: MediaKind = MediaKind.Movie): String = "movie/$id?kind=${kind.name}"

fun releasesRoute(id: Int, kind: MediaKind = MediaKind.Movie, season: Int? = null): String =
    "releases/$id?kind=${kind.name}&season=${season?.toString().orEmpty()}"

/** All stream/player args are optional strings with "" default. */
internal const val STREAM_BASE = "stream?title={title}"
internal val STREAM_ROUTE = "$STREAM_BASE&$META_PATTERN"
internal val STREAM_ARGS = listOf("title") + PlaybackMeta.ARGS
internal val PLAYER_ROUTE = "player?url={url}&title={title}&key={key}&$META_PATTERN"
internal val PLAYER_ARGS = listOf("url", "title", "key") + PlaybackMeta.ARGS

/** Stream screen route; [releaseTitle] is the torrent title shown while buffering. */
fun streamRoute(releaseTitle: String, meta: PlaybackMeta): String =
    "stream?title=${encodeArg(releaseTitle)}&${meta.query()}"

fun playerRoute(url: String, title: String, key: String, meta: PlaybackMeta = PlaybackMeta()): String =
    "player?url=${encodeArg(url)}&title=${encodeArg(title)}&key=${encodeArg(key)}&${meta.query()}"

/** Route that reopens the entry's release and resumes playback without asking. */
fun WatchEntry.streamRoute(): String = streamRoute(
    title,
    PlaybackMeta(
        movieId = movieId, movieTitle = title, movieYear = year, poster = posterUrl, rating = rating,
        magnet = magnet, torrent = torrentUrl, imdb = imdb, autoResume = true,
        kind = kind, source = source, season = season, episode = episode,
        seasonEpisodes = seasonEpisodes, seasonCount = seasonCount,
    ),
)
