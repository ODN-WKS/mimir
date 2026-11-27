package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.link.LinkApi
import io.github.odnwks.mimir.link.Vault
import io.github.odnwks.mimir.standalone.TalkListener
import io.github.odnwks.mimir.standalone.TalkRequest
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

/**
 * P46 fix round 2, walk problem 1 (late reply). The walk's stuck link: the call just before the
 * Talk (a ChatPut) reached the PC, the Talk never did. The ChatPut's answer comes back after the Talk
 * went out. Does the phone still notice that its Talk is stuck and redo the link?
 */
@RunWith(RobolectricTestRunner::class)
class P46F2LateReplyTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Plain : Vault.Codec {
        override fun seal(text: String): ByteArray = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    private class Link : LinkApi {
        val events = LinkedBlockingQueue<String>()
        val calls = java.util.Collections.synchronizedList(ArrayList<Pair<Long, String>>())
        private val ids = AtomicLong(0)
        private val seq = AtomicLong(100)
        @Volatile var up = false
        @Volatile var stuck = false
        @Volatile var connects = 0
        @Volatile var heldPut = -1L
        override fun start(key: ByteArray, relays: Boolean) = 1L
        override fun stop(handle: Long) {}
        override fun addHint(handle: Long, addr: String) {}
        override fun pairStart(handle: Long, code: String, phoneName: String) = "{}"
        override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = "{}"
        override fun connect(handle: Long, pcId: String): String { connects++; up = true; stuck = false; return "" }
        override fun call(handle: Long, json: String): Long {
            val id = ids.incrementAndGet()
            calls += id to json
            when {
                // The ChatPut reaches the PC; its answer is on its way back (held here, let go later).
                json.startsWith("{\"ChatPut\"") -> heldPut = id
                // From the Talk on, nothing the phone sends reaches the PC.
                json.startsWith("{\"Talk\"") -> stuck = true
                !stuck -> reply(id, "{}")
            }
            return id
        }
        override fun nextEvent(handle: Long, timeoutMs: Long): String? = events.poll(minOf(timeoutMs, 50), TimeUnit.MILLISECONDS)
        override fun connected(handle: Long) = up
        override fun networkChanged(handle: Long) {}
        override fun endedBecause(handle: Long) = ""
        fun reply(id: Long, ok: String) { events.add("""{"seq":${seq.incrementAndGet()},"event":{"Reply":{"call_id":$id,"result":{"Ok":$ok}}}}""") }
        fun ready() {
            events.add("""{"seq":1,"event":{"Welcome":{"proto":2,"pc_name":"Test PC","pc_version":"0.21","min_app":"","run_id":"r1","features":["talk","chat_sync","truth","chat_only","engines"]}}}""")
            events.add("""{"seq":2,"event":{"Snapshot":{"run_id":"r1"}}}""")
            events.add("""{"seq":3,"event":"Ready"}""")
        }
        fun callsOf(prefix: String) = synchronized(calls) { calls.filter { it.second.startsWith(prefix) } }
    }

    private fun until(ms: Long = 15_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test fun aStuckTalkIsNoticedThoughTheCallBeforeItIsAnsweredLate() {
        val link = Link()
        val file = File(tmp.root, "link.vault")
        val v = Vault(file, Plain(), { ByteArray(32) }, tries = 1, pause = {})
        v.save(v.load().copy(pcId = "pc-1", pcName = "Test PC"))
        val st = LinkStation(Vault(file, Plain(), { ByteArray(32) }, tries = 1, pause = {}), "0.21", relays = false, hints = emptyList(), secure = { true }, api = link)
        assertTrue("connected", until { link.connects >= 1 })
        link.ready()
        assertTrue("ready", until { link.callsOf("\"Projects\"").isNotEmpty() })
        assertTrue(until { st.state.value.pc.online })
        val log = java.util.Collections.synchronizedList(ArrayList<String>())
        val listener = object : TalkListener {
            override fun filed(f: Filed?, err: String?) { log += "filed:$err" }
            override fun piece(text: String, thinking: Boolean) {}
            override fun done(turn: LChatTurn?, err: String?) { log += "done:$err" }
            override fun lost() { log += "lost" }
            override fun back() { log += "back" }
            override fun nudge() { log += "nudge" }
        }
        // The chat's earlier turns go to the PC, then the new message, as the walk's PC log showed (call 9, call 10).
        assertTrue(st.chatPut("c1", null, emptyList(), false) { })
        assertTrue(st.talk(TalkRequest("c1", "t1", emptyList(), null, emptyList(), null, "Answer in short sentences.", null), listener))
        Thread.sleep(300)
        link.reply(link.heldPut, "{\"seq\":5,\"deleted\":false}")
        assertTrue("a Talk that never reached the PC is noticed and the link made again (connects=${link.connects}, log=$log)", until(20_000) { link.connects >= 2 })
        st.close()
    }
}
