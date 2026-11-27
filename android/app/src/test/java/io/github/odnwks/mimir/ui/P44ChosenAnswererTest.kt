package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.data.Mapping
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.PcTalk
import io.github.odnwks.mimir.standalone.Saved
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.standalone.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * P44: who answers is the person's saved choice from the first moment (QA16 Q17), and words a
 * Whisper left in the draft file by an older version go at start (verify-p42b F5).
 */
@RunWith(RobolectricTestRunner::class)
class P44ChosenAnswererTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A PC that is connected but whose "who answers" choice is not read yet this run. */
    private class UnreadPc(val inner: FakePc = FakePc()) : PcTalk by inner {
        @Volatile var read = false
        override fun choiceRead() = read
    }

    private fun until(ms: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    // ---- Q17: never send before the saved choice is read ----

    @Test fun aMessageWaitsUntilThePcsChoiceIsReadThenGoesByItself() {
        FakeWeb().install(); Online.set(Online.Settings())
        val pc = UnreadPc()
        val engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }
        assertTrue("the message is taken", engine.ask("What is a haiku?"))
        Thread.sleep(300)
        assertTrue("nothing is sent before the choice is read", pc.inner.sent.isEmpty())
        assertEquals("it waits in the chat", listOf("What is a haiku?"), engine.state.value.chat.queued.map { it.text })
        assertFalse("no answer is written by this phone meanwhile", engine.state.value.writing)
        pc.read = true
        engine.pcChoiceRead()
        assertTrue("sent once the choice is read", until { pc.inner.sent.size == 1 })
        assertTrue("answered by the PC", until { engine.state.value.chat.turns.any { !it.user && it.ran == "pc" } })
        assertTrue(engine.state.value.chat.queued.isEmpty())
    }

    @Test fun tryAgainWaitsTooUntilTheChoiceIsRead() {
        FakeWeb().install(); Online.set(Online.Settings())
        val pc = UnreadPc()
        val engine = testEngine(tmp.newFolder(), ready = true, turns = listOf(Turn(true, "Hi", id = "u1", at = 1), Turn(false, "Hello", id = "u1.a", at = 2, ran = "pc"))).also { it.pc = pc }
        assertFalse(engine.tryAgain())
        Thread.sleep(200)
        assertTrue(pc.inner.sent.isEmpty())
        assertEquals("the chat is unchanged", 2, engine.state.value.chat.turns.size)
    }

    @Test fun theRememberedChoiceIsShownBeforeThePcIsReached() {
        val m = Mapping.models(null, "auto", remembered = "paid")
        assertEquals("paid", m.chatsEngine)
        assertTrue(m.chatsEngineKnown)
        val none = Mapping.models(null, "auto", remembered = "")
        assertNull("the PC offers no choice", none.chatsEngine)
        assertTrue(none.chatsEngineKnown)
        val never = Mapping.models(null, "auto", remembered = null)
        assertNull(never.chatsEngine)
        assertFalse("never read: not known", never.chatsEngineKnown)
        // A fresh reply from the PC wins over what was remembered.
        val fresh = Mapping.models(io.github.odnwks.mimir.link.LModels(emptyList(), null, false, "", chatsEngine = "free"), "auto", remembered = "paid")
        assertEquals("free", fresh.chatsEngine)
        assertTrue(fresh.chatsEngineKnown)
    }

    @Test fun anUnknownChoiceIsNeverShownAsYourPc() {
        assertEquals(WHO_READING, answererWord(whisper = false, online = false, answeringPc = true, claudeAnswers = false, known = false))
        assertEquals("Your PC", answererWord(whisper = false, online = false, answeringPc = true, claudeAnswers = false, known = true))
        assertEquals("Online", answererWord(whisper = false, online = false, answeringPc = true, claudeAnswers = true, known = true))
        // This phone answering does not depend on the PC's choice.
        assertEquals("This phone", answererWord(whisper = false, online = false, answeringPc = false, claudeAnswers = false, known = false))
        // In the sheet nothing is ticked while it is unknown.
        assertTrue(whoCards(null, phoneCan = true, paired = true, claudeOffered = false).none { it.chosen })
    }

    // ---- F5: old Whisper words in the draft file ----

    private fun storeIn(dir: File) = Store(File(dir, "standalone.bin"), PlainCodec)

    @Test fun draftsOfNoKeptChatGoAtStartAndTheRestStay() {
        val dir = tmp.newFolder()
        val store = storeIn(dir)
        store.save(Saved(Catalog.default.id, listOf(
            LocalChat("c1", Catalog.default.id, listOf(Turn(true, "Hi", id = "t1", at = 1))),
            LocalChat("c2", Catalog.default.id, listOf(Turn(true, "Soup?", id = "t2", at = 2))),
        )))
        store.saveDrafts(mapOf("c1" to "start chat words", "c2" to "older chat words", "w-old" to "whispered words"))
        val engine = io.github.odnwks.mimir.standalone.Engine(dir, storeIn(dir), WordsRunner(), supported = true, now = { 1_800_000_000_000L })
        assertEquals("start chat words", engine.drafts.get("c1").text)
        assertEquals("older chat words", engine.drafts.get("c2").text)
        assertTrue("the Whisper's words are gone", engine.drafts.get("w-old").isEmpty)
        assertEquals("and gone from the file at once", mapOf("c1" to "start chat words", "c2" to "older chat words"), storeIn(dir).loadDrafts())
    }

    @Test fun unreadableChatsLeaveEveryDraftAlone() {
        val dir = tmp.newFolder()
        File(dir, "standalone.bin").writeText("not a chat file")
        storeIn(dir).saveDrafts(mapOf("c1" to "keep me"))
        val engine = io.github.odnwks.mimir.standalone.Engine(dir, storeIn(dir), WordsRunner(), supported = true, now = { 1_800_000_000_000L })
        assertEquals("keep me", engine.drafts.get("c1").text)
        assertEquals(mapOf("c1" to "keep me"), storeIn(dir).loadDrafts())
    }
}
