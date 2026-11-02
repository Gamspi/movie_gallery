package dev.gamspi.videogallery.translate

import kotlinx.coroutines.CancellationException

const val EMBEDDED_CACHE_SIZE = 500

/**
 * Translates embedded cues as they arrive, with an in-memory LRU of phrases.
 * A result is dropped (null) when a newer cue or [invalidate] came while it was translated.
 */
class EmbeddedTranslation(
    private val translator: SubtitleTranslator,
    private val from: TranslateLang,
    private val capacity: Int = EMBEDDED_CACHE_SIZE,
) {
    private val to = from.other()
    private val lock = Any()
    private var generation = 0L
    private var failures = 0
    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > capacity
    }

    /** True after a series of consecutive failures; reset by the next success. */
    val failed: Boolean get() = synchronized(lock) { failures >= TRANSLATION_MAX_FAILURES }

    fun invalidate() {
        synchronized(lock) { generation++ }
    }

    /** Returns the translation, or null when superseded or failed. */
    suspend fun translate(text: String): String? {
        val gen = synchronized(lock) {
            cache[text]?.let { generation++; return it }
            ++generation
        }
        val result = try {
            text.lines().filter { it.isNotBlank() }.map { translator.translate(it.trim(), from, to) }
                .joinToString("\n")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            synchronized(lock) { failures++ }
            return null
        }
        return synchronized(lock) {
            failures = 0
            cache[text] = result
            result.takeIf { generation == gen }
        }
    }
}
