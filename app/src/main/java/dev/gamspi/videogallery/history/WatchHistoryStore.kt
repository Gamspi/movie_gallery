package dev.gamspi.videogallery.history

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** «Continue watching» history; fresh entries first. */
interface WatchHistoryStore {
    val entries: StateFlow<List<WatchEntry>>

    /** Inserts or replaces the movie's entry; watched or duration-less entries are not kept. */
    fun upsert(entry: WatchEntry)

    fun remove(identity: String)

    /** Suspends until the persisted history has been read into [entries]. */
    suspend fun awaitLoaded()
}

private val json = Json { ignoreUnknownKeys = true }

internal fun encodeHistory(list: List<WatchEntry>): String =
    json.encodeToString(ListSerializer(WatchEntry.serializer()), list)

/** Corrupted text yields an empty list; invalid items are skipped one by one. */
internal fun decodeHistory(text: String?): List<WatchEntry> {
    if (text.isNullOrBlank()) return emptyList()
    val array = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
    return array.mapNotNull { item: JsonElement ->
        runCatching { json.decodeFromJsonElement(WatchEntry.serializer(), item) }.getOrNull()
            ?.takeIf { it.movieId > 0 && it.durationMs > 0 && it.positionKey.isNotBlank() }
    }.sortedByDescending { it.updatedAt }.take(MAX_HISTORY)
}

/** Keeps the history as one JSON string; all reads and writes run serially off the UI thread. */
class PersistentWatchHistoryStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WatchHistoryStore {
    private val serial = dispatcher.limitedParallelism(1)
    private val _entries = MutableStateFlow<List<WatchEntry>>(emptyList())
    override val entries: StateFlow<List<WatchEntry>> = _entries.asStateFlow()
    private var loaded = false

    init {
        scope.launch(serial) { ensureLoaded() }
    }

    override fun upsert(entry: WatchEntry) = mutate { it.upserting(entry) }

    override fun remove(identity: String) = mutate { list -> list.filterNot { it.matchesIdentity(identity) } }

    override suspend fun awaitLoaded() {
        withContext(serial) { ensureLoaded() }
    }

    private fun mutate(change: (List<WatchEntry>) -> List<WatchEntry>) {
        scope.launch(serial) {
            ensureLoaded()
            val next = change(_entries.value)
            if (next == _entries.value) return@launch
            _entries.value = next
            try {
                write(encodeHistory(next))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the in-memory history when the disk write fails.
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _entries.value = try {
            decodeHistory(read())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val KEY = "watch_history"

        /** [openPrefs] is called lazily on the IO dispatcher, never on the caller's thread. */
        fun fromPrefs(openPrefs: () -> SharedPreferences, scope: CoroutineScope): PersistentWatchHistoryStore {
            val prefs by lazy(openPrefs)
            return PersistentWatchHistoryStore(
                read = { prefs.getString(KEY, null) },
                write = { prefs.edit().putString(KEY, it).apply() },
                scope = scope,
            )
        }
    }
}
