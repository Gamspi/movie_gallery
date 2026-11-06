package dev.gamspi.videogallery.torrents

import java.net.URLEncoder

enum class ProviderId(val label: String) { Rutor("rutor"), Apibay("apibay"), Yts("YTS"), Rutracker("rutracker") }

data class TorrentResult(
    val title: String,
    val provider: ProviderId,
    val sizeBytes: Long,
    val seeders: Int,
    val leechers: Int,
    val quality: Quality?,
    val infoHash: String,
    val magnet: String,
    val torrentUrl: String? = null,
    /** Topic page for providers whose search results lack a hash (resolved on selection). */
    val topicUrl: String? = null,
)

/** Stable identity: info hash when known, otherwise the topic page. */
val TorrentResult.key: String
    get() = infoHash.lowercase().ifBlank { topicUrl ?: "${provider.name}:$title" }

/** True when the magnet must be fetched from the topic page before playback. */
val TorrentResult.needsResolve: Boolean
    get() = magnet.isBlank() && !topicUrl.isNullOrBlank()

private val trackers = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://tracker.openbittorrent.com:80/announce",
)

fun magnetOf(infoHash: String, name: String): String =
    "magnet:?xt=urn:btih:$infoHash&dn=${URLEncoder.encode(name, "UTF-8")}" +
        trackers.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
