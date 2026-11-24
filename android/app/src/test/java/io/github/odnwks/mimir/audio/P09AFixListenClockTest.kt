package io.github.odnwks.mimir.audio

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why U2b1TaskMessagesTest.listenUsesThePcVoiceWhileThePcIsThere failed in most full runs: other
 * Listen tests move the debounce clock far ahead (10 000 s) and leave their last tap in it; the next
 * test in the same process then saw every tap as "too soon" until the real clock caught up.
 */
class P09AFixListenClockTest {
    @After fun tearDown() { PcReading.clock = { System.nanoTime() / 1_000_000 } }

    @Test fun aTapStampedByAnEarlierClockDoesNotSwallowTapsAfterIt() {
        val real = System.nanoTime() / 1_000_000
        PcReading.clock = { real + 10_000_000L }
        PcReading.tooSoon() // a tap stamped far ahead of the real clock
        PcReading.clock = { real }
        assertFalse("a tap after the clock went back is not a quick second tap", PcReading.tooSoon())
        assertTrue("but a real quick second tap still counts once", PcReading.tooSoon())
    }
}
