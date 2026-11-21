package io.github.odnwks.mimir.labs

import io.github.odnwks.mimir.labs.CheckLine.Result
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.findSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksTest {
    private class Fake(
        var paired: Boolean = true, var online: Boolean = true, var answer: PcAnswer? = PcAnswer(120, true),
        var voice: Boolean = true, var lookupsOn: Boolean = true, var lookupWorks: Boolean = true, var free: Long = 20_000_000_000L,
    ) : Probes {
        var asked = 0
        override fun version() = "0.01 (dev)"
        override fun freeBytes() = free
        override fun paired() = paired
        override fun pcOnline() = online
        override fun askPc(): PcAnswer? { asked++; return answer }
        override fun voiceLoads() = voice
        override fun lookupsOn() = lookupsOn
        override fun lookup() = lookupWorks
    }

    private fun by(l: List<CheckLine>, name: String) = l.first { it.name == name }

    @Test fun everythingWell() {
        val f = Fake()
        val l = Checks.run(f)
        assertEquals(6, l.size)
        assertTrue(l.all { it.result == Result.Good })
        assertEquals("Reached in 120 ms", by(l, "Your PC").line)
        assertEquals(1, f.asked)
    }

    @Test fun pcAwayIsAProblemWithNextStep() {
        val l = Checks.run(Fake(online = false))
        assertEquals(Result.Problem, by(l, "Your PC").result)
        assertTrue(by(l, "Your PC").next!!.isNotBlank())
        assertEquals(Result.Skipped, by(l, "Your PC's model").result)
    }

    @Test fun notPairedSkipsThePc() {
        val l = Checks.run(Fake(paired = false))
        assertEquals(Result.Skipped, by(l, "Your PC").result)
        assertEquals(Result.Skipped, by(l, "Your PC's model").result)
    }

    @Test fun modelThatDoesNotAnswer() {
        val l = Checks.run(Fake(answer = PcAnswer(900, false)))
        assertEquals(Result.Problem, by(l, "Your PC's model").result)
        assertTrue(by(l, "Your PC's model").next!!.isNotBlank())
    }

    @Test fun pcNotSetToAnswerChats() = assertEquals(Result.Skipped, by(Checks.run(Fake(answer = null)), "Your PC's model").result)
    @Test fun voiceFails() = assertEquals(Result.Problem, by(Checks.run(Fake(voice = false)), "Ingrid's voice").result)
    @Test fun lookupsOffAreSkippedAndSaySo() {
        val c = by(Checks.run(Fake(lookupsOn = false, lookupWorks = false)), "Looking things up")
        assertEquals(Result.Skipped, c.result)
        assertTrue(c.line.contains("off"))
    }
    @Test fun lookupFails() = assertEquals(Result.Problem, by(Checks.run(Fake(lookupWorks = false)), "Looking things up").result)
    @Test fun lowSpace() = assertEquals(Result.Problem, by(Checks.run(Fake(free = 300_000_000)), "Space on this phone").result)
    @Test fun versionLine() = assertEquals("0.01 (dev)", by(Checks.run(Fake()), "App version").line)

    @Test fun aProbeThatThrowsBecomesAProblemNotACrash() {
        val f = object : Probes by Fake() { override fun voiceLoads(): Boolean = error("boom") }
        assertEquals(Result.Problem, by(Checks.run(f), "Ingrid's voice").result)
    }

    @Test fun timesInPlainWords() {
        assertEquals("80 ms", Checks.words(80))
        assertEquals("1.5 s", Checks.words(1500))
    }

    @Test fun searchFindsLabs() {
        for (q in listOf("labs", "bug", "report", "problem", "troubleshoot", "log")) {
            assertTrue("'$q' finds nothing", findSettings(q, paired = false).any { it.screen == Screen.Labs })
            assertTrue("'$q' finds nothing paired", findSettings(q, paired = true).any { it.screen == Screen.Labs })
        }
    }
}
