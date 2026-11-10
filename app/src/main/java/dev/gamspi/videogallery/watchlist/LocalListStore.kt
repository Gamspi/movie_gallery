package dev.gamspi.videogallery.watchlist

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Owns records, outgoing revisions and the read cursor in one durable snapshot. */
class LocalListStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = MutableStateFlow(ListSnapshot())
    val snapshots: StateFlow<ListSnapshot> = state.asStateFlow()
    private var loaded = false

    suspend fun snapshot(): ListSnapshot = transaction { it }

    suspend fun changeTitle(seed: ListRecord, field: String, value: kotlinx.serialization.json.JsonElement) = transaction { current ->
        require(seed.table == ListTable.Titles)
        require(field in setOf("status", "my_rating", "notify", "deleted"))
        if (field == "status") require(value.toString().trim('"') in setOf("unwatched", "watching", "watched", "dropped", "dismissed"))
        if (field == "my_rating") require(value.toString().toIntOrNull() in 1..10)
        if (field in setOf("notify", "deleted")) require(value.toString() in setOf("true", "false"))
        val old = current.records[seed.identity]
        // A tombstone's fields must not leak into a title added again.
        val data = (seed.data + old?.takeUnless { it.deleted }?.data.orEmpty()).toMutableMap()
        // An explicit card edit also carries the current catalog premiere.
        seed.data["premiere_date"]?.let { data["premiere_date"] = it }
        if (field != "deleted") data[field] = value
        if (field == "my_rating" && data["type"].toString().trim('"') !in setOf("tv_show", "animated_series"))
            data["status"] = kotlinx.serialization.json.JsonPrimitive("watched")
        val row = seed.copy(data = kotlinx.serialization.json.JsonObject(data),
            deleted = field == "deleted" && value.toString() == "true",
            updatedAt = maxOf(seed.updatedAt, (old?.updatedAt ?: -1) + 1)).validated()
        current.copy(records = current.records + (row.identity to row), pending = current.pending + (row.identity to row))
    }

    /** Season score and the series average commit together; the status only changes for a new title. */
    suspend fun rateSeason(seed: ListRecord, season: Int, rating: Int) = transaction { current ->
        require(seed.table == ListTable.Titles && season in 0..9999 && rating in 1..10)
        val old = current.records[seed.identity]
        val live = old?.takeUnless { it.deleted }
        val row = seed.copy(data = withSeasonRating(seed.data + live?.data.orEmpty(), season, rating, live == null),
            deleted = false, updatedAt = maxOf(seed.updatedAt, (old?.updatedAt ?: -1) + 1)).validated()
        current.copy(records = current.records + (row.identity to row), pending = current.pending + (row.identity to row))
    }

    /** Episode marks and, for an unknown title, its record commit together. */
    suspend fun markEpisodes(seed: ListRecord, episodes: List<Pair<Int, Int>>, watched: Boolean) = transaction { current ->
        require(seed.table == ListTable.Titles)
        if (episodes.isEmpty()) return@transaction current
        var next = current
        if (next.records[seed.identity]?.deleted != false) {
            val title = seed.copy(deleted = false, updatedAt = maxOf(seed.updatedAt, (next.records[seed.identity]?.updatedAt ?: -1) + 1),
                data = kotlinx.serialization.json.JsonObject(seed.data + mapOf(
                    "status" to kotlinx.serialization.json.JsonPrimitive("watching"),
                    "notify" to kotlinx.serialization.json.JsonPrimitive(true)))).validated()
            next = next.copy(records = next.records + (title.identity to title), pending = next.pending + (title.identity to title))
        }
        episodes.forEach { (season, episode) ->
            val row = ListRecord(ListTable.Episodes, seed.titleKey, seed.updatedAt, season = season, episode = episode, watched = watched)
            val revision = row.copy(updatedAt = maxOf(row.updatedAt, (next.records[row.identity]?.updatedAt ?: -1) + 1)).validated()
            next = next.copy(records = next.records + (revision.identity to revision), pending = next.pending + (revision.identity to revision))
        }
        next
    }

    suspend fun recordPlayback(rows: List<ListRecord>, seriesDone: Boolean) = transaction { current ->
        var next = current
        rows.forEach { incoming ->
            incoming.validated()
            val old = next.records[incoming.identity]
            var row = incoming
            if (row.table == ListTable.Titles) {
                val data = incoming.data.toMutableMap()
                // A tombstone must not bring its old fields back
                old?.takeUnless { it.deleted }?.data?.let { data.putAll(it) }
                data["status"] = kotlinx.serialization.json.JsonPrimitive(
                    if (seriesDone) "watched" else incoming.data.getValue("status").toString().trim('"'))
                row = row.copy(data = kotlinx.serialization.json.JsonObject(data))
                if (old != null && !old.deleted && old.data == row.data) return@forEach
            }
            row = row.copy(updatedAt = maxOf(row.updatedAt, (old?.updatedAt ?: -1) + 1)).validated()
            next = next.copy(records = next.records + (row.identity to row), pending = next.pending + (row.identity to row))
        }
        next
    }

    suspend fun edit(record: ListRecord) = transaction { current ->
        record.validated()
        val old = current.records[record.identity]
        val revision = record.copy(updatedAt = maxOf(record.updatedAt, (old?.updatedAt ?: -1) + 1)).validated()
        current.copy(records = current.records + (revision.identity to revision), pending = current.pending + (revision.identity to revision))
    }

    suspend fun importHistory(records: List<ListRecord>) = transaction { current ->
        if (current.historyImported) current else {
            var next = current
            records.forEach { record ->
                record.validated()
                if (record.identity !in next.records) next = next.copy(
                    records = next.records + (record.identity to record),
                    pending = next.pending + (record.identity to record),
                )
            }
            next.copy(historyImported = true)
        }
    }

    suspend fun pending(limit: Int = 100): List<ListRecord> {
        require(limit in 1..500)
        return snapshot().pending.values.take(limit)
    }

    /** A newer local edit made while the request was in flight stays queued. */
    suspend fun acknowledge(sent: List<ListRecord>) = transaction { current ->
        val acknowledged = sent.filter { current.pending[it.identity] == it }.map { it.identity }.toSet()
        current.copy(pending = current.pending - acknowledged)
    }

    /** The caller supplies the cursor used for this page, never the batch response seq. */
    suspend fun mergePage(since: Long, next: Long, rows: List<ListRecord>) = transaction { current ->
        require(since == current.cursor && next >= since)
        var records = current.records
        rows.forEach { remote ->
            remote.validated()
            val local = records[remote.identity]
            if (local == null || remote.updatedAt > local.updatedAt) records = records + (remote.identity to remote)
        }
        current.copy(records = records, cursor = next)
    }

    private suspend fun transaction(change: (ListSnapshot) -> ListSnapshot): ListSnapshot = withContext(dispatcher) {
        mutex.withLock {
            if (!loaded) {
                val restored = read()?.let { json.decodeFromString<ListSnapshot>(it) } ?: ListSnapshot()
                require(restored.cursor >= 0)
                (restored.records + restored.pending).forEach { (id, row) -> require(id == row.validated().identity) }
                state.value = restored
                loaded = true
            }
            val next = change(state.value)
            if (next != state.value) {
                write(json.encodeToString(ListSnapshot.serializer(), next))
                state.value = next
            }
            state.value
        }
    }

    companion object {
        fun fromFile(file: File): LocalListStore {
            val atomic = AtomicFile(file)
            return LocalListStore(
                read = { if (!file.exists() && !File(file.path + ".bak").exists()) null else atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) } },
                write = { text ->
                    val stream = atomic.startWrite()
                    try {
                        stream.write(text.toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(stream)
                    } catch (error: Throwable) {
                        atomic.failWrite(stream)
                        throw error
                    }
                },
            )
        }
    }
}
