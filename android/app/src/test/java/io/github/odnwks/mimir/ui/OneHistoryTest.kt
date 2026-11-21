package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.link.LChatRecord
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.ChatSync
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Stage 1, H4: one history on the phones and the PC. */
@RunWith(RobolectricTestRunner::class)
class OneHistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val pc = FakePc()

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
    }

    private fun phone() = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }

    private fun settle(e: Engine) {
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(30)
        while (e.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        // The sync runs on its own thread: give it a moment.
        Thread.sleep(150)
    }

    private fun Engine.all(): List<LocalChat> = state.value.let { s -> (if (s.chat.turns.isEmpty() || s.whisper) emptyList() else listOf(s.chat)) + s.earlier }

    /** The owner's way (P4/H4): the S8 in airplane mode, two chats; reconnect; the S25 shows both. */
    @Test fun chatsHadWhileAwayCopyToThePcAndShowOnTheOtherPhone() {
        val s8 = phone(); val s25 = phone()
        pc.reachable = false
        s8.send("Three easy dinner ideas"); settle(s8)
        s8.newChat()
        s8.send("How do rainbows form?"); settle(s8)
        assertTrue("nothing reached the PC while away", pc.kept().isEmpty())
        pc.reachable = true
        s8.linkUp(); settle(s8)
        assertEquals(2, pc.kept().size)
        assertTrue(pc.kept().values.all { it.size == 2 })
        s25.linkUp(); settle(s25)
        assertEquals(setOf("Three easy dinner ideas", "How do rainbows form?"), s25.all().map { it.turns.first().text }.toSet())
        // Sent once: a second connect sends nothing again.
        val before = pc.seq
        s8.linkUp(); settle(s8)
        assertEquals(before, pc.seq)
    }

    /** A long time apart: the PC's changes come a page at a time until there are no more. */
    @Test fun manyChangesComePageByPage() {
        val s8 = phone(); val s25 = phone()
        listOf("One", "Two", "Three").forEach { s8.newChat(); s8.send(it); settle(s8) }
        pc.pageSize = 1
        s25.linkUp(); settle(s25)
        assertEquals(setOf("One", "Two", "Three"), s25.all().map { it.turns.first().text }.toSet())
    }

    /** H4: deleted on the S25 while the S8 is off: it goes from the S8 too and doesn't come back. */
    @Test fun aChatDeletedOnOnePhoneGoesFromTheOtherAndNeverComesBack() {
        val s8 = phone(); val s25 = phone()
        s8.send("Plan a picnic"); settle(s8)
        s25.linkUp(); settle(s25)
        val id = s25.all().single().id
        pc.reachable = false
        s25.deleteChat(id); s25.forgetDeleted()
        // The S8, still off, even adds to the chat.
        s8.send("And what to bring?"); settle(s8)
        pc.reachable = true
        s25.linkUp(); settle(s25)
        s8.linkUp(); settle(s8)
        assertTrue("gone from the S8", s8.all().none { it.id == id })
        assertTrue("and from the PC", id !in pc.kept())
        s8.linkUp(); settle(s8)
        assertTrue("it doesn't come back", s8.all().none { it.id == id })
    }

    /** H4: a Whisper never reaches the PC or the other phone. */
    @Test fun aWhisperIsNeverCopied() {
        val s8 = phone(); val s25 = phone()
        s8.startWhisper()
        s8.send("A secret"); settle(s8)
        s8.linkUp(); settle(s8)
        s8.endWhisper(); settle(s8)
        assertTrue(pc.kept().values.flatten().none { "secret" in it })
        s25.linkUp(); settle(s25)
        assertTrue(s25.all().isEmpty())
    }

    @Test fun aRenameTravelsToo() {
        val s8 = phone(); val s25 = phone()
        s8.send("Plan a picnic"); settle(s8)
        s8.rename("Picnic")
        settle(s8)
        s25.linkUp(); settle(s25)
        assertEquals("Picnic", s25.all().single().name)
    }

    @Test fun anAnswerGivenAgainOnThePhoneReplacesThePcsEverywhere() {
        val pcAnswer = LChatTurn("u.a", false, "From the PC", ran = "pc", at = 2, version = 1, seq = 5)
        val phoneAnswer = Turn(false, "From the phone", id = "u.a", at = 3, ran = "phone", version = 2)
        val mine = LocalChat("c", "m", listOf(Turn(true, "Q", id = "u", at = 1, seq = 4), phoneAnswer))
        // The PC's older answer never replaces the phone's newer one...
        assertEquals("From the phone", ChatSync.merge(mine, LChatRecord("c", null, 2, 5, listOf(pcAnswer)), "m").turns.last().text)
        // ...and a newer version from elsewhere replaces this phone's.
        val old = mine.copy(turns = listOf(mine.turns[0], Turn(false, "From the PC", id = "u.a", at = 2, ran = "pc", seq = 5)))
        val merged = ChatSync.merge(old, LChatRecord("c", null, 3, 6, listOf(LChatTurn("u.a", false, "From the phone", ran = "phone", at = 3, version = 2, seq = 6))), "m")
        assertEquals(listOf("Q", "From the phone"), merged.turns.map { it.text })
    }

    @Test fun turnsAddedOnBothSidesWhileApartShowInOneOrderEverywhere() {
        val a = LocalChat("c", "m", listOf(Turn(true, "A1", id = "a1", at = 10), Turn(false, "A1 answer", id = "a1.a", at = 11)))
        val fromB = LChatRecord("c", null, 12, 7, listOf(LChatTurn("b1", true, "B1", at = 5), LChatTurn("b1.a", false, "B1 answer", at = 6)))
        val merged = ChatSync.merge(a, fromB, "m").turns.map { it.text }
        assertEquals(listOf("B1", "B1 answer", "A1", "A1 answer"), merged)
        assertFalse(merged.size != 4)
    }
}
