package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.LFinished
import io.github.odnwks.mimir.link.LinkApi
import io.github.odnwks.mimir.link.Vault
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

/** N4b: finished items reach the notifier only from a PC that lists finished_events; an older PC keeps today's way. */
@RunWith(RobolectricTestRunner::class)
class LinkStationFinishedTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Plain : Vault.Codec {
        override fun seal(text: String): ByteArray = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    private class FakeLink : LinkApi {
        val events = LinkedBlockingQueue<String>()
        val calls = java.util.Collections.synchronizedList(ArrayList<Pair<Long, String>>())
        private val ids = AtomicLong(0)
        @Volatile var up = false
        @Volatile var dropped = false
        @Volatile var connects = 0
        /** The PC can be reached: false, connecting fails (the PC is away). */
        @Volatile var pcUp = false
        private val seq = AtomicLong(100)
        private val held = listOf("{\"Chat\"", "{\"Send\"", "{\"NewTask\"", "{\"NewIncognito\"")
        override fun start(key: ByteArray, relays: Boolean) = 1L
        override fun stop(handle: Long) {}
        override fun addHint(handle: Long, addr: String) {}
        override fun pairStart(handle: Long, code: String, phoneName: String) = "{}"
        override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = "{}"
        override fun connect(handle: Long, pcId: String): String { if (!pcUp) return "Couldn't connect."; connects++; up = true; return "" }
        override fun call(handle: Long, json: String): Long {
            if (dropped || !up) return -1L
            val id = ids.incrementAndGet()
            calls += id to json
            when {
                json.startsWith("\"Projects\"") -> reply(id, """{"projects":[{"path":"/p/garden","name":"Garden planner"}],"last":null}""")
                held.none { json.startsWith(it) } -> reply(id, "{}")
            }
            return id
        }
        override fun nextEvent(handle: Long, timeoutMs: Long): String? = events.poll(minOf(timeoutMs, 50), TimeUnit.MILLISECONDS)
        override fun connected(handle: Long) = up
        override fun networkChanged(handle: Long) {}
        override fun endedBecause(handle: Long) = ""
        fun reply(id: Long, ok: String) { events.add("""{"seq":${seq.incrementAndGet()},"event":{"Reply":{"call_id":$id,"result":{"Ok":$ok}}}}""") }
        fun refuse(id: Long, why: String) { events.add("""{"seq":${seq.incrementAndGet()},"event":{"Reply":{"call_id":$id,"result":{"Err":"$why"}}}}""") }
        fun ready(features: String = "\"talk\",\"chat_sync\",\"truth\"") {
            pcUp = true
            events.add("""{"seq":1,"event":{"Welcome":{"proto":2,"pc_name":"Test PC","pc_version":"0.21","min_app":"","run_id":"r1","features":[$features]}}}""")
            events.add("""{"seq":2,"event":{"Snapshot":{"run_id":"r1"}}}""")
            events.add("""{"seq":3,"event":"Ready"}""")
        }
        fun callsOf(prefix: String) = synchronized(calls) { calls.filter { it.second.startsWith(prefix) } }
    }

    private fun until(ms: Long = 8_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    private fun station(link: FakeLink): LinkStation {
        val file = File(tmp.root, "link.vault")
        fun vault() = Vault(file, Plain(), { ByteArray(32) }, tries = 1, pause = {})
        vault().let { it.save(it.load().copy(pcId = "pc-1", pcName = "Test PC")) }
        return LinkStation(vault(), "0.21", relays = false, hints = emptyList(), secure = { true }, api = link)
    }

    private val item = """{"id":"fin:t1:4","task":"t1","how":"Ok","at":1794000400}"""

    @Test fun aPcWithTheFeatureTellsTheNotifierOnceForEachEventAndTheRecentList() {
        val link = FakeLink(); val st = station(link)
        val got = java.util.Collections.synchronizedList(ArrayList<List<LFinished>>())
        st.onFinished = { got += it }
        link.pcUp = true
        link.ready("\"talk\",\"finished_events\"")
        assertTrue(until { st.pcFinishedEvents() })
        link.events.add("""{"seq":10,"event":{"Finished":{"item":$item}}}""")
        link.events.add("""{"seq":11,"event":{"RecentFinished":{"items":[$item]}}}""")
        assertTrue(until { got.size == 2 })
        assertEquals("fin:t1:4", got[0].single().id)
        assertEquals("t1", got[1].single().task)
        assertTrue(st.state.value.pcFinished)
        st.close()
    }

    @Test fun anOlderPcWithoutTheFeatureNeverStartsTheServiceOrTheNotes() {
        val link = FakeLink(); val st = station(link)
        val got = java.util.Collections.synchronizedList(ArrayList<List<LFinished>>())
        st.onFinished = { got += it }
        link.pcUp = true
        link.ready()
        assertTrue(until { st.state.value.paired && !st.state.value.pcAway })
        link.events.add("""{"seq":10,"event":{"Finished":{"item":$item}}}""")
        Thread.sleep(500)
        assertTrue(got.isEmpty())
        assertFalse(st.pcFinishedEvents())
        assertFalse(st.state.value.pcFinished)
        st.close()
    }
}
