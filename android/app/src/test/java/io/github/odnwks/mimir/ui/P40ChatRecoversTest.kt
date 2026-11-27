package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.link.LTalkState
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P40 (QA16 Q6): a link that looked open carried nothing after a quiet spell. Every message failed with
 * "Your PC didn't finish that answer" and Try again changed nothing on screen. The app now redoes the link,
 * sends the message the PC never got once more (never twice), and Try again is seen to start.
 */
@RunWith(RobolectricTestRunner::class)
class P40ChatRecoversTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val pc = FakePc()

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
    }

    private fun engine() = testEngine(tmp.newFolder(), ready = false, supported = false).also { it.pc = pc }

    private fun waitUntil(what: String, ms: Long = 8_000, f: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!f() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(what, f())
    }

    private fun answered(r: io.github.odnwks.mimir.standalone.TalkRequest, l: io.github.odnwks.mimir.standalone.TalkListener) {
        l.filed(Filed.Talk("${r.turnId}.a", "pc", "Qwen 3.6 · Your PC"), null)
        l.piece("Bosc, Anjou and Bartlett.", false)
        l.done(LChatTurn("${r.turnId}.a", false, "Bosc, Anjou and Bartlett.", ran = "pc", at = r.turns.last().at + 1, seq = 7), null)
    }

    @Test fun aMessageThePcNeverGotIsSentOnceMoreWhenTheLinkIsRedone() {
        val e = engine()
        var tries = 0
        pc.state = LTalkState.Unknown
        pc.answer = { r, l ->
            tries++
            // The first one is lost on a link that carried nothing; the link is redone and the PC never had it.
            if (tries == 1) { l.nudge(); l.back() } else answered(r, l)
        }
        e.ask("Name three kinds of pears")
        waitUntil("the answer comes") { e.state.value.chat.turns.lastOrNull()?.text == "Bosc, Anjou and Bartlett." }
        assertEquals("sent once more, the same message", 2, pc.sent.size)
        assertEquals(pc.sent[0].turnId, pc.sent[1].turnId)
        assertNull(e.state.value.problem)
    }

    @Test fun neverSentTwiceMore() {
        val e = engine()
        pc.state = LTalkState.Unknown
        pc.answer = { _, l -> l.nudge(); l.back() }
        e.ask("Name three kinds of pears")
        waitUntil("the card is up") { !e.state.value.writing && e.state.value.problem != null }
        assertEquals(Engine.PC_COULD_NOT_ANSWER, e.state.value.problem)
        Thread.sleep(500)
        assertEquals("the first send and one more, never a third", 2, pc.sent.size)
    }

    @Test fun tryAgainIsSeenToStart() {
        val e = engine()
        pc.answer = { _, l -> l.done(null, "no") }
        e.ask("Name three kinds of plums")
        waitUntil("the card is up") { !e.state.value.writing && e.state.value.problem != null }
        pc.answer = { _, l -> l.done(null, "no") }
        e.retry()
        // The PC fails again at once, but the new try stays in view long enough to be seen (the QA polled every 2 s).
        Thread.sleep(700)
        assertTrue("still trying after 0.7 s", e.state.value.writing)
        assertNull("no card while it tries", e.state.value.problem)
        waitUntil("the card is back") { !e.state.value.writing && e.state.value.problem != null }
        assertEquals(2, pc.sent.size)
    }
}
