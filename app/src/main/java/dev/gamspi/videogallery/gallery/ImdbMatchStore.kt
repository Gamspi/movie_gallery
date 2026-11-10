package dev.gamspi.videogallery.gallery

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.settings.CatalogSource
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A cached lookup; [key] is null for a remembered miss. */
internal data class ImdbMatch(val key: RecommendationKey?)

/**
 * Disk cache of IMDb id to catalog id matches, so a foreign list title costs one lookup per catalog.
 * Holds only public ids and timestamps, never keys. Failed lookups are not stored.
 */
@Singleton
class ImdbMatchStore internal constructor(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE_NAME))

    private constructor(file: File) : this(
        read = { file.takeIf { it.exists() }?.readText() },
        write = { text ->
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        },
    )

    @Serializable
    private data class Entry(val id: Int? = null, val series: Boolean = false, val time: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry>? = null

    internal suspend fun get(source: CatalogSource, imdb: String, kind: MediaKind): ImdbMatch? = mutex.withLock {
        val entry = loaded()[key(source, imdb, kind)] ?: return@withLock null
        val ttl = if (entry.id == null) MISS_TTL_MS else HIT_TTL_MS
        if (now() - entry.time !in 0 until ttl) return@withLock null
        ImdbMatch(entry.id?.let { RecommendationKey(if (entry.series) MediaKind.Series else MediaKind.Movie, it) })
    }

    internal suspend fun put(source: CatalogSource, imdb: String, kind: MediaKind, match: RecommendationKey?) = mutex.withLock {
        val all = loaded()
        all[key(source, imdb, kind)] = Entry(match?.id, match?.kind == MediaKind.Series, now())
        if (all.size > MAX_ENTRIES) {
            all.entries.sortedBy { it.value.time }.take(all.size - MAX_ENTRIES).map { it.key }.forEach(all::remove)
        }
        val text = json.encodeToString(serializer, all.toMap())
        withContext(dispatcher) {
            // The cache is an optimisation: a failed write only costs a repeated lookup later.
            try { write(text) } catch (_: IOException) { }
        }
    }

    private suspend fun loaded(): MutableMap<String, Entry> = entries ?: withContext(dispatcher) {
        try {
            read()?.let { json.decodeFromString(serializer, it) }.orEmpty()
        } catch (_: IOException) {
            emptyMap()
        } catch (_: SerializationException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
    }.toMutableMap().also { entries = it }

    private fun key(source: CatalogSource, imdb: String, kind: MediaKind) = "${source.name}|${kind.name}|$imdb"

    private companion object {
        const val FILE_NAME = "imdb-matches.json"
        const val DAY_MS = 86_400_000L
        const val HIT_TTL_MS = 90 * DAY_MS
        const val MISS_TTL_MS = 7 * DAY_MS
        const val MAX_ENTRIES = 2000
    }
}
