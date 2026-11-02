package dev.gamspi.videogallery.translate

import dev.gamspi.videogallery.subs.SubtitleTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val TRANSLATION_BATCH = 20

/** Consecutive cue failures after which translation stops with [TrackTranslation.failed]. */
const val TRANSLATION_MAX_FAILURES = 5

/**
 * Translates a whole track in the background, starting from the playback position.
 * Batch order is rebuilt from the latest [seek] position; done or failed cues are never resent.
 */
class TrackTranslation(
    track: SubtitleTrack,
    private val key: CacheKey,
    private val from: TranslateLang,
    private val translator: SubtitleTranslator,
    private val cache: TranslationCache,
    scope: CoroutineScope,
    startPositionMs: Long = 0L,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val to = from.other()
    private val cues = track.cues

    private val _translated = MutableStateFlow(SubtitleTrack(emptyList()))
    val translated: StateFlow<SubtitleTrack> = _translated.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    @Volatile
    private var positionMs: Long = startPositionMs

    private val job = scope.launch(dispatcher) { run() }

    fun seek(positionMs: Long) {
        this.positionMs = positionMs
    }

    fun cancel() {
        job.cancel()
    }

    private suspend fun run() {
        // Only the worker touches these.
        val done = HashMap<Int, String>()
        cache.get(key).forEach { (i, text) -> if (i in cues.indices) done[i] = text }
        val pending = cues.indices.filterTo(HashSet()) { it !in done && cues[it].text.isNotBlank() }
        if (done.isNotEmpty()) publish(done)

        var failures = 0
        while (pending.isNotEmpty()) {
            val batch = nextBatch(pending)
            var added = false
            for (i in batch) {
                pending.remove(i)
                val result = try {
                    translateCue(cues[i].text)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (result == null) {
                    if (++failures >= TRANSLATION_MAX_FAILURES) {
                        if (added) save(done)
                        _failed.value = true
                        return
                    }
                } else {
                    failures = 0
                    done[i] = result
                    added = true
                }
            }
            if (added) save(done)
        }
    }

    private suspend fun save(done: Map<Int, String>) {
        publish(done)
        // put replaces all cues of the key, so pass the full map.
        cache.put(key, HashMap(done))
    }

    private fun publish(done: Map<Int, String>) {
        _translated.value = SubtitleTrack(done.map { (i, text) -> cues[i].copy(text = text) })
    }

    /** Next pending indices from the current position to the end, then wrapping to the start. */
    private fun nextBatch(pending: Set<Int>): List<Int> {
        val first = firstIndexAt(positionMs)
        return ((first until cues.size).asSequence() + (0 until first).asSequence())
            .filter { it in pending }
            .take(TRANSLATION_BATCH)
            .toList()
    }

    /** First cue that starts at or after [pos], stepping back over cues still active at [pos]. */
    private fun firstIndexAt(pos: Long): Int {
        var i = cues.indexOfFirst { it.startMs >= pos }.let { if (it < 0) cues.size else it }
        while (i > 0 && cues[i - 1].endMs > pos) i--
        return i
    }

    /** Translates non-blank lines one by one and joins them with a newline. */
    private suspend fun translateCue(text: String): String {
        val out = ArrayList<String>()
        for (line in text.lines()) {
            if (line.isNotBlank()) out += translator.translate(line.trim(), from, to)
        }
        return out.joinToString("\n")
    }
}
