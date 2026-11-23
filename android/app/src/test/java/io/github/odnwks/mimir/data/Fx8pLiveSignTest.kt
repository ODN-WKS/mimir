package io.github.odnwks.mimir.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FX8P: the live line's fixes (hold, no false silence, time words) and the turn clock. */
class Fx8pLiveSignTest {
    private val now = 1_000_000_000_000L

    @Test fun yourPcGotItIsHeldUntilTheFirstStepEvenAfterTwoAndAHalfSeconds() {
        val s = LiveSign(gotItAt = now - 8_000)
        assertEquals("Your PC got it", s.line(now))
        assertEquals("the first step takes over", "Thinking…", LiveSign(gotItAt = now - 8_000, doing = "Thinking…").line(now))
    }

    @Test fun yourPcGotItGoesWhenTheTurnEndsOrAfterAWhile() {
        assertNull(LiveSign(gotItAt = now - 8_000, turnEnded = true).line(now))
        assertNull("an old PC that never says a step falls back", LiveSign(gotItAt = now - 16_000).line(now))
    }

    @Test fun anOldGotItDoesNotCoverTheNextReply() {
        assertNull(LiveSign(gotItAt = now - 600_000).line(now))
    }

    @Test fun twentySecondsOfSilenceIsNormalButAMinuteIsSaid() {
        assertEquals("Thinking… · 30 s", LiveSign(doing = "Thinking…", turnUnix = now / 1000 - 30, newsAt = now - 30_000).line(now))
        assertEquals("Still working; no news from your PC for 1 min 5 s", LiveSign(doing = "Thinking…", turnUnix = now / 1000 - 90, newsAt = now - 65_000).line(now))
    }

    @Test fun noNewsLineWhileItWaitsForThePerson() {
        val quiet = now - 300_000
        assertEquals("Waiting for your OK · 5 min", LiveSign(doing = "Waiting for your OK", turnUnix = now / 1000 - 300, newsAt = quiet).line(now))
        assertEquals("Thinking… · 5 min", LiveSign(doing = "Thinking…", turnUnix = now / 1000 - 300, newsAt = quiet, needsYou = true).line(now))
    }

    @Test fun onlyTheWordsAreAnnouncedNotTheTimer() {
        val s = LiveSign(doing = "Reading main.rs", turnUnix = now / 1000 - 12, newsAt = now).shown(now)!!
        assertEquals("Reading main.rs", s.words)
        assertEquals(" · 12 s", s.timer)
    }

    @Test fun aPcClockThatIsOffDoesNotSkewTheTimeWorked() {
        val c = TurnClock()
        c.header("t", busy = false, turnUnix = 0, nowMs = now - 10_000)
        // The PC's clock is 400 s ahead: the turn "began" in the phone's future.
        c.header("t", busy = true, turnUnix = now / 1000 + 400, nowMs = now)
        assertEquals(now / 1000, c.start("t", now / 1000 + 400))
        // A small difference is trusted as it is.
        c.header("u", busy = false, turnUnix = 0, nowMs = now - 10_000)
        c.header("u", busy = true, turnUnix = now / 1000 - 2, nowMs = now)
        assertEquals(now / 1000 - 2, c.start("u", now / 1000 - 2))
        // A turn first seen already going on keeps the PC's start, never in the future.
        c.header("v", busy = true, turnUnix = now / 1000 - 90, nowMs = now)
        assertEquals(now / 1000 - 90, c.start("v", now / 1000 - 90))
        c.forget("t"); assertEquals(now / 1000 + 400, c.start("t", now / 1000 + 400))
    }
}
