package dev.gamspi.videogallery.stream

data class StreamStallWarning(val sessionId: Long)

/** Call from one owner with monotonic time and an immutable demand identifier. */
class StallMonitor(private val timeoutMs: Long = 30_000) {
    init { require(timeoutMs >= 0) }

    private var waitingSinceMs: Long? = null
    private var demand: Any? = null
    private var bestProgress = 0L

    fun update(nowMs: Long, waiting: Boolean, demandId: Any?, progress: Long): Boolean {
        if (!waiting) {
            reset()
            return false
        }

        val since = waitingSinceMs
        if (since == null || demand != demandId) {
            waitingSinceMs = nowMs
            demand = demandId
            bestProgress = progress
        } else if (progress > bestProgress) {
            waitingSinceMs = nowMs
            bestProgress = progress
        }

        return nowMs - checkNotNull(waitingSinceMs) >= timeoutMs
    }

    fun reset() {
        waitingSinceMs = null
        demand = null
        bestProgress = 0L
    }
}
