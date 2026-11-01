package dev.gamspi.videogallery.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallMonitorTest {
    @Test fun warns_at_30000_ms_but_not_29999() {
        val monitor = StallMonitor()
        assertFalse(monitor.update(1_000, true, "initial", 0))
        assertFalse(monitor.update(30_999, true, "initial", 0))
        assertTrue(monitor.update(31_000, true, "initial", 0))
        assertTrue(monitor.update(40_000, true, "initial", 0))
    }

    @Test fun useful_progress_clears_warning_and_restarts_interval() {
        val monitor = StallMonitor()
        assertFalse(monitor.update(0, true, "range", 2))
        assertTrue(monitor.update(30_000, true, "range", 2))
        assertFalse(monitor.update(31_000, true, "range", 3))
        assertFalse(monitor.update(60_999, true, "range", 3))
        assertTrue(monitor.update(61_000, true, "range", 3))
    }

    @Test fun paused_or_ready_resets_wait_and_clears_warning() {
        val monitor = StallMonitor()
        monitor.update(0, true, "range", 0)
        assertTrue(monitor.update(30_000, true, "range", 0))
        assertFalse(monitor.update(31_000, false, "range", 0))
        assertFalse(monitor.update(100_000, false, "range", 0))
        assertFalse(monitor.update(101_000, true, "range", 0))
        assertFalse(monitor.update(130_999, true, "range", 0))
        assertTrue(monitor.update(131_000, true, "range", 0))
    }

    @Test fun new_demand_starts_new_wait_even_when_progress_is_lower() {
        val monitor = StallMonitor()
        monitor.update(0, true, setOf(1, 2), 1)
        assertTrue(monitor.update(30_000, true, setOf(1, 2), 1))
        assertFalse(monitor.update(31_000, true, setOf(8, 9), 0))
        assertFalse(monitor.update(60_999, true, setOf(8, 9), 0))
        assertTrue(monitor.update(61_000, true, setOf(8, 9), 0))
    }

    @Test fun reset_prevents_old_session_wait_from_leaking_to_new_session() {
        val monitor = StallMonitor()
        monitor.update(0, true, "same-range", 0)
        assertTrue(monitor.update(30_000, true, "same-range", 0))
        monitor.reset()
        assertFalse(monitor.update(50_000, true, "same-range", 0))
        assertFalse(monitor.update(79_999, true, "same-range", 0))
        assertTrue(monitor.update(80_000, true, "same-range", 0))
    }

    @Test fun progress_regression_and_recovery_to_old_value_do_not_restart_wait() {
        val monitor = StallMonitor()
        monitor.update(0, true, "range", 5)
        assertFalse(monitor.update(10_000, true, "range", 4))
        assertFalse(monitor.update(20_000, true, "range", 5))
        assertTrue(monitor.update(30_000, true, "range", 5))
    }

    @Test fun null_demand_is_a_valid_stable_identifier() {
        val monitor = StallMonitor()
        assertFalse(monitor.update(0, true, null, 0))
        assertTrue(monitor.update(30_000, true, null, 0))
        assertFalse(monitor.update(31_000, true, "range", 0))
    }

    @Test fun configured_timeout_is_used() {
        val monitor = StallMonitor(timeoutMs = 500)
        assertFalse(monitor.update(0, true, 1, 0))
        assertFalse(monitor.update(499, true, 1, 0))
        assertTrue(monitor.update(500, true, 1, 0))
    }
}
