package dev.gamspi.videogallery.subs

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** Immutable list of cues sorted by start time with fast lookup by playback position. */
class SubtitleTrack(cues: List<SubtitleCue>) {
    val cues: List<SubtitleCue> = cues.sortedBy { it.startMs }

    /** Longest cue duration; bounds how far back [textAt] must look for overlapping cues. */
    private val maxDurationMs: Long = this.cues.maxOfOrNull { it.endMs - it.startMs } ?: 0L

    /** Text of all cues active at [positionMs] (interval is [start, end)), joined by newline; null if none. */
    fun textAt(positionMs: Long): String? {
        if (cues.isEmpty()) return null
        // Last cue with startMs <= positionMs.
        var lo = 0
        var hi = cues.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) {
                last = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (last < 0) return null
        var first = last
        while (first > 0 && cues[first - 1].startMs + maxDurationMs > positionMs) first--
        val active = (first..last).map { cues[it] }.filter { positionMs < it.endMs }
        return if (active.isEmpty()) null else active.joinToString("\n") { it.text }
    }
}
