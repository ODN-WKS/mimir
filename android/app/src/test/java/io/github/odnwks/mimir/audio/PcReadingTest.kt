package io.github.odnwks.mimir.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The PC's voice reading to the phone: Stop ends it at once, extra taps count once, a late answer is dropped. */
class PcReadingTest {
    private var now = 0L
    private var stops = 0
    private companion object { var base = System.nanoTime() / 1_000_000 + 10_000_000L }

    @Before fun setUp() { PcReading.stop(); base += 100_000; now = base; PcReading.clock = { now }; stops = 0; PcReading.stopper = { stops += 1 } }
    @After fun tearDown() { PcReading.stop(); PcReading.stopper = null; PcReading.clock = { System.nanoTime() / 1_000_000 } }

    @Test fun aSecondAskForTheSameReplyIsIgnored() {
        val a = PcReading.begin("r1")
        assertTrue(a != 0)
        assertEquals(0, PcReading.begin("r1"))
        assertEquals(0, PcReading.begin("r1"))
        assertEquals("r1", PcReading.id.value)
    }

    @Test fun stopEndsThePlayerAtOnceAndDropsTheLateAnswer() {
        val a = PcReading.begin("r1")
        PcReading.stop()
        assertEquals(1, stops)
        assertNull(PcReading.id.value)
        assertFalse(PcReading.wanted(a))
    }

    @Test fun anOlderAnswerCannotPlayAfterAStopAndANewAsk() {
        val a = PcReading.begin("r1")
        PcReading.stop()
        val b = PcReading.begin("r1")
        assertFalse(PcReading.wanted(a))
        assertTrue(PcReading.wanted(b))
    }

    @Test fun anotherReplyTakesOver() {
        val a = PcReading.begin("r1")
        val b = PcReading.begin("r2")
        assertFalse(PcReading.wanted(a))
        assertTrue(PcReading.wanted(b))
        assertEquals("r2", PcReading.id.value)
    }

    @Test fun stoppingWithNothingReadingDoesNothing() {
        PcReading.stop()
        assertEquals(0, stops)
    }

    @Test fun quickTapsCountOnce() {
        assertFalse(PcReading.tooSoon())
        repeat(4) { now += 50; assertTrue(PcReading.tooSoon()) }
        now += 400
        assertFalse(PcReading.tooSoon())
    }

    @Test fun readingEndsWhenThePlayerStopsAfterPlaying() {
        PcReading.begin("r1")
        PcReading.onPlayer(null, false)
        assertEquals("r1", PcReading.id.value) // not started yet
        PcReading.onPlayer("msg:r1", true)
        assertEquals("r1", PcReading.id.value)
        PcReading.onPlayer("msg:r1", false)
        assertNull(PcReading.id.value)
    }
}
