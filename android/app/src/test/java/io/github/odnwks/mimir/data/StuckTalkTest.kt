package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.link.LinkApi
import io.github.odnwks.mimir.link.Vault
import io.github.odnwks.mimir.standalone.TalkListener
import io.github.odnwks.mimir.standalone.TalkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * A link stuck one way (P46 walk, problem 1; QA15 H1). Seen on the emulator with a test PC: the phone's
 * calls (the chat message first) never reached the PC, while the PC's own events still came in, so the
 * app kept "connected" and every later message waited behind the stuck one until the app was restarted.
 * Only an answer to something the phone asked proves its calls get through; events from the PC don't.
 */
@RunWith(RobolectricTestRunner::class)
class StuckTalkTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Plain : Vault.Codec {
        override fun seal(text: String): ByteArray = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    /** A link whose calls can stop getting through ([oneWay]) while the PC's events keep coming. */
    private class OneWayLink : LinkApi {
        val events = LinkedBlockingQueue<String>()
        val calls = java.util.Collections.synchronizedList(ArrayList<Pair<Long, String>>())
        private val ids = AtomicLong(0)
        private val seq = AtomicLong(100)
        @Volatile var up = false
        @Volatile var oneWay = false
        @Volatile var connects = 0
        override fun start(key: ByteArray, relays: Boolean) = 1L
        override fun stop(handle: Long) {}
        override fun addHint(handle: Long, addr: String) {}
        override fun pairStart(handle: Long, code: String, phoneName: String) = "{}"
        override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = "{}"
        override fun connect(handle: Long, pcId: String): String { connects++; up = true; oneWay = false; return "" }
        override fun call(handle: Long, json: String): Long {
            val id = ids.incrementAndGet()
            calls += id to json
            if (!oneWay && !json.startsWith("{\"Talk\"")) reply(id, "{}")
            return id
        }
        override fun nextEvent(handle: Long, timeoutMs: Long): String? {
            // The PC's own news keeps coming, stuck calls or not (a task working, another answer being written).
            if (oneWay) events.add("""{"seq":${seq.incrementAndGet()},"event":{"SomethingNew":{}}}""")
            return events.poll(minOf(timeoutMs, 50), TimeUnit.MILLISECONDS)
        }
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

    private fun paired(link: OneWayLink): LinkStation {
        val file = File(tmp.root, "link.vault")
        val v = Vault(file, Plain(), { ByteArray(32) }, tries = 1, pause = {})
        v.save(v.load().copy(pcId = "pc-1", pcName = "Test PC"))
        val st = LinkStation(Vault(file, Plain(), { ByteArray(32) }, tries = 1, pause = {}), "0.21", relays = false, hints = emptyList(), secure = { true }, api = link)
        assertTrue("connected", until { link.connects >= 1 })
        link.ready()
        assertTrue("ready", until { link.callsOf("\"Projects\"").isNotEmpty() })
        return st
    }

    @Test fun aMessageOnALinkStuckOneWayRedoesTheLinkThoughThePcsEventsStillCome() {
        val link = OneWayLink(); val st = paired(link)
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
        link.oneWay = true
        assertTrue(st.talk(TalkRequest("c1", "t1", emptyList(), null, emptyList(), null, "Answer in short sentences.", null), listener))
        assertTrue("a link whose calls get no answer is made again", until { link.connects >= 2 })
        assertFalse("the message is never failed: $log", synchronized(log) { log.any { it.startsWith("done") || it.startsWith("filed") } })
        link.ready()
        assertTrue("the answer is looked for again once the link is back: $log", until { synchronized(log) { "back" in log } })
        assertEquals("made again once", 2, link.connects)
        st.close()
    }
}
