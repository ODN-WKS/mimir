package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.link.LTalkState
import io.github.odnwks.mimir.ui.FakePc
import io.github.odnwks.mimir.ui.PlainCodec
import io.github.odnwks.mimir.ui.WordsRunner
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

/** N2: nothing is lost when the PC is away. */
@RunWith(RobolectricTestRunner::class)
class N2NothingLostTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pc = FakePc()

    private fun engine(dir: File = tmp.newFolder()): Engine {
        Catalog.reset()
        return Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), WordsRunner(), supported = false, now = System::currentTimeMillis).also { it.pc = pc }
    }

    private fun waitUntil(what: String, f: () -> Boolean) {
        val until = System.currentTimeMillis() + 6_000
        while (!f() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(what, f())
    }

    private fun idle(e: Engine) = waitUntil("nothing is being written") { !e.state.value.writing }

    @Test fun severalMessagesWaitAndGoInOrderOnceWhenThePcIsBack() {
        val e = engine()
        pc.reachable = false
        assertTrue(e.ask("First"))
        assertTrue(e.ask("Second"))
        assertTrue(e.ask("Third"))
        assertEquals(listOf("First", "Second", "Third"), e.state.value.chat.queued.map { it.text })
        assertTrue(pc.sent.isEmpty())
        pc.reachable = true
        e.linkUp()
        waitUntil("all three are answered") { e.state.value.chat.turns.size == 6 && !e.state.value.writing }
        assertEquals(listOf("First", "Second", "Third"), e.state.value.chat.turns.filter { it.user }.map { it.text })
        // Each answer follows its own message.
        assertEquals(listOf(true, false, true, false, true, false), e.state.value.chat.turns.map { it.user })
        assertTrue(e.state.value.chat.queued.isEmpty())
        // The PC was asked exactly once for each (the same ids, so never a duplicate).
        assertEquals(3, pc.sent.size)
        assertEquals(e.state.value.chat.turns.filter { it.user }.map { it.id }, pc.sent.map { it.turnId })
        // A second "link is up" sends nothing again.
        e.linkUp(); Thread.sleep(300)
        assertEquals(3, pc.sent.size)
    }

    @Test fun aWaitingMessageCanBeTakenBack() {
        val e = engine()
        pc.reachable = false
        e.ask("Never mind this"); e.ask("Keep this")
        e.cancelQueued(e.state.value.chat.queued.first().id)
        assertEquals(listOf("Keep this"), e.state.value.chat.queued.map { it.text })
        pc.reachable = true
        e.linkUp()
        waitUntil("the kept one is answered") { e.state.value.chat.turns.size == 2 && !e.state.value.writing }
        assertEquals(listOf("Keep this"), pc.sent.map { it.turns.last().text })
    }

    @Test fun waitingMessagesSurviveARestart() {
        val dir = tmp.newFolder()
        val e = engine(dir)
        pc.reachable = false
        e.ask("Before the restart"); e.ask("Also this")
        val ids = e.state.value.chat.queued.map { it.id }
        // The app starts again from the same folder.
        val again = engine(dir)
        assertEquals(listOf("Before the restart", "Also this"), again.state.value.chat.queued.map { it.text })
        assertEquals(ids, again.state.value.chat.queued.map { it.id })
        pc.reachable = true
        again.linkUp()
        waitUntil("both are answered") { again.state.value.chat.turns.size == 4 && !again.state.value.writing }
        assertEquals(2, pc.sent.size)
    }

    @Test fun aMessageTypedWhileOthersWaitLinesUpBehindThemEvenWithThePcBack() {
        val e = engine()
        pc.reachable = false
        e.ask("One")
        pc.reachable = true
        // The link is up but linkUp() hasn't run yet: the new message still goes after "One".
        e.ask("Two")
        waitUntil("both are answered") { e.state.value.chat.turns.size == 4 && !e.state.value.writing }
        assertEquals(listOf("One", "Two"), e.state.value.chat.turns.filter { it.user }.map { it.text })
    }

    @Test fun aVoiceNoteIsKeptThenHeardAndSentWhenThePcIsBackAndTheAudioIsDeleted() {
        val dir = tmp.newFolder()
        val e = engine(dir)
        pc.reachable = false
        assertTrue(e.keepRecording(ByteArray(44 + 32000) { 1 }, 1))
        val q = e.state.value.chat.queued.single()
        assertEquals("", q.text)
        val file = File(File(dir, "voice"), q.voice!!)
        assertTrue("the recording is kept", file.isFile)
        // A recording is kept sealed (this check uses the plain codec, so look for the raw bytes only in the real one).
        pc.reachable = true
        e.linkUp()
        waitUntil("it was heard and answered") { e.state.value.chat.turns.size == 2 && !e.state.value.writing }
        assertEquals("Run the checks again.", e.state.value.chat.turns.first().text)
        assertEquals(q.id, e.state.value.chat.turns.first().id)
        assertFalse("the audio is deleted once done", file.exists())
        assertEquals(1, pc.hearCalls.get())
        assertTrue(e.state.value.chat.queued.isEmpty())
    }

    @Test fun aVoiceNoteThePcCouldNotHearStaysAndIsTriedAgain() {
        val dir = tmp.newFolder()
        val e = engine(dir)
        pc.reachable = false
        e.keepRecording(ByteArray(44 + 32000), 1)
        pc.heard = null
        pc.reachable = true
        e.linkUp()
        waitUntil("the PC was asked") { pc.hearCalls.get() == 1 }
        Thread.sleep(200)
        assertEquals(1, e.state.value.chat.queued.size)
        assertTrue(File(dir, "voice").listFiles()!!.isNotEmpty())
        pc.heard = "Now it works."
        e.linkUp()
        waitUntil("it goes now") { e.state.value.chat.turns.size == 2 && !e.state.value.writing }
        assertEquals("Now it works.", e.state.value.chat.turns.first().text)
    }

    @Test fun anOrphanRecordingIsDeletedAtStart() {
        val dir = tmp.newFolder()
        File(dir, "voice").apply { mkdirs() }.resolve("old.wav").writeText("x")
        engine(dir)
        assertFalse(File(dir, "voice/old.wav").exists())
    }

    @Test fun anAnswerCutOffByAClosedAppIsTakenFromThePcWhenTheLinkIsBack() {
        val dir = tmp.newFolder()
        val e = engine(dir)
        pc.answer = { _, _ -> }   // The PC takes the message and never reports back here.
        e.ask("Tell me a story")
        waitUntil("it's with the PC") { e.state.value.writing }
        val id = e.state.value.chat.turns.last().id
        // The app is closed mid-answer; the PC finishes and keeps the answer.
        val again = engine(dir)
        assertTrue(again.state.value.chat.turns.last().user)
        pc.sent.clear()
        pc.state = LTalkState.Done(LChatTurn("$id.a", false, "Once upon a time, the end.", ran = "pc", at = System.currentTimeMillis() + 5, seq = 9))
        again.linkUp()
        waitUntil("the finished answer shows in place") { again.state.value.chat.turns.lastOrNull()?.text == "Once upon a time, the end." }
        assertEquals("the message is not sent a second time", 0, pc.sent.size)
        assertEquals(listOf(id, "$id.a"), again.state.value.chat.turns.map { it.id })
        assertNull(again.state.value.problem)
    }

    @Test fun aPcThatLostTheAnswerGetsTheMessageAgainAndOneCardOffersTryAgainWhenItFailed() {
        val e = engine()
        pc.answer = { _, _ -> }
        e.ask("Hello there")
        waitUntil("it's with the PC") { e.state.value.writing }
        e.stop()
        idle(e)
        // It failed on the PC: one line says so (the chat card has Try again), and nothing is sent by itself.
        pc.sent.clear()
        pc.state = LTalkState.Failed("model stopped")
        e.linkUp()
        waitUntil("the card is up") { e.state.value.problem == Engine.PC_COULD_NOT_ANSWER }
        assertEquals("Your PC didn't finish that answer.", Engine.PC_COULD_NOT_ANSWER)
        assertEquals(0, pc.sent.size)
    }
}
