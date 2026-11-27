package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.LTalkState
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * P40 fix 1: the PC answers "I don't know it" for a message it took as project work (a task, words for the
 * running task, an idea). When the PC's "taken" reply was lost, the phone sent the message again: a second
 * task, or the words typed twice. Now a message goes again only to a PC that says it never does one twice,
 * and goes marked as sent again, so that PC gives back what it filed the first time.
 */
@RunWith(RobolectricTestRunner::class)
class P40NeverTwiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val pc = FakePc()
    /** Tasks the stand-in PC started. */
    private val tasks = AtomicInteger()

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

    /** The stand-in PC files the message as a new task, as the real one does, and its reply is lost on the way. */
    private fun filesATaskAndTheReplyIsLost() {
        // The real PC answers "Unknown" when asked about a message it took as project work.
        pc.state = LTalkState.Unknown
        pc.answer = { r, l ->
            if (r.again) l.filed(Filed.NewTask("/home/me/Recipe-Box", "task-1"), null)
            else { tasks.incrementAndGet(); l.nudge(); l.back() }
        }
    }

    @Test fun anOlderPcIsNeverSentAMessageTwice() {
        val e = engine()
        filesATaskAndTheReplyIsLost()
        e.ask("In Recipe Box, add a dark mode")
        waitUntil("the card is up") { !e.state.value.writing && e.state.value.problem != null }
        assertEquals(Engine.PC_COULD_NOT_ANSWER, e.state.value.problem)
        Thread.sleep(500)
        assertEquals("sent once: the PC may have started the task", 1, pc.sent.size)
        assertEquals(1, tasks.get())
    }

    @Test fun aPcThatTakesEachMessageOnceGetsItAgainMarkedAndGivesTheTaskBack() {
        val e = engine()
        pc.takesOnce = true
        filesATaskAndTheReplyIsLost()
        e.ask("In Recipe Box, add a dark mode")
        waitUntil("the task's card shows") { e.state.value.chat.turns.lastOrNull()?.task == "task-1" }
        assertEquals(2, pc.sent.size)
        assertFalse("the first send isn't marked", pc.sent[0].again)
        assertTrue("the second is marked as sent again", pc.sent[1].again)
        assertEquals("one task", 1, tasks.get())
        assertEquals(null, e.state.value.problem)
    }
}
