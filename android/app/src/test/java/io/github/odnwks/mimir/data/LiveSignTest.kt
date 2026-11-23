package io.github.odnwks.mimir.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D1: a task always says something while it is going on, and never looks frozen. */
class LiveSignTest {
    private val now = 1_000_000_000_000L

    @Test fun sendingComesFirst() {
        assertEquals("Sending…", LiveSign(sending = true).line(now))
    }

    @Test fun yourPcGotItForAMoment() {
        val s = LiveSign(gotItAt = now - 1_000)
        assertEquals("Your PC got it", s.line(now))
        assertNull("it goes after a while if nothing else is said", s.line(now + 5_000))
    }

    @Test fun theStepAndHowLongItWorked() {
        val s = LiveSign(doing = "Reading main.rs", turnUnix = now / 1000 - 12, newsAt = now - 500)
        assertEquals("Reading main.rs · 12 s", s.line(now))
        assertEquals("Thinking… · 1 min 20 s", LiveSign(doing = "Thinking…", turnUnix = now / 1000 - 80, newsAt = now).line(now))
    }

    @Test fun silenceIsSaidPlainly() {
        val s = LiveSign(doing = "Running a command", turnUnix = now / 1000 - 40, newsAt = now - 25_000)
        assertEquals("Still working; no news from your PC for 25 s", s.line(now))
    }

    @Test fun aDownLinkIsSaid() {
        val s = LiveSign(doing = "Thinking…", newsAt = now, linkUp = false)
        assertEquals("Not connected to your PC. It shows again when it is back.", s.line(now))
    }

    @Test fun nothingToSayWhenIdle() {
        assertNull(LiveSign().line(now))
    }
}
