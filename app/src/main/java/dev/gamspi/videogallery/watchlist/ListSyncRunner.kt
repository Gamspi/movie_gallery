package dev.gamspi.videogallery.watchlist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ListSyncStatus {
    data object Disabled : ListSyncStatus
    data object Idle : ListSyncStatus
    data object Running : ListSyncStatus
    data class Unavailable(val since: Long) : ListSyncStatus
}

/** Limits exchanges caused by routine position ticks; forced calls always pass. */
class SyncThrottle(private val intervalMs: Long, private val now: () -> Long) {
    private var last: Long? = null

    fun allow(force: Boolean): Boolean {
        val time = now()
        val previous = last
        if (!force && previous != null && time - previous < intervalMs) return false
        last = time
        return true
    }
}

/** Application-scoped scheduler; disabling cancels the entire active exchange. */
class ListSyncRunner(
    private val exchange: suspend () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val state = MutableStateFlow<ListSyncStatus>(ListSyncStatus.Disabled)
    val status: StateFlow<ListSyncStatus> = state.asStateFlow()

    private val manual = Channel<Unit>(Channel.CONFLATED)

    /** Automatic request: waits for any running backoff. */
    fun request() { requests.trySend(Unit) }

    /** User-initiated request: also ends a running backoff. */
    fun retryNow() {
        manual.trySend(Unit)
        requests.trySend(Unit)
    }

    fun start(scope: CoroutineScope, enabled: StateFlow<Boolean>): Job = scope.launch {
        enabled.collectLatest { on ->
            if (!on) {
                state.value = ListSyncStatus.Disabled
                return@collectLatest
            }
            request()
            var failedSince: Long? = null
            var failures = 0
            for (signal in requests) {
                state.value = ListSyncStatus.Running
                try {
                    exchange()
                    failedSince = null
                    failures = 0
                    state.value = ListSyncStatus.Idle
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    val since = failedSince ?: now().also { failedSince = it }
                    state.value = ListSyncStatus.Unavailable(since)
                    val minutes = listOf(1L, 5L, 30L, 120L)[failures.coerceAtMost(3)]
                    failures = (failures + 1).coerceAtMost(3)
                    // A press made before this failure must not cut the new pause short.
                    manual.tryReceive()
                    withTimeoutOrNull(minutes * 60_000) { manual.receive() }
                    request()
                }
            }
        }
    }
}
