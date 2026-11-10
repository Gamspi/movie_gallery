package dev.gamspi.videogallery.watchlist

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamspi.videogallery.history.WatchEntry
import dev.gamspi.videogallery.history.WatchHistoryStore
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.settings.SecretStore
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

internal fun importWatchEntry(entry: WatchEntry): List<ListRecord> {
    val series = entry.kind == MediaKind.Series
    val key = watchlistKey(entry.imdb,
        entry.movieId.toLong().takeIf { entry.source in listOf("Kinopoisk", "PoiskKino") },
        entry.movieId.toLong().takeIf { entry.source == "Tmdb" }, series) ?: return emptyList()
    val time = entry.updatedAt.coerceAtLeast(0)
    val data = buildJsonObject {
        put("key", key); put("title_ru", entry.title); put("type", if (series) "tv_show" else "movie")
        put("status", if (!series && entry.progress >= .9f) "watched" else "watching")
        put("notify", series)
        entry.year?.let { put("year", it) }
        entry.posterUrl?.let { put("poster_url", it) }
        entry.source?.let { put("catalog_source", it) }
        put("catalog_id", entry.movieId)
        if (entry.imdb.isNotBlank()) put("imdb_id", entry.imdb)
    }
    val title = ListRecord(ListTable.Titles, key, time, data = data)
    if (series && (entry.season == null || entry.episode == null)) return listOf(title)
    val position = ListRecord(ListTable.Positions, key, time,
        season = if (series) entry.season!! else 0, episode = if (series) entry.episode!! else 0,
        positionMs = entry.positionMs.coerceAtLeast(0), durationMs = entry.durationMs.coerceAtLeast(0))
    return if (series && entry.progress >= .9f) listOf(title, position,
        ListRecord(ListTable.Episodes, key, time, season = position.season, episode = position.episode, watched = true))
    else listOf(title, position)
}

interface PlaybackList {
    /** [syncNow] is false for routine position ticks, which are exchanged less often. */
    fun record(entry: WatchEntry, seriesDone: Boolean, syncNow: Boolean)
    /** True when this movie or the entry's season has no score yet. */
    suspend fun needsRating(entry: WatchEntry): Boolean = false
    fun rate(entry: WatchEntry, rating: Int) {}
    suspend fun savedPosition(entry: WatchEntry): Long?
}

@Singleton
class ListSyncService @Inject constructor(
    @ApplicationContext context: Context,
    private val secrets: SecretStore,
    private val history: WatchHistoryStore,
) : PlaybackList {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playback = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    // One connection pool for every exchange; no proxy interceptor, no key logging.
    private val http by lazy { OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build() }
    private val positionSync = SyncThrottle(POSITION_SYNC_MS, SystemClock::elapsedRealtime)
    val store = LocalListStore.fromFile(File(context.filesDir, "watchlist.json"))
    private val active = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = active.asStateFlow()
    private val message = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = message.asStateFlow()
    private val runner = ListSyncRunner(exchange = {
        val url = secrets.get(URL)?.trim().orEmpty().toHttpUrl()
        require(url.isHttps)
        val api = HttpListApi(url, secrets.get(KEY).orEmpty(), http)
        history.awaitLoaded()
        store.importHistory(history.entries.value.flatMap(::importWatchEntry))
        ListSync(store, api).exchange()
    })
    val status = runner.status
    private var started = false
    private val initialized = CompletableDeferred<Unit>()
    private val settingsMutex = kotlinx.coroutines.sync.Mutex()

    @Synchronized fun start() {
        if (started) return
        started = true
        scope.launch {
            for (event in playback) {
                try {
                    event()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message.value = "Не удалось сохранить прогресс списка" }
            }
        }
        runner.start(scope, active)
        scope.launch {
            try { active.value = secrets.get(ENABLED)?.toBooleanStrictOrNull() ?: false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "Не удалось прочитать настройки синхронизации" }
            finally { initialized.complete(Unit) }
        }
    }

    suspend fun toggle() = withContext(Dispatchers.IO) {
        initialized.await()
        settingsMutex.lock()
        try {
            val next = !active.value
            try {
                secrets.put(ENABLED, next.toString())
                active.value = next
                message.value = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "Не удалось сохранить настройку синхронизации" }
        } finally { settingsMutex.unlock() }
    }

    fun request() = runner.request()

    private var pullJob: Job? = null

    /** Exchanges on entering the foreground, periodically while there, and once on leaving. */
    @Synchronized fun setForeground(on: Boolean) {
        if (on == (pullJob != null)) return
        if (on) {
            runner.request()
            pullJob = scope.launch {
                while (true) { delay(FOREGROUND_PULL_MS); runner.request() }
            }
        } else {
            pullJob?.cancel()
            pullJob = null
            runner.request()
        }
    }

    fun retryNow() = runner.retryNow()

    fun report(text: String) { message.value = text }

    fun loadLocal() {
        scope.launch {
            try { store.snapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "Не удалось прочитать личный список" }
        }
    }

    fun changeTitle(seed: ListRecord, field: String, value: JsonElement) {
        scope.launch {
            try {
                store.changeTitle(seed.copy(updatedAt = System.currentTimeMillis()), field, value)
                runner.request()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "Не удалось сохранить личный список" }
        }
    }

    override fun record(entry: WatchEntry, seriesDone: Boolean, syncNow: Boolean) {
        playback.trySend {
            store.recordPlayback(importWatchEntry(entry), seriesDone)
            if (positionSync.allow(syncNow)) runner.request()
        }
    }

    override suspend fun needsRating(entry: WatchEntry): Boolean {
        val seed = importWatchEntry(entry).firstOrNull() ?: return false
        return ratingMissing(store.snapshot().records[seed.identity]?.takeUnless { it.deleted }, entry.season)
    }

    // Same queue as progress, so a score never overtakes the playback write before it.
    override fun rate(entry: WatchEntry, rating: Int) {
        val seed = importWatchEntry(entry).firstOrNull() ?: return
        val season = entry.season
        playback.trySend {
            if (entry.kind == MediaKind.Series && season != null) store.rateSeason(seed.copy(updatedAt = System.currentTimeMillis()), season, rating)
            else store.changeTitle(seed.copy(updatedAt = System.currentTimeMillis()), "my_rating", JsonPrimitive(rating))
            runner.request()
        }
    }

    fun rateSeason(seed: ListRecord, season: Int, rating: Int) = saveAndSync { store.rateSeason(seed.copy(updatedAt = System.currentTimeMillis()), season, rating) }

    fun markEpisodes(seed: ListRecord, episodes: List<Pair<Int, Int>>, watched: Boolean) =
        saveAndSync { store.markEpisodes(seed.copy(updatedAt = System.currentTimeMillis()), episodes, watched) }

    private fun saveAndSync(save: suspend () -> Unit) {
        scope.launch {
            try {
                save()
                runner.request()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "Не удалось сохранить личный список" }
        }
    }

    override suspend fun savedPosition(entry: WatchEntry): Long? {
        val identity = importWatchEntry(entry).firstOrNull { it.table == ListTable.Positions }?.identity ?: return null
        return store.snapshot().records[identity]?.takeUnless { it.deleted }?.positionMs
    }

    companion object {
        const val URL = "list_url"
        const val KEY = "list_key"
        const val ENABLED = "list_sync_enabled"
        private const val POSITION_SYNC_MS = 300_000L
        private const val FOREGROUND_PULL_MS = 300_000L
    }
}
