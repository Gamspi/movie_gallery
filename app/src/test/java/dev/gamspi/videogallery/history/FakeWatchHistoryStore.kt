package dev.gamspi.videogallery.history

import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory history applying the real upsert rules. */
class FakeWatchHistoryStore(initial: List<WatchEntry> = emptyList()) : WatchHistoryStore {
    override val entries = MutableStateFlow(initial)
    override fun upsert(entry: WatchEntry) { entries.value = entries.value.upserting(entry) }
    override fun remove(movieId: Int) { entries.value = entries.value.filterNot { it.movieId == movieId } }
}
