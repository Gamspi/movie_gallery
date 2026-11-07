package dev.gamspi.videogallery.releases

import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.Quality
import dev.gamspi.videogallery.torrents.TorrentResult

/** Release list filter; dead and over-limit releases are hidden by default. */
data class ReleaseFilter(
    val quality: Quality? = null,
    val hiddenProviders: Set<ProviderId> = emptySet(),
    val showDead: Boolean = false,
    val showOverLimit: Boolean = false,
)

fun TorrentResult.isDead(): Boolean = seeders == 0

/** Unknown size (<= 0) counts as fitting. */
fun TorrentResult.isOverLimit(limitBytes: Long): Boolean = sizeBytes > 0 && sizeBytes > limitBytes

fun List<TorrentResult>.applyFilter(f: ReleaseFilter, limitBytes: Long): List<TorrentResult> = filter {
    (f.quality == null || it.quality == f.quality) &&
        it.provider !in f.hiddenProviders &&
        (f.showDead || !it.isDead()) &&
        (f.showOverLimit || !it.isOverLimit(limitBytes))
}

/** Providers present in the results, in [ProviderId.entries] order. */
fun List<TorrentResult>.presentProviders(): List<ProviderId> {
    val present = map { it.provider }.toSet()
    return ProviderId.entries.filter { it in present }
}

/** Per provider: releases that would be visible from it under the other filters. */
fun List<TorrentResult>.providerCounts(f: ReleaseFilter, limitBytes: Long): Map<ProviderId, Int> {
    val base = applyFilter(f.copy(hiddenProviders = emptySet()), limitBytes)
    return presentProviders().associateWith { id -> base.count { it.provider == id } }
}

/** Dead and over-limit counts within the current quality and provider selection. */
fun List<TorrentResult>.deadCount(f: ReleaseFilter): Int =
    count { (f.quality == null || it.quality == f.quality) && it.provider !in f.hiddenProviders && it.isDead() }

fun List<TorrentResult>.overLimitCount(f: ReleaseFilter, limitBytes: Long): Int =
    count {
        (f.quality == null || it.quality == f.quality) && it.provider !in f.hiddenProviders &&
            it.isOverLimit(limitBytes)
    }

/** One-line description of the active filter, e.g. «1080p · без apibay · скрыто 37». */
fun filterSummary(all: List<TorrentResult>, visibleCount: Int, f: ReleaseFilter): String {
    val parts = buildList {
        f.quality?.let { add(it.label) }
        val hidden = all.presentProviders().filter { it in f.hiddenProviders }
        if (hidden.isNotEmpty()) add("без " + hidden.joinToString(", ") { it.label })
        if (f.showDead) add("+ мёртвые")
        if (f.showOverLimit) add("+ больше лимита")
        val hiddenCount = all.size - visibleCount
        if (hiddenCount > 0) add("скрыто $hiddenCount")
    }
    return if (parts.isEmpty()) "Все раздачи" else parts.joinToString(" · ")
}
