package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.LinkApi
import io.github.odnwks.mimir.link.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** U2b-1: editing a task message goes Undo first, then the reply; nothing is sent or lost when Undo fails or the link is down. */
@RunWith(RobolectricTestRunner::class)
class LinkStationEditTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Plain(var failSeal: Boolean = false) : Vault.Codec {
        override fun seal(text: String): ByteArray { if (failSeal) error("disk"); return text.toByteArray() }
        override fun open(bytes: ByteArray) = String(bytes)
    }

    /** A link that records what is called, answers what isn't held, and can be killed and brought back. */
    private class FakeLink : LinkApi {
        val events = LinkedBlockingQueue<String>()
        val calls = java.util.Collections.synchronizedList(ArrayList<Pair<Long, String>>())
        private val ids = AtomicLong(0)
        @Volatile var up = false
        /** The link is gone for calls (the real one hands back no call id). */
        @Volatile var dropped = false
        @Volatile var connects = 0
        private val seq = AtomicLong(100)
        private val held = listOf("{\"Chat\"", "{\"Send\"", "{\"Talk\"", "{\"Answer", "{\"NewTask\"", "{\"Listen\"", "{\"Undo\"")
        override fun start(key: ByteArray, relays: Boolean) = 1L
        override fun stop(handle: Long) {}
        override fun addHint(handle: Long, addr: String) {}
        override fun pairStart(handle: Long, code: String, phoneName: String) = "{}"
        override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = "{}"
        override fun connect(handle: Long, pcId: String): String { connects++; up = true; return "" }
        override fun call(handle: Long, json: String): Long {
            if (dropped) return -1L
            val id = ids.incrementAndGet()
            calls += id to json
            if (held.none { json.startsWith(it) }) reply(id, "{}")
            return id
        }
        override fun nextEvent(handle: Long, timeoutMs: Long): String? = events.poll(minOf(timeoutMs, 50), TimeUnit.MILLISECONDS)
        override fun connected(handle: Long) = up
        override fun networkChanged(handle: Long) {}
        override fun endedBecause(handle: Long) = ""
        fun reply(id: Long, ok: String) { events.add("""{"seq":${seq.incrementAndGet()},"event":{"Reply":{"call_id":$id,"result":{"Ok":$ok}}}}""") }
        fun refuse(id: Long, why: String) { events.add("""{"seq":${seq.incrementAndGet()},"event":{"Reply":{"call_id":$id,"result":{"Err":"$why"}}}}""") }
        fun ready() {
            events.add("""{"seq":1,"event":{"Welcome":{"proto":2,"pc_name":"Test PC","pc_version":"0.21","min_app":"","run_id":"r1","features":["talk","chat_sync","truth","chat_only","engines"]}}}""")
            events.add("""{"seq":2,"event":{"Snapshot":{"run_id":"r1"}}}""")
            events.add("""{"seq":3,"event":"Ready"}""")
        }
        fun kill() { up = false }
        fun callsOf(prefix: String) = calls.filter { it.second.startsWith(prefix) }
    }

    private fun until(ms: Long = 8_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    private lateinit var vaultFile: File
    private val codec = Plain()

    private fun vault() = Vault(File(vaultFile.path), codec, { ByteArray(32) }, tries = 1, pause = {})

    private fun paired(link: FakeLink): LinkStation {
        vaultFile = File(tmp.root, "link.vault")
        val v = vault()
        v.save(v.load().copy(pcId = "pc-1", pcName = "Test PC"))
        val st = LinkStation(vault(), "0.21", relays = false, hints = emptyList(), secure = { true }, api = link)
        assertTrue("connected", until { link.connects >= 1 })
        link.ready()
        assertTrue("ready", until { link.callsOf("\"Projects\"").isNotEmpty() })
        return st
    }

    private fun outbox() = vault().load().outbox.map { it.second }
    private fun notes(st: LinkStation) = st.state.value.chat.filterIsInstance<ChatEntry.Note>().map { it.text }

    @Test fun undoGoesFirstWithTheRightEntryThenTheReply() {
        val link = FakeLink(); val st = paired(link)
        var result: String? = "pending"
        st.editTaskMessage("t1", "t1-4", "new words") { result = it }
        assertTrue(until { link.callsOf("{\"Undo\"").size == 1 })
        assertEquals("""{"Undo":{"task":"t1","entry":4}}""", link.callsOf("{\"Undo\"").single().second)
        assertTrue("no reply before Undo is answered", link.callsOf("{\"Send\"").isEmpty())
        link.reply(link.callsOf("{\"Undo\"").single().first, "null")
        assertTrue(until { link.callsOf("{\"Send\"").size == 1 })
        assertTrue(link.calls.indexOfFirst { it.second.startsWith("{\"Undo\"") } < link.calls.indexOfFirst { it.second.startsWith("{\"Send\"") })
        assertTrue(link.callsOf("{\"Send\"").single().second.contains("new words"))
        assertTrue(until { result == null })
        st.close()
    }

    @Test fun aRefusedUndoSendsNothingAndGivesTheReasonBack() {
        val link = FakeLink(); val st = paired(link)
        var result: String? = "pending"
        st.editTaskMessage("t1", "t1-2", "new words") { result = it }
        assertTrue(until { link.callsOf("{\"Undo\"").size == 1 })
        link.refuse(link.callsOf("{\"Undo\"").single().first, "Pause or stop the task before undoing.")
        assertTrue(until { result != "pending" })
        assertEquals("Pause or stop the task before undoing.", result)
        Thread.sleep(300)
        assertTrue(link.callsOf("{\"Send\"").isEmpty())
        st.close()
    }

    @Test fun withTheLinkDownNothingGoesOutAndTheCallerKeepsTheWords() {
        val link = FakeLink(); val st = paired(link)
        link.dropped = true
        var result: String? = null
        st.editTaskMessage("t1", "t1-2", "new words") { result = it }
        assertTrue(until { result != null })
        assertTrue(result!!.contains("away"))
        assertTrue(link.callsOf("{\"Undo\"").isEmpty() && link.callsOf("{\"Send\"").isEmpty())
        st.close()
    }
}
