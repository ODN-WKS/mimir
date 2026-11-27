package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.link.LTalkState
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.PcTalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P40 with P44: the one resend of a message the PC never got is a send like any other. It waits for the PC's
 * choice to be read again, and never goes to Online when something else was named.
 */
@RunWith(RobolectricTestRunner::class)
class P44ResendTest {
    @get:Rule val tmp = TemporaryFolder()
    private val inner = FakePc().apply { state = LTalkState.Unknown; takesOnce = true }

    private class ChoicePc(val inner: FakePc) : PcTalk by inner {
        @Volatile var read = true
        @Volatile var engine = "free"
        override fun choiceRead() = read
        override fun chatsEngine() = engine
        override fun chatsShown() = engine
    }

    private val pc = ChoicePc(inner)

    @Before fun setUp() { FakeWeb().install(); Online.set(Online.Settings()) }

    private fun engine() = testEngine(tmp.newFolder(), ready = false, supported = false).also { it.pc = pc }

    private fun until(ms: Long = 8_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test fun aResendThatWouldNowGoOnlineIsHeld() {
        val e = engine()
        inner.answer = { _, l -> if (inner.sent.size == 1) { pc.engine = "paid"; l.nudge(); l.back() } }
        e.ask("My private question")
        assertTrue("held", until { !e.state.value.writing && e.state.value.chat.queued.firstOrNull()?.heldFor == "paid" })
        Thread.sleep(300)
        assertEquals("never sent again by itself", 1, inner.sent.size)
        assertTrue("marked as gone once", e.state.value.chat.queued.first().wentOnce)
        inner.answer = { _, l -> l.done(null, "no") }
        e.sendHeld(e.state.value.chat.queued.first().id)
        assertTrue("sent when asked", until { inner.sent.size >= 2 })
        assertEquals(inner.sent[0].turnId, inner.sent[1].turnId)
        assertTrue("marked as sent again", inner.sent[1].again)
    }

    @Test fun aResendWaitsUntilTheChoiceIsReadAgain() {
        val e = engine()
        inner.answer = { _, l -> if (inner.sent.size == 1) { pc.read = false; l.nudge(); l.back() } }
        e.ask("My private question")
        assertTrue(until { inner.sent.size == 1 })
        Thread.sleep(2_500)
        assertEquals("nothing goes while the choice is unread", 1, inner.sent.size)
        pc.read = true
        assertTrue("then it goes once more", until { inner.sent.size == 2 })
    }

    @Test fun aResendToAnotherAnswererSaysSo() {
        pc.engine = "paid"
        val e = engine()
        inner.answer = { _, l -> if (inner.sent.size == 1) { pc.engine = "free"; l.nudge(); l.back() } }
        e.ask("A question")
        assertTrue(until { inner.sent.size == 2 })
        assertTrue("one plain line", until { e.state.value.note?.startsWith("Your PC answers this now, not Online") == true })
    }
}
