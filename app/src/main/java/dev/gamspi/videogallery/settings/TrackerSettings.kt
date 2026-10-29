package dev.gamspi.videogallery.settings

import dev.gamspi.videogallery.torrents.ProviderId

data class TrackerConfig(val enabled: Boolean, val baseUrl: String)

fun defaultTracker(id: ProviderId): TrackerConfig = when (id) {
    ProviderId.Rutor -> TrackerConfig(true, "https://rutor.info/")
    ProviderId.Apibay -> TrackerConfig(true, "https://apibay.org/")
    ProviderId.Yts -> TrackerConfig(false, "https://yts.lt/")
}

fun normalizeTrackerUrl(id: ProviderId, raw: String): String {
    var url = raw.trim()
    if (url.isEmpty()) return defaultTracker(id).baseUrl
    if (!url.contains("://")) url = "https://$url"
    if (!url.endsWith("/")) url += "/"
    return url
}
