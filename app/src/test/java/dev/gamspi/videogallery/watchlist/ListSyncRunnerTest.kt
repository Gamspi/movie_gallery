package dev.gamspi.videogallery.watchlist

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListSyncRunnerTest {
    @Test fun disabledMakesNoRequestsAndDisablingCancelsActiveExchange() = runTest {
        val enabled = MutableStateFlow(false)
        var calls = 0
        var cancelled = false
        val runner = ListSyncRunner({ calls++; try { awaitCancellation() } finally { cancelled = true } })
        val job = runner.start(backgroundScope, enabled)
        runner.request()
        runCurrent()
        assertEquals(0, calls)
        enabled.value = true
        runCurrent()
        assertEquals(1, calls)
        enabled.value = false
        runCurrent()
        assertTrue(cancelled)
        assertEquals(ListSyncStatus.Disabled, runner.status.value)
        runner.request()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, calls)
        job.cancel()
    }

    @Test fun manualRetryEndsBackoffButAutomaticRequestDoesNot() = runTest {
        var calls = 0
        val runner = ListSyncRunner({ calls++; throw IOException() }, { 42 })
        val job = runner.start(backgroundScope, MutableStateFlow(true))
        runCurrent(); assertEquals(1, calls)
        runner.request()
        advanceTimeBy(30_000); runCurrent(); assertEquals(1, calls)
        runner.retryNow()
        runCurrent(); assertEquals(2, calls)
        // The failure count survives: the next pause is 5 minutes, not 1.
        advanceTimeBy(299_999); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(3, calls)
        job.cancel()
    }

    @Test fun pressDuringExchangeDoesNotCutTheFollowingPause() = runTest {
        var calls = 0
        lateinit var runner: ListSyncRunner
        runner = ListSyncRunner({ if (++calls == 1) runner.retryNow(); throw IOException() }, { 42 })
        val job = runner.start(backgroundScope, MutableStateFlow(true))
        runCurrent(); assertEquals(1, calls)
        advanceTimeBy(59_999); runCurrent(); assertEquals(1, calls)
        job.cancel()
    }

    @Test fun throttleLimitsRoutineCallsOnly() {
        var time = 0L
        val throttle = SyncThrottle(300_000) { time }
        assertTrue(throttle.allow(false))
        time = 299_999; assertFalse(throttle.allow(false))
        assertTrue(throttle.allow(true))
        time = 599_998; assertFalse(throttle.allow(false))
        time = 599_999; assertTrue(throttle.allow(false))
    }

    @Test fun retryBackoffAndRecovery() = runTest {
        var calls = 0
        val runner = ListSyncRunner({ if (++calls < 3) throw IOException() }, { 42 })
        val job = runner.start(backgroundScope, MutableStateFlow(true))
        runCurrent()
        assertEquals(ListSyncStatus.Unavailable(42), runner.status.value)
        runner.request()
        advanceTimeBy(59_999); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(300_000); runCurrent(); assertEquals(3, calls)
        assertEquals(ListSyncStatus.Idle, runner.status.value)
        job.cancel()
    }
}
