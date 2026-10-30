package dev.gamspi.videogallery.player

private const val MIN_RESUME_MS = 60_000L

fun isFinished(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs * 100 > durationMs * 95

fun resumeFrom(savedMs: Long?, durationMs: Long): Long? {
    if (savedMs == null || savedMs < MIN_RESUME_MS) return null
    if (isFinished(savedMs, durationMs)) return null
    return savedMs
}

fun formatTime(ms: Long, durationMs: Long): String {
    val total = (if (ms < 0) 0 else ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (durationMs >= 3_600_000L) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(total / 60, s)
    }
}
