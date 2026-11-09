package dev.gamspi.videogallery.watchlist

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One exchange at a time; durable acknowledgements happen only after successful requests. */
class ListSync(private val store: LocalListStore, private val api: ListApi) {
    private val mutex = Mutex()

    suspend fun exchange() = mutex.withLock {
        // Bound the outgoing pass so continuous playback writes cannot starve reads.
        val outgoing = store.snapshot().pending.values.toList()
        for (batch in outgoing.chunked(50)) {
            currentCoroutineContext().ensureActive()
            api.push(batch)
            store.acknowledge(batch)
        }
        do {
            currentCoroutineContext().ensureActive()
            val since = store.snapshot().cursor
            val page = api.changes(since)
            require(page.next >= since && (!page.more || page.next > since))
            store.mergePage(since, page.next, page.rows)
        } while (page.more)
    }
}
