package dev.gamspi.videogallery.history

import dev.gamspi.videogallery.stream.EpisodeRef

class FakeSeriesStore : SeriesStore {
    override suspend fun awaitLoaded() = Unit

    private val releases = mutableMapOf<Pair<String, Int>, SeasonRelease>()
    private val progress = mutableMapOf<String, Map<EpisodeRef, Float>>()

    override fun release(seriesKey: String, season: Int): SeasonRelease? = releases[seriesKey to season]

    override fun saveRelease(seriesKey: String, season: Int, r: SeasonRelease) {
        val copy = r.copy(files = r.files.toList())
        (r.files.map { it.ref.season } + season).distinct().forEach { releases[seriesKey to it] = copy }
    }

    override fun progress(seriesKey: String): Map<EpisodeRef, Float> = progress[seriesKey].orEmpty().toMap()

    override fun saveProgress(seriesKey: String, ref: EpisodeRef, fraction: Float) {
        if (!fraction.isFinite()) return
        progress[seriesKey] = progress(seriesKey) + (ref to fraction.coerceIn(0f, 1f))
    }
}
