package io.github.odnwks.mimir.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHD-14: after 3 consecutive failed checks the alarm interval must grow to 30–60 min. */
class BackoffAfterMissesTest {

    @Test
    fun three_consecutive_misses_triggers_backoff() {
        val backoff = CheckBackoff(random = { 45 }) // deterministic 45-min random
        repeat(3) { backoff.missed() }
        val interval = backoff.nextIntervalMs()
        assertTrue(
            "interval after 3 misses must be >= 30 min, was ${interval / 60_000} min",
            interval >= 30 * 60_000L,
        )
    }

    @Test
    fun two_misses_keep_normal_interval() {
        val backoff = CheckBackoff()
        repeat(2) { backoff.missed() }
        assertEquals(Stay.EVERY_MS, backoff.nextIntervalMs())
    }

    @Test
    fun success_resets_to_normal_interval() {
        val backoff = CheckBackoff(random = { 45 })
        repeat(3) { backoff.missed() }
        backoff.succeeded()
        assertEquals(Stay.EVERY_MS, backoff.nextIntervalMs())
    }

    @Test
    fun backoff_interval_is_at_most_60_minutes() {
        val backoff = CheckBackoff(random = { 60 })
        repeat(10) { backoff.missed() }
        assertTrue(backoff.nextIntervalMs() <= 60 * 60_000L)
    }
}
