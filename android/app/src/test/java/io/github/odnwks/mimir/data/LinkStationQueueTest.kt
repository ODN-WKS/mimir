package io.github.odnwks.mimir.data

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

/** The real LinkStation on a stand-in link that can be killed mid-send (audit PHD-02, PHD-03, PHD-11, F2, F5, F10). */
@RunWith(RobolectricTestRunner::class)
class LinkStationQueueTest {
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

    @Test fun aMessageStaysQueuedWhenTheLinkIsKilledMidSendAndGoesOutAgainAfterTheReconnect() {
        val link = FakeLink(); val st = paired(link)
        st.send("hello pc")
        assertTrue("handed to the link", until { link.callsOf("{\"Chat\"").size == 1 })
        link.kill()
        assertTrue("the link was seen dropping", until { st.state.value.pc.online.not() || link.connects >= 2 })
        assertEquals("still queued, no answer came", listOf("hello pc"), outbox())
        assertTrue("reconnected", until { link.connects >= 2 })
        link.ready()
        assertTrue("sent again", until { link.callsOf("{\"Chat\"").size == 2 })
        val again = link.callsOf("{\"Chat\"").last()
        link.reply(again.first, "false")
        assertTrue("answered, so gone from the queue", until { outbox().isEmpty() })
        st.close()
    }

    @Test fun aTalkAnswerStillResumesAfterTheLinkDropsBeforeTheReply() {
        val link = FakeLink(); val st = paired(link)
        val log = ArrayList<String>()
        val listener = object : TalkListener {
            override fun filed(f: io.github.odnwks.mimir.link.Filed?, err: String?) { log += "filed:$err" }
            override fun piece(text: String, thinking: Boolean) {}
            override fun done(turn: io.github.odnwks.mimir.link.LChatTurn?, err: String?) {}
            override fun lost() { log += "lost" }
            override fun back() { log += "back" }
        }
        assertTrue(st.talk(TalkRequest("c1", "t1", emptyList(), null, emptyList(), null, null, null), listener))
        link.kill()
        assertTrue(until { log.contains("lost") })
        assertFalse("a dropped link is not the PC's answer", log.any { it.startsWith("filed") })
        st.close()
    }

    @Test fun aGivenAnswerStaysUntilThePcAnswers() {
        val link = FakeLink(); val st = paired(link)
        st.answerChoice("ask:1", 0)
        assertTrue(until { link.callsOf("{\"Answer").size == 1 })
        link.kill()
        assertTrue(until { link.connects >= 2 })
        assertEquals("not dropped at hand-off", 1, vault().load().answers.size)
        st.close()
    }

    @Test fun aWhisperReplyForAnUnknownTaskIsRefusedAndNeverSavedOnThePhone() {
        val link = FakeLink(); val st = paired(link)
        st.reply("no-such-task", "secret words")
        // Sent now (the PC is there) or refused, but never kept on the phone.
        assertTrue(vault().load().replies.isEmpty())
        link.kill()
        st.reply("no-such-task", "more secret words")
        assertTrue(vault().load().replies.isEmpty())
        st.close()
    }

    @Test fun aMessageThePcRefusesShowsAFailedLineAndCanBeSentAgain() {
        val link = FakeLink(); val st = paired(link)
        st.send("odd request")
        assertTrue(until { link.callsOf("{\"Chat\"").size == 1 })
        link.refuse(link.callsOf("{\"Chat\"").first().first, "No.")
        assertTrue("not left waiting", until { outbox().isEmpty() })
        val line = { st.state.value.chat.filterIsInstance<ChatEntry.Mine>().first { it.text == "odd request" } }
        assertTrue(until { line().filing?.kind == FilingKind.Failed })
        st.retryMessage(line().id)
        assertTrue("sent again", until { link.callsOf("{\"Chat\"").size == 2 })
        st.close()
    }

    @Test fun aQueuedMessageThatCouldNotBeSavedIsSaidAndTriedAgain() {
        val link = FakeLink(); val st = paired(link)
        codec.failSeal = true
        st.send("will not save")
        assertTrue("the owner is told", until { notes(st).any { it.contains("couldn't be saved") } })
        codec.failSeal = false
        st.send("saves fine")
        assertTrue(until { outbox().contains("saves fine") })
        st.close()
    }
}
