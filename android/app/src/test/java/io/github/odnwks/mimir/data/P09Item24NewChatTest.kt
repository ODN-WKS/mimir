package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.LinkApi
import io.github.odnwks.mimir.link.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 0.09 item 24: New chat always opens an empty chat; a message still waiting stays with its own old chat. */
@RunWith(RobolectricTestRunner::class)
class P09Item24NewChatTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Plain : Vault.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    /** A link that never comes up: whatever is sent waits. */
    private class DownLink : LinkApi {
        override fun start(key: ByteArray, relays: Boolean) = 1L
        override fun stop(handle: Long) {}
        override fun addHint(handle: Long, addr: String) {}
        override fun pairStart(handle: Long, code: String, phoneName: String) = "{}"
        override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = "{}"
        override fun connect(handle: Long, pcId: String) = "The PC didn't answer."
        override fun call(handle: Long, json: String) = -1L
        override fun nextEvent(handle: Long, timeoutMs: Long): String? { Thread.sleep(minOf(timeoutMs, 50)); return null }
        override fun connected(handle: Long) = false
        override fun networkChanged(handle: Long) {}
        override fun endedBecause(handle: Long) = ""
    }

    private fun vault() = Vault(File(tmp.root, "link.vault"), Plain(), { ByteArray(32) }, tries = 1, pause = {})

    private fun station(): LinkStation {
        val v = vault()
        v.save(v.load().copy(pcId = "pc-1", pcName = "Test PC"))
        return LinkStation(vault(), "0.21", relays = false, hints = emptyList(), secure = { true }, api = DownLink())
    }

    private fun mine(st: LinkStation) = st.state.value.chat.filterIsInstance<ChatEntry.Mine>()

    @Test fun newChatAfterAChatWithRepliesIsEmpty() {
        val st = station()
        st.answerHere("hello there", "Working") { listOf("Hi") }
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end && st.state.value.chat.none { it is ChatEntry.Reply && it.paragraphs == listOf("Hi") }) Thread.sleep(20)
        st.newChat()
        assertTrue(st.state.value.chat.isEmpty())
        assertEquals(1, st.state.value.pastChats.size)
    }

    @Test fun newChatWhenTheLastMessageWaitsIsEmptyAndTheMessageStaysWithItsOldChat() {
        val st = station()
        st.send("still waiting")
        assertEquals(1, mine(st).size)
        st.newChat()
        assertTrue("the new chat is empty", st.state.value.chat.isEmpty())
        assertEquals("the old chat is kept", 1, st.state.value.pastChats.size)
        assertEquals("the message still waits to be sent", listOf("still waiting"), vault().load().outbox.map { it.second })
        // Coming back to the old chat shows it, waiting.
        st.openPastChat(st.state.value.pastChats.single().id)
        assertEquals(listOf("still waiting"), mine(st).map { it.text })
        assertEquals(FilingKind.Waiting, mine(st).single().filing?.kind)
    }

    @Test fun newChatWithOnlyANoteIsEmpty() {
        val st = station()
        LinkStation::class.java.getDeclaredMethod("note", String::class.java, Boolean::class.javaPrimitiveType, NoteAction::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(st, "The PC didn't answer.", true, null, "")
        assertTrue(st.state.value.chat.any { it is ChatEntry.Note })
        st.newChat()
        assertTrue(st.state.value.chat.isEmpty())
    }

    @Test fun aWaitingMessageOfAnOldChatDoesNotComeBackInTheNewOneAfterARestart() {
        val st = station()
        st.send("old waiting")
        st.newChat()
        val again = LinkStation(vault(), "0.21", relays = false, hints = emptyList(), secure = { true }, api = DownLink())
        assertTrue(again.state.value.chat.isEmpty())
        assertEquals(1, again.state.value.pastChats.size)
    }
}
