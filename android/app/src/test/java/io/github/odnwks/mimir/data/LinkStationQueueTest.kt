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
        /** The link is gone for calls (the real one hands back no call id). */
        @Volatile var dropped = false
        /** The PC answers a Stop with this refusal instead of Ok. */
        @Volatile var refuseStop: String? = null
        @Volatile var connects = 0
        /** The PC can't be reached for a new session. */
        @Volatile var refuseConnect = false
        /** The session looks open but carries nothing: no call is answered. */
        @Volatile var silent = false
        private val seq = AtomicLong(100)
        private val held = listOf("{\"Chat\"", "{\"Send\"", "{\"Talk\"", "{\"Answer", "{\"NewTask\"", "{\"Listen\"")
        override fun start(key: ByteArray, relays: Boolean) = 1L
        override fun stop(handle: Long) {}
        override fun addHint(handle: Long, addr: String) {}
        override fun pairStart(handle: Long, code: String, phoneName: String) = "{}"
        override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = "{}"
        override fun connect(handle: Long, pcId: String): String { if (refuseConnect) { up = false; return "Couldn't connect." }; connects++; up = true; return "" }
        override fun call(handle: Long, json: String): Long {
            if (dropped) return -1L
            val id = ids.incrementAndGet()
            calls += id to json
            val why = refuseStop
            if (why != null && json.startsWith("{\"Stop\"")) { refuse(id, why); return id }
            if (!silent && held.none { json.startsWith(it) }) reply(id, "{}")
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
        val log = java.util.Collections.synchronizedList(ArrayList<String>())
        val listener = object : TalkListener {
            override fun filed(f: io.github.odnwks.mimir.link.Filed?, err: String?) { log += "filed:$err" }
            override fun piece(text: String, thinking: Boolean) {}
            override fun done(turn: io.github.odnwks.mimir.link.LChatTurn?, err: String?) {}
            override fun lost() { log += "lost" }
            override fun back() { log += "back" }
        }
        assertTrue(st.talk(TalkRequest("c1", "t1", emptyList(), null, emptyList(), null, null, null), listener))
        link.kill()
        assertTrue(until { synchronized(log) { log.contains("lost") } })
        assertFalse("a dropped link is not the PC's answer", synchronized(log) { log.any { it.startsWith("filed") } })
        st.close()
    }

    // P40 (QA16 Q6): after a quiet spell the link still looked open but carried nothing; every message
    // failed after a second and the app kept saying "connected" until it was closed and opened again.
    @Test fun aChatMessageAnOpenLinkNeverAnswersRedoesTheLinkInsteadOfFailing() {
        val link = FakeLink(); val st = paired(link)
        assertTrue(until { st.state.value.pc.online })
        val log = java.util.Collections.synchronizedList(ArrayList<String>())
        val listener = object : TalkListener {
            override fun filed(f: io.github.odnwks.mimir.link.Filed?, err: String?) { log += "filed:$err" }
            override fun piece(text: String, thinking: Boolean) {}
            override fun done(turn: io.github.odnwks.mimir.link.LChatTurn?, err: String?) { log += "done:$err" }
            override fun lost() { log += "lost" }
            override fun back() { log += "back" }
            override fun nudge() { log += "nudge" }
        }
        // The link stays "up" but the PC never answers the message: no failure, the link is made again.
        link.refuseConnect = true
        link.silent = true
        assertTrue(st.talk(TalkRequest("c1", "t1", emptyList(), null, emptyList(), null, null, null), listener))
        assertTrue("not shown as connected once the link carried nothing", until(9_000) { !st.state.value.pc.online })
        link.refuseConnect = false
        link.silent = false
        assertTrue("the link was made again", until(8_000) { link.connects >= 2 })
        assertFalse("no failure was given for the message", synchronized(log) { log.any { it.startsWith("done") || it.startsWith("filed") } })
        link.ready()
        assertTrue("the answer is looked for again once the link is back", until { synchronized(log) { log.contains("back") } })
        assertTrue("connected again", until { st.state.value.pc.online })
        st.close()
    }

    private class Log : TalkListener {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun filed(f: io.github.odnwks.mimir.link.Filed?, err: String?) { seen += "filed:$err" }
        override fun piece(text: String, thinking: Boolean) {}
        override fun done(turn: io.github.odnwks.mimir.link.LChatTurn?, err: String?) { seen += "done:$err" }
        override fun lost() { seen += "lost" }
        override fun back() { seen += "back" }
        override fun nudge() { seen += "nudge" }
        fun has(s: String) = synchronized(seen) { seen.contains(s) }
    }

    // P40 fix 2: a "taken" reply slower than 2 s on a link that still carries the PC's answers redid the
    // link, and every call still waiting (task replies, recordings, lookups) failed with it.
    @Test fun aSlowTakenReplyOnALinkThatStillCarriesKeepsTheLinkAndItsCalls() {
        val link = FakeLink(); val st = paired(link)
        assertTrue(until { st.state.value.pc.online })
        val log = Log()
        assertTrue(st.talk(TalkRequest("c1", "t1", emptyList(), null, emptyList(), null, null, null), log))
        Thread.sleep(LinkStation.TALK_TAKEN_MS + io.github.odnwks.mimir.data.LinkStation.PROBE_WAIT_MS + 1_500)
        assertEquals("the link was kept", 1, link.connects)
        assertTrue("still shown as connected", st.state.value.pc.online)
        assertFalse("the waiting answer wasn't disturbed", synchronized(log.seen) { log.seen.any { it == "nudge" || it == "lost" || it.startsWith("done") || it.startsWith("filed") } })
        // The slow reply still lands.
        val talk = link.callsOf("{\"Talk\"").single()
        link.reply(talk.first, """{"Talk":{"answer":"t1.a","ran":"pc","model_words":"Your PC"}}""")
        assertTrue("the reply is taken", until { synchronized(log.seen) { log.seen.contains("filed:null") } })
        st.close()
    }

    // P40 finding 4: a link this phone redid that can't be made again is a PC that's away: said at once,
    // not after 30 s of silence.
    @Test fun aRedoneLinkThatCantBeMadeAgainSaysThePcIsAway() {
        val link = FakeLink(); val st = paired(link)
        assertTrue(until { st.state.value.pc.online })
        val log = Log()
        link.silent = true
        link.refuseConnect = true
        assertTrue(st.talk(TalkRequest("c1", "t1", emptyList(), null, emptyList(), null, null, null), log))
        assertTrue("told the PC is away once the link can't be made again", until(12_000) { log.has("lost") })
        st.close()
    }

    @Test fun aCallTheOpenLinkCantTakeRedoesTheLink() {
        val link = FakeLink(); val st = paired(link)
        val listener = object : TalkListener {
            override fun filed(f: io.github.odnwks.mimir.link.Filed?, err: String?) {}
            override fun piece(text: String, thinking: Boolean) {}
            override fun done(turn: io.github.odnwks.mimir.link.LChatTurn?, err: String?) {}
            override fun lost() {}
            override fun back() {}
        }
        // The session says it is open, but no call can go out on it.
        link.dropped = true
        assertTrue("kept for when the link is back", st.talk(TalkRequest("c1", "t2", emptyList(), null, emptyList(), null, null, null), listener))
        link.dropped = false
        assertTrue("the link was made again", until(6_000) { link.connects >= 2 })
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

    @Test fun aStopOnADroppedLinkSaysThePcIsAwayAndGoesOutAgainWhenItsBack() {
        val link = FakeLink(); val st = paired(link)
        link.dropped = true
        st.stopTask("t1")
        // The task itself says "Stop waiting: sends when your PC is back" (QA11 T7).
        assertTrue("the task says the stop waits", until { st.stopIsWaiting("t1") })
        assertTrue("nothing went out", link.callsOf("{\"Stop\"").isEmpty())
        link.dropped = false
        link.ready()
        assertTrue("Stop is sent again once the PC is back", until { link.callsOf("{\"Stop\"").size == 1 })
        assertTrue("and the line goes", until { !st.stopIsWaiting("t1") })
        st.close()
    }

    @Test fun aStopTheConnectedPcRefusesIsNotKeptAndTheReasonShowsOnce() {
        val link = FakeLink(); val st = paired(link)
        link.refuseStop = "This task isn't running here."
        st.stopTask("t1")
        assertTrue("the PC's words show", until { notes(st).any { it.contains("isn't running here") } })
        assertTrue("the stop is not kept", until { !st.stopIsWaiting("t1") })
        // A later reconnect does not send it again.
        link.refuseStop = null
        link.ready()
        Thread.sleep(300)
        assertEquals("sent once only", 1, link.callsOf("{\"Stop\"").size)
        assertEquals("said once", 1, notes(st).count { it.contains("isn't running here") })
        st.close()
    }
}
