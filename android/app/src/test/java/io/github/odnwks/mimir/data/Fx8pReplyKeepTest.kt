package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.LFile
import io.github.odnwks.mimir.link.LPicture
import io.github.odnwks.mimir.link.LinkApi
import io.github.odnwks.mimir.link.Vault
import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.standalone.TurnFile
import io.github.odnwks.mimir.standalone.TurnPicture
import org.json.JSONObject
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

/** FX8P: pictures kept for a message are never dropped quietly (unreadable, refused), and are kept off the main thread. */
@RunWith(RobolectricTestRunner::class)
class Fx8pReplyKeepTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Plain : Vault.Codec {
        override fun seal(text: String): ByteArray = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    private class FakeLink(val features: String) : LinkApi {
        val events = LinkedBlockingQueue<String>()
        val calls = java.util.Collections.synchronizedList(ArrayList<Pair<Long, String>>())
        private val ids = AtomicLong(0)
        @Volatile var sendAnswer: String? = null
        @Volatile var up = false
        @Volatile var pcUp = false
        @Volatile var connects = 0
        private val seq = AtomicLong(100)
        override fun start(key: ByteArray, relays: Boolean) = 1L
        override fun stop(handle: Long) {}
        override fun addHint(handle: Long, addr: String) {}
        override fun pairStart(handle: Long, code: String, phoneName: String) = "{}"
        override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = "{}"
        override fun connect(handle: Long, pcId: String): String { if (!pcUp) return "Couldn't connect."; connects++; up = true; return "" }
        override fun call(handle: Long, json: String): Long {
            if (!up) return -1L
            val id = ids.incrementAndGet()
            calls += id to json
            if (json.startsWith("\"Projects\"")) events.add("""{"seq":${seq.incrementAndGet()},"event":{"Reply":{"call_id":$id,"result":{"Ok":{"projects":[{"path":"/p/garden","name":"Garden planner"}],"last":null}}}}}""")
            else if (json.startsWith("{\"Send\"") && sendAnswer != null) events.add("""{"seq":${seq.incrementAndGet()},"event":{"Reply":{"call_id":$id,"result":$sendAnswer}}}""")
            else if (!json.startsWith("{\"Send\"")) events.add("""{"seq":${seq.incrementAndGet()},"event":{"Reply":{"call_id":$id,"result":{"Ok":{}}}}}""")
            return id
        }
        override fun nextEvent(handle: Long, timeoutMs: Long): String? = events.poll(minOf(timeoutMs, 50), TimeUnit.MILLISECONDS)
        override fun connected(handle: Long) = up
        override fun networkChanged(handle: Long) {}
        override fun endedBecause(handle: Long) = ""
        fun ready() {
            pcUp = true
            events.add("""{"seq":1,"event":{"Welcome":{"proto":2,"pc_name":"Test PC","pc_version":"0.21","min_app":"","run_id":"r1","features":[$features]}}}""")
            events.add("""{"seq":2,"event":{"Snapshot":{"run_id":"r1"}}}""")
            events.add("""{"seq":3,"event":"Ready"}""")
            events.add("""{"seq":4,"event":{"Task":{"header":{"id":"t1","title":"Garden","project":"/p/garden","status":"Done","busy":false,"created_unix":1}}}}""")
        }
        fun callsOf(prefix: String) = synchronized(calls) { calls.filter { it.second.startsWith(prefix) } }
    }

    private fun until(ms: Long = 8_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    private val vaultFile get() = File(tmp.root, "link.vault")
    private fun vault() = Vault(vaultFile, Plain(), { ByteArray(32) }, tries = 1, pause = {})
    private val attachDir get() = File(tmp.root, "attach")

    private fun station(link: FakeLink): LinkStation {
        val v = vault()
        v.save(v.load().copy(pcId = "pc-1", pcName = "Test PC"))
        return LinkStation(vault(), "0.21", relays = false, hints = emptyList(), secure = { true }, api = link, attachDir = attachDir,
            projectStore = ProjectStore.InMemory(listOf("/p/garden" to "Garden planner")))
    }

    private val pic = TurnPicture.of("Photo.jpg", byteArrayOf(1, 2, 3))
    private val all = "\"talk\",\"chat_sync\",\"truth\",\"task_files\",\"task_reply_options\""

    @Test fun readTellsNothingKeptFromUnreadable() {
        val dir = tmp.newFolder("a")
        val kept = PendingAttachments(dir, Plain())
        assertTrue("nothing kept is an empty answer", kept.read("m1")!!.isEmpty)
        assertTrue(kept.keep("m2", PendingAttachments.held(listOf(pic), emptyList())))
        assertEquals(1, kept.read("m2")!!.pictures.size)
        File(dir, "m2.att").writeText("not json at all")
        assertEquals("kept but unreadable is not 'nothing'", null, kept.read("m2"))
        assertTrue(kept.has("m2"))
    }

    @Test fun unreadablePicturesStopTheReplyAndKeepTheWords() {
        val link = FakeLink(all)
        var st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null })
        link.up = false; link.pcUp = false
        assertTrue(st.reply("t1", "look at this", listOf(pic), emptyList()))
        st.close()
        attachDir.listFiles().orEmpty().filter { it.name.endsWith(".att") }.forEach { it.writeText("garbage") }
        val link2 = FakeLink(all); st = station(link2); link2.ready()
        assertTrue("marked failed with a plain note", until { st.state.value.task("t1")?.messages?.any { it.waiting && it.failedWhy.isNotEmpty() } == true })
        assertEquals("nothing is sent without its pictures", 0, link2.callsOf("{\"Send\"").size)
        assertTrue("words kept", st.state.value.task("t1")!!.messages.any { it.text == "look at this" })
        assertTrue("file kept", attachDir.listFiles().orEmpty().any { it.name.endsWith(".att") })
        st.close()
    }

    @Test fun aRefusedReplyKeepsItsPicturesAndCanBeTriedAgain() {
        val link = FakeLink(all); link.sendAnswer = """{"Err":"Busy right now."}"""
        val st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null })
        assertTrue(st.reply("t1", "again please", listOf(pic), emptyList()))
        assertTrue("shown as failed", until { st.state.value.task("t1")?.messages?.any { it.waiting && it.failedWhy == "Busy right now." } == true })
        assertTrue("pictures kept", attachDir.listFiles().orEmpty().any { it.name.endsWith(".att") })
        link.sendAnswer = """{"Ok":{}}"""
        val id = st.state.value.task("t1")!!.messages.first { it.waiting }.id
        st.retryReply(id)
        assertTrue("sent again with its picture", until { link.callsOf("{\"Send\"").size == 2 })
        assertEquals(1, JSONObject(link.callsOf("{\"Send\"").last().second).getJSONObject("Send").getJSONArray("pictures").length())
        assertTrue("gone once taken", until { st.state.value.task("t1")?.messages?.none { it.waiting } == true })
        assertTrue(until { attachDir.listFiles().orEmpty().none { it.name.endsWith(".att") } })
        st.close()
    }

    @Test fun aRefusedReplyCanBeRemoved() {
        val link = FakeLink(all); link.sendAnswer = """{"Err":"No."}"""
        val st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null })
        st.reply("t1", "x", listOf(pic), emptyList())
        assertTrue(until { st.state.value.task("t1")?.messages?.any { it.failedWhy.isNotEmpty() } == true })
        st.removeReply(st.state.value.task("t1")!!.messages.first { it.waiting }.id)
        assertTrue(until { st.state.value.task("t1")?.messages?.none { it.waiting } == true })
        assertTrue(attachDir.listFiles().orEmpty().none { it.name.endsWith(".att") })
        st.close()
    }

    @Test fun replyAsyncSavesOffTheCallingThread() {
        val link = FakeLink(all); val st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null })
        val caller = Thread.currentThread()
        var ranOn: Thread? = null; var taken: Boolean? = null
        st.replyAsync("t1", "pic", listOf(pic), emptyList()) { ranOn = Thread.currentThread(); taken = it }
        assertTrue(until { taken != null })
        assertTrue("taken", taken == true)
        assertTrue("work did not run on the calling thread", ranOn !== caller)
        st.close()
    }

    @Test fun startTaskAsyncKeepsPicturesAndReturnsTheId() {
        val link = FakeLink(all); val st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null && link.callsOf("\"Projects\"").isNotEmpty() })
        var id: String? = null
        st.startTaskAsync("Look", "Garden planner", incognito = false, pictures = listOf(pic)) { id = it }
        assertTrue(until { id != null })
        assertTrue(id!!.startsWith("t-"))
        assertTrue(until { link.callsOf("{\"NewTask\"").size == 1 })
        st.close()
    }

    @Test fun anUnreadableNewTaskIsMarkedFailedNotSentWithoutPictures() {
        val link = FakeLink(all)
        var st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null && link.callsOf("\"Projects\"").isNotEmpty() })
        link.up = false; link.pcUp = false
        val id = st.startTask("Look", "Garden planner", incognito = false, pictures = listOf(pic))
        assertTrue(id.startsWith("t-"))
        st.close()
        attachDir.listFiles().orEmpty().filter { it.name.endsWith(".att") }.forEach { it.writeText("garbage") }
        val link2 = FakeLink(all); st = station(link2); link2.ready()
        assertTrue(until { st.state.value.task(id)?.failedWhy?.isNotEmpty() == true })
        assertEquals(0, link2.callsOf("{\"NewTask\"").size)
        st.close()
    }
}
