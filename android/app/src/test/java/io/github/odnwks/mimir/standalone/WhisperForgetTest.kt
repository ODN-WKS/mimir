package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.online.ChatMode
import io.github.odnwks.mimir.online.LookupLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** PHE-03 / PHE-18: what a Whisper looked up is gone from the list and memory when it ends. */
@RunWith(RobolectricTestRunner::class)
class WhisperForgetTest {
    @get:Rule val tmp = TemporaryFolder()

    private object Plain : Store.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    private object Quiet : Runner {
        override fun open(path: String) = true
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink) = """{"gen_tokens":0,"gen_ms":1}"""
        override fun stop() {}
        override fun close() {}
    }

    @Test fun theWhispersLookupWordsLeaveTheListWhenItEnds() {
        Catalog.reset()
        LookupLog.clear()
        val dir = tmp.newFolder()
        val e = Engine(dir, Store(File(dir, "standalone.bin"), Plain), Quiet, supported = true)
        LookupLog.add(LookupLog.Entry(1, "a-site.example", "everyday words", LookupLog.Route.Phone, 10), ChatMode.Normal)
        e.startWhisper()
        LookupLog.add(LookupLog.Entry(2, "secret.example", "my private words", LookupLog.Route.Tor, 20), ChatMode.WhisperWeb)
        assertEquals(2, LookupLog.entries.value.size)
        e.endWhisper()
        assertEquals(listOf("a-site.example"), LookupLog.entries.value.map { it.site })
        assertTrue(LookupLog.entries.value.none { it.words == "my private words" })
        LookupLog.clear()
    }

    @Test fun leavingAWhisperThroughNewChatOrAnOldChatForgetsItsLookupsToo() {
        for (way in listOf("new", "open")) {
            Catalog.reset()
            LookupLog.clear()
            val dir = tmp.newFolder()
            val old = LocalChat("old1", Catalog.default.id, listOf(Turn(true, "an earlier question", id = "t1"), Turn(false, "an answer", id = "t2")), at = 1)
            Store(File(dir, "standalone.bin"), Plain).save(Saved(Catalog.default.id, listOf(old)))
            val e = Engine(dir, Store(File(dir, "standalone.bin"), Plain), Quiet, supported = true)
            // The saved chat is the open one: start a Whisper over it, so it is in the earlier chats.
            e.startWhisper()
            LookupLog.add(LookupLog.Entry(2, "secret.example", "my private words", LookupLog.Route.Tor, 20), ChatMode.WhisperWeb)
            assertEquals(1, LookupLog.entries.value.size)
            if (way == "new") e.newChat() else e.open("old1")
            assertTrue("$way: the Whisper's lookup words are gone", LookupLog.entries.value.isEmpty())
        }
        LookupLog.clear()
    }

    @Test fun aFailedLookupLogsNoWordsFromTheOwnersQuestion() {
        Catalog.reset()
        val dir = tmp.newFolder()
        val e = Engine(dir, Store(File(dir, "standalone.bin"), Plain), Quiet, supported = true)
        val m = Engine::class.java.getDeclaredMethod("failed", String::class.java, Throwable::class.java).apply { isAccessible = true }
        org.robolectric.shadows.ShadowLog.clear()
        m.invoke(e, "look up \"my secret words\" in the encyclopedias", java.io.IOException("down"))
        m.invoke(e, "find the place \"Secretville\"", java.io.IOException("down"))
        val logged = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { it.msg }
        assertTrue("the failure is logged", logged.contains("lookup failed"))
        assertFalse(logged.contains("secret") || logged.contains("Secretville"))
    }
}
