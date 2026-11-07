package dev.gamspi.videogallery.history

import android.content.SharedPreferences
import dev.gamspi.videogallery.stream.EpisodeFile
import dev.gamspi.videogallery.stream.EpisodeRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

interface SeriesStore {
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

/** Reads use a memory snapshot (empty until loaded); prefs work is serialized on IO. */
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
    private var loaded = false

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

    private fun mutate(key: String, change: (SeriesData) -> SeriesData) {
        scope.launch(serial) {
            ensureLoaded()
            val next = snapshot + (key to change(snapshot[key] ?: SeriesData()))
            if (next == snapshot) return@launch
            snapshot = next
            try {
                write(json.encodeToString(serializer, next))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the memory snapshot on a disk failure.
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        snapshot = try {
            read()?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(serializer, it) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        loaded = true
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
