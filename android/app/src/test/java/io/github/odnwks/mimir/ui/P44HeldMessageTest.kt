package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.link.LChatRecord
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.ChatSync
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.PcTalk
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.standalone.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * P44: a message never goes to another answerer than the one shown when it was typed without a plain line saying
 * so, and one that would now go to Online waits until the person sends it again (verify-p44 findings 2 and 3).
 */
@RunWith(RobolectricTestRunner::class)
class P44HeldMessageTest {
    @get:Rule val tmp = TemporaryFolder()

    private class ChoicePc(val inner: FakePc = FakePc()) : PcTalk by inner {
        @Volatile var read = true
        @Volatile var engine: String? = "free"
        @Volatile var shown: String? = "free"
        override fun choiceRead() = read
        override fun chatsEngine() = engine
        override fun chatsShown() = shown
    }

    @Before fun setUp() { FakeWeb().install(); Online.set(Online.Settings()) }

    private fun until(ms: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    private fun Engine.queued() = state.value.chat.queued.map { it.text }

    /** Typed while "Your PC" shows from memory; the PC then says Online. */
    private fun typedThenOnline(dir: File, pc: ChoicePc): Engine {
        pc.read = false
        val engine = testEngine(dir, ready = true).also { it.pc = pc }
        assertTrue(engine.ask("My private question", saw = "pc"))
        pc.engine = "paid"; pc.shown = "paid"; pc.read = true
        engine.pcChoiceRead()
        return engine
    }

    @Test fun aMessageTypedForThePcIsHeldWhenTheReadSaysOnline() {
        val pc = ChoicePc()
        val engine = typedThenOnline(tmp.newFolder(), pc)
        Thread.sleep(300)
        assertTrue("never sent by itself", pc.inner.sent.isEmpty())
        assertEquals(listOf("My private question"), engine.queued())
        assertEquals("paid", engine.state.value.chat.queued.first().heldFor)
        // A later reconnect, and the choice going back, don't send it either.
        pc.engine = "free"; engine.linkUp(); engine.drainQueue()
        Thread.sleep(300)
        assertTrue(pc.inner.sent.isEmpty())
    }

    @Test fun theHeldMessageGoesOnceWhenSentAgain() {
        val pc = ChoicePc()
        val engine = typedThenOnline(tmp.newFolder(), pc)
        engine.sendHeld(engine.state.value.chat.queued.first().id)
        assertTrue("sent", until { pc.inner.sent.size == 1 })
        assertTrue(until { !engine.state.value.writing && engine.queued().isEmpty() })
        Thread.sleep(200)
        assertEquals("once", 1, pc.inner.sent.size)
    }

    @Test fun theHoldSurvivesARestart() {
        val dir = tmp.newFolder()
        typedThenOnline(dir, ChoicePc())
        Thread.sleep(300)
        val kept = Store(File(dir, "standalone.bin"), PlainCodec).load().chats.flatMap { it.queued }.single()
        assertEquals("pc", kept.saw)
        assertEquals("paid", kept.heldFor)
    }

    @Test fun aMessageTypedBehindAHeldOneWaitsToo() {
        val pc = ChoicePc()
        val engine = typedThenOnline(tmp.newFolder(), pc)
        assertTrue(engine.ask("And a second one", saw = "paid"))
        Thread.sleep(300)
        assertTrue(pc.inner.sent.isEmpty())
        assertEquals(listOf("My private question", "And a second one"), engine.queued())
        // The held one is taken back: the one typed with Online shown goes.
        engine.cancelQueued(engine.state.value.chat.queued.first().id)
        assertTrue(until { pc.inner.sent.size == 1 })
        assertEquals("And a second one", pc.inner.sent.single().turns.last().text)
        assertTrue("the taken-back words never went", pc.inner.sent.single().turns.none { it.text == "My private question" })
    }

    @Test fun thePhoneShownButThePcOnOnlineIsHeldAtOnce() {
        val pc = ChoicePc().apply { engine = "paid"; shown = "paid" }
        val engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }
        assertTrue("taken, so the box clears and the words stay in the chat", engine.ask("Only for my phone", saw = "phone"))
        Thread.sleep(300)
        assertTrue(pc.inner.sent.isEmpty())
        assertEquals("paid", engine.state.value.chat.queued.single().heldFor)
    }

    @Test fun fromOnlineToYourPcGoesWithAPlainLine() {
        val pc = ChoicePc().apply { read = false; engine = "paid"; shown = "paid" }
        val engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }
        engine.ask("Hello", saw = "paid")
        pc.engine = "free"; pc.shown = "free"; pc.read = true
        engine.pcChoiceRead()
        assertTrue(until { pc.inner.sent.size == 1 })
        assertEquals("Your PC answers this now, not Online (Anthropic).", engine.state.value.note)
    }

    @Test fun nobodyShownThenOnlineGoesWithAPlainLine() {
        val pc = ChoicePc().apply { read = false; engine = null; shown = null }
        val engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }
        engine.ask("Hello", saw = null)
        pc.engine = "paid"; pc.shown = "paid"; pc.read = true
        engine.pcChoiceRead()
        assertTrue(until { pc.inner.sent.size == 1 })
        assertEquals("Online (Anthropic) answers this.", engine.state.value.note)
    }

    /** A message that was on its way when the app closed or the link dropped, and the PC never had it. */
    @Test fun anUnansweredMessageIsNotResentToOnline() {
        val pc = ChoicePc().apply { engine = "paid"; shown = "paid" }
        val engine = testEngine(tmp.newFolder(), ready = true, turns = listOf(Turn(true, "Hi", id = "u1", at = 1, saw = "pc"))).also { it.pc = pc }
        engine.open("c1")
        engine.linkUp()
        Thread.sleep(400)
        assertTrue(pc.inner.sent.isEmpty())
        assertEquals(listOf("Hi"), engine.queued())
        assertEquals("paid", engine.state.value.chat.queued.single().heldFor)
        assertTrue(engine.state.value.chat.turns.isEmpty())
    }

    @Test fun thePcsCopyOfAHeldMessageIsNotListedTwice() {
        val local = LocalChat("c1", Catalog.default.id, emptyList(), queued = listOf(Turn(true, "Hi", id = "u1", at = 1, saw = "pc", heldFor = "paid")))
        val merged = ChatSync.merge(local, LChatRecord("c1", null, 2, 5, listOf(LChatTurn("u1", true, "Hi", at = 1, seq = 5))), Catalog.default.id)
        assertTrue(merged.turns.isEmpty())
        assertEquals(1, merged.queued.size)
    }

    @Test fun theReadArrivingBetweenTheCheckAndTheQueueStillSends() {
        val inner = FakePc()
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val pc = object : PcTalk by inner {
            // Unread at the first look, read from then on: the PC's reply landed in between.
            override fun choiceRead() = asked.getAndIncrement() > 0
        }
        val engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }
        assertTrue(engine.ask("Quick one", saw = "pc"))
        assertTrue("it doesn't stay waiting", until { inner.sent.size == 1 })
    }

    @Test fun whoTheTopBarNames() {
        assertEquals("pc", shownAnswerer(null, answeringPc = true, claudeAnswers = false, known = true))
        assertEquals("paid", shownAnswerer(null, answeringPc = true, claudeAnswers = true, known = true))
        assertEquals(null, shownAnswerer(null, answeringPc = true, claudeAnswers = false, known = false))
        assertEquals("phone", shownAnswerer(null, answeringPc = false, claudeAnswers = true, known = false))
        assertEquals("own:Acme", shownAnswerer("Acme", answeringPc = false, claudeAnswers = false, known = true))
    }
}
