package dev.gamspi.videogallery.translate

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val TRANSLATION_CACHE_LIMIT_BYTES = 50L * 1024 * 1024

data class CacheKey(val fileId: Long, val target: TranslateLang, val modelVersion: String)

/** Runs the given block in a single database transaction. */
typealias TxRunner = suspend (suspend () -> Unit) -> Unit

/**
 * Persistent cache of translated cues with LRU eviction by total text size.
 * Failures never propagate (a miss or no-op instead); cancellation always does.
 */
class TranslationCache(
    private val dao: TranslationDao,
    private val tx: TxRunner,
    private val limitBytes: Long = TRANSLATION_CACHE_LIMIT_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun get(key: CacheKey): Map<Int, String> = withContext(dispatcher) {
        try {
            val rows = dao.cues(key.fileId, key.target, key.modelVersion)
            if (rows.isNotEmpty()) dao.touch(key.fileId, key.target, key.modelVersion, clock())
            rows.associate { it.cueIndex to it.text }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cache get failed", e)
            emptyMap()
        }
    }

    suspend fun put(key: CacheKey, cues: Map<Int, String>) {
        if (cues.isEmpty()) return
        withContext(dispatcher) {
            try {
                tx {
                    val f = key.fileId
                    val t = key.target
                    val v = key.modelVersion
                    dao.deleteCues(f, t, v)
                    dao.insertCues(cues.map { (i, text) -> TranslatedCueEntity(f, t, v, i, text) })
                    val bytes = cues.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
                    dao.upsertEntry(CacheEntryEntity(f, t, v, bytes, clock()))
                    // Evict least recently used entries (never the current one) until under the limit.
                    while (dao.totalBytes() > limitBytes) {
                        val victim = dao.oldestExcept(f, t, v) ?: break
                        dao.deleteCues(victim.fileId, victim.target, victim.modelVersion)
                        dao.deleteEntry(victim.fileId, victim.target, victim.modelVersion)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cache put failed", e)
            }
        }
    }

    suspend fun clear() {
        withContext(dispatcher) {
            try {
                tx {
                    dao.clearCues()
                    dao.clearEntries()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cache clear failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "TranslationCache"
    }
}
