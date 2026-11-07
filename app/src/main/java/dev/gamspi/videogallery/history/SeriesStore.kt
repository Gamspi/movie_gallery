package dev.gamspi.videogallery.history

import android.content.SharedPreferences
import dev.gamspi.videogallery.stream.EpisodeFile
import dev.gamspi.videogallery.stream.EpisodeRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class SeasonRelease(
    val magnet: String,
    val torrentUrl: String,
    val title: String,
    val files: List<EpisodeFile>,
)

/**
 * Callers must `awaitLoaded()` before deciding on reads: getters return empty
 * until the persisted data is loaded. Once loaded, writes are visible to reads immediately.
 */
interface SeriesStore {
    suspend fun awaitLoaded()
    fun release(seriesKey: String, season: Int): SeasonRelease?
    fun saveRelease(seriesKey: String, season: Int, r: SeasonRelease)
    fun progress(seriesKey: String): Map<EpisodeRef, Float>
    fun saveProgress(seriesKey: String, ref: EpisodeRef, fraction: Float)
}

fun seriesKey(source: String, id: Int): String = "$source:Series:$id"

@Serializable
private data class EpisodeProgress(val ref: EpisodeRef, val fraction: Float)

@Serializable
private data class SeriesData(
    val releases: Map<Int, SeasonRelease> = emptyMap(),
    val progress: List<EpisodeProgress> = emptyList(),
)

/**
 * Reads use a memory snapshot (empty until loaded; call [awaitLoaded] first). After loading,
 * writes update the snapshot synchronously; prefs work is serialized on IO.
 */
class PersistentSeriesStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SeriesStore {
    private val serial = dispatcher.limitedParallelism(1)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), SeriesData.serializer())
    @Volatile private var snapshot: Map<String, SeriesData> = emptyMap()
    private val lock = Any()
    @Volatile private var loaded = false
    private var persisted: Map<String, SeriesData> = emptyMap()

    init {
        scope.launch(serial) { ensureLoaded() }
    }

    override fun release(seriesKey: String, season: Int): SeasonRelease? =
        snapshot[seriesKey]?.releases?.get(season)?.let { it.copy(files = it.files.toList()) }

    override fun progress(seriesKey: String): Map<EpisodeRef, Float> =
        snapshot[seriesKey]?.progress.orEmpty().associate { it.ref to it.fraction }

    override fun saveRelease(seriesKey: String, season: Int, r: SeasonRelease) {
        val release = r.copy(files = r.files.toList())
        val seasons = (release.files.map { it.ref.season } + season).distinct()
        mutate(seriesKey) { it.copy(releases = it.releases + seasons.associateWith { release }) }
    }

    override fun saveProgress(seriesKey: String, ref: EpisodeRef, fraction: Float) {
        if (!fraction.isFinite()) return
        val progress = EpisodeProgress(ref, fraction.coerceIn(0f, 1f))
        mutate(seriesKey) { it.copy(progress = it.progress.filterNot { p -> p.ref == ref } + progress) }
    }

    override suspend fun awaitLoaded() {
        withContext(serial) { ensureLoaded() }
    }

    private fun mutate(key: String, change: (SeriesData) -> SeriesData) {
        fun apply() {
            snapshot = snapshot + (key to change(snapshot[key] ?: SeriesData()))
        }
        // Loaded: update memory now. Not loaded: the queued job merges onto loaded data.
        val applied = synchronized(lock) { loaded.also { if (it) apply() } }
        scope.launch(serial) {
            ensureLoaded()
            if (!applied) synchronized(lock) { apply() }
            val current = snapshot
            if (current == persisted) return@launch
            try {
                write(json.encodeToString(serializer, current))
                persisted = current
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the memory snapshot on a disk failure.
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        val data = try {
            read()?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(serializer, it) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        synchronized(lock) {
            snapshot = data
            persisted = data
            loaded = true
        }
    }

    companion object {
        private const val KEY = "series"

        fun fromPrefs(openPrefs: () -> SharedPreferences, scope: CoroutineScope): PersistentSeriesStore {
            val prefs by lazy(openPrefs)
            return PersistentSeriesStore(
                read = { prefs.getString(KEY, null) },
                write = { prefs.edit().putString(KEY, it).apply() },
                scope = scope,
            )
        }
    }
}
