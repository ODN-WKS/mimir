package io.github.odnwks.mimir.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * PHD-28: two rapid "Pair" taps → only one coroutine started.
 */
class NoPairingRaceTest {

    @Test
    fun `two rapid taps start only one attempt`() {
        val guard = PairingGuard()
        val started = AtomicInteger(0)

        // Simulate two simultaneous taps.
        val r1 = guard.tryStart().also { if (it) started.incrementAndGet() }
        val r2 = guard.tryStart().also { if (it) started.incrementAndGet() }

        assertEquals("exactly one coroutine started", 1, started.get())
        assertTrue("first tap started", r1)
        assertFalse("second tap blocked", r2)
    }

    @Test
    fun `concurrent taps start exactly one attempt`() {
        val guard = PairingGuard()
        val started = AtomicInteger(0)
        val threads = (1..10).map {
            Thread {
                if (guard.tryStart()) started.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("exactly one attempt started across 10 threads", 1, started.get())
    }
}
