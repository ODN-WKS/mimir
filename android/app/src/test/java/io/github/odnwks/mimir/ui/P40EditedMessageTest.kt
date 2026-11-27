package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.link.LTalkState
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.TalkListener
import io.github.odnwks.mimir.standalone.TalkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P40 fix 2: an edit (and Try again) keeps the message's id. When its Talk was lost on the way, the PC still
 * had the old answer: the phone showed that as the answer to the new words, and the edited words were never
 * filed. Now an answer older than what was asked is never taken, and the message goes once more, marked, with
 * its new version, so the PC files the new words.
 */
@RunWith(RobolectricTestRunner::class)
class P40EditedMessageTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val pc = FakePc()
    /** What the stand-in PC filed as ideas, by message id and version (as the real one keeps its replies). */
    private val ideas = java.util.concurrent.ConcurrentHashMap<String, String>()

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

    /** The stand-in PC files every message as an idea, once per message version. */
    private fun filesIdea(r: TalkRequest, l: TalkListener) {
        val asked = r.turns.last { it.id == r.turnId }
        ideas.putIfAbsent("${asked.id}/${asked.version}", asked.text)
        l.filed(Filed.Idea("/home/me/Recipe-Box", "idea-${asked.id}-${asked.version}"), null)
    }

    @Test fun anEditedIdeaWhoseTalkWasLostIsFiledWithItsNewWords() {
        val e = engine()
        pc.takesOnce = true
        pc.answer = ::filesIdea
        e.ask("Recipes: idea - a dark mode switch")
        waitUntil("the idea is saved") { e.state.value.chat.turns.lastOrNull()?.text?.startsWith("Saved as an idea") == true }
        val question = e.state.value.chat.turns.first()
        val old = e.state.value.chat.turns.last()
        // The PC kept the phone's copy of the old reply (a change number this phone never heard of).
        pc.state = LTalkState.Done(LChatTurn(old.id, false, old.text, ran = "pc", at = old.at, version = old.version, seq = 9))
        // The edit's Talk is lost on a link that carried nothing; the one sent again gets through.
        pc.answer = { r, l -> if (r.again) filesIdea(r, l) else { l.nudge(); l.back() } }
        assertNotNull(e.editMessage(question.id, "Recipes: idea - a bigger print button"))
        waitUntil("the edited words are filed") { ideas.values.any { it.contains("bigger print button") } }
        waitUntil("the reply shows") { !e.state.value.writing && e.state.value.chat.turns.lastOrNull()?.user == false }
        assertEquals("the first send, the edit, the edit once more", 3, pc.sent.size)
        assertTrue(pc.sent[2].again)
        assertEquals(2, pc.sent[2].turns.last().version)
        assertNull(e.state.value.problem)
    }

    @Test fun tryAgainWhoseTalkWasLostNeverShowsTheOldAnswer() {
        val e = engine()
        pc.takesOnce = true
        e.ask("What's the capital of Norway?")
        waitUntil("the answer comes") { e.state.value.chat.turns.lastOrNull()?.text == "Oslo is the capital." }
        val old = e.state.value.chat.turns.last()
        // The PC still has its old answer, as it saved it.
        pc.state = LTalkState.Done(LChatTurn(old.id, false, old.text, ran = "pc", at = old.at, version = old.version, seq = old.seq))
        val firstAnswer = pc.answer
        pc.words = listOf("Oslo, ", "on the fjord.")
        pc.answer = { r, l -> if (r.again) firstAnswer(r, l) else { l.nudge(); l.back() } }
        assertTrue(e.tryAgain())
        waitUntil("the new answer comes") { e.state.value.chat.turns.lastOrNull()?.text == "Oslo, on the fjord." }
        assertEquals(3, pc.sent.size)
        assertTrue(pc.sent[2].again)
        assertTrue("Try again asks a newer version", pc.sent[2].turns.last().version > pc.sent[0].turns.last().version)
        assertEquals("the question stays once", 2, e.state.value.chat.turns.size)
    }
}
