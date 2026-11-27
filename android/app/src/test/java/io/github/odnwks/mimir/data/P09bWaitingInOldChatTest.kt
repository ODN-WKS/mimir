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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 0.09 review finding 1: a message left waiting in an old chat is updated THERE when the PC comes back. */
@RunWith(RobolectricTestRunner::class)
class P09bWaitingInOldChatTest {
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
        /** The PC answers a Stop with this refusal instead of Ok. */
        @Volatile var refuseStop: String? = null
        @Volatile var connects = 0
        private val seq = AtomicLong(100)
        private val held = listOf("{\"Chat\"", "{\"Send\"", "{\"Talk\"", "{\"Answer", "{\"NewTask\"", "{\"Listen\"")
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
            val why = refuseStop
            if (why != null && json.startsWith("{\"Stop\"")) { refuse(id, why); return id }
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
        fun callsOf(prefix: String) = synchronized(calls) { calls.filter { it.second.startsWith(prefix) } }
    }

    private fun until(ms: Long = 8_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    private lateinit var vaultFile: File
    private val codec = Plain()

    private fun vault() = Vault(File(vaultFile.path), codec, { ByteArray(32) }, tries = 1, pause = {})

    /** What the station has saved, only read: [Vault.load] also deletes the temp file of a save
     *  under way, which fails that save and puts a "couldn't be saved" line in the open chat. */
    private fun saved() = org.json.JSONObject(codec.open(vaultFile.readBytes()))
    private fun outboxEmpty() = saved().getJSONArray("outbox").length() == 0

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


    private fun mine(st: LinkStation) = st.state.value.chat.filterIsInstance<ChatEntry.Mine>()

    private fun waitingThenNewChat(link: FakeLink): LinkStation {
        val st = paired(link)
        link.dropped = true
        st.send("m1")
        st.newChat()
        assertTrue(st.state.value.chat.isEmpty())
        link.dropped = false
        link.ready()
        assertTrue("sent", until { link.callsOf("{\"Chat\"").size == 1 })
        return st
    }

    @Test fun aStatusAnswerLandsInTheOldChatNotTheNewOne() {
        val link = FakeLink(); val st = waitingThenNewChat(link)
        link.reply(link.callsOf("{\"Chat\"").first().first, """{"Status":{"reply":["All quiet"]}}""")
        assertTrue("left the outbox", until { outboxEmpty() })
        // Saved with the older chats: the answer has been dealt with.
        assertTrue("the reply was filed", until { "All quiet" in saved().getJSONArray("past").toString() })
        assertTrue("the new chat stays empty ${st.state.value.chat}", st.state.value.chat.isEmpty())
        st.openPastChat(st.state.value.pastChats.single().id)
        assertTrue("the old chat has the reply", until { st.state.value.chat.any { it is ChatEntry.Reply && it.paragraphs == listOf("All quiet") } })
        assertTrue("and no longer waits", mine(st).single().filing?.kind != FilingKind.Waiting)
        st.close()
    }

    @Test fun aRefusalMarksTheOldChatsMessageFailed() {
        val link = FakeLink(); val st = waitingThenNewChat(link)
        link.refuse(link.callsOf("{\"Chat\"").first().first, "No.")
        assertTrue("left the outbox", until { outboxEmpty() })
        // The reason is said in the open chat once the old one has been marked.
        assertTrue("the refusal was dealt with", until { st.state.value.chat.any { it is ChatEntry.Note && it.text == "No." } })
        assertTrue("the new chat stays empty", st.state.value.chat.none { it is ChatEntry.Mine })
        st.openPastChat(st.state.value.pastChats.single().id)
        assertEquals(FilingKind.Failed, mine(st).single().filing?.kind)
        st.close()
    }
}
