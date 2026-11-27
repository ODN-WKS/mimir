package io.github.odnwks.mimir.data

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

/** P44 (QA16 Q17): the PC's "who answers" for chats is remembered on the phone, and read again before any message goes. */
@RunWith(RobolectricTestRunner::class)
class P44RememberedChoiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private object Plain : Vault.Codec {
        override fun seal(text: String): ByteArray = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    /** A link that answers every call with an empty Ok, except "Models", which waits for [modelsReply]. */
    private class FakeLink(@Volatile var modelsReply: String? = null) : LinkApi {
        val events = LinkedBlockingQueue<String>()
        val calls = java.util.Collections.synchronizedList(ArrayList<Pair<Long, String>>())
        private val ids = AtomicLong(0)
        private val seq = AtomicLong(100)
        @Volatile var up = false
        @Volatile var connects = 0
        override fun start(key: ByteArray, relays: Boolean) = 1L
        override fun stop(handle: Long) {}
        override fun addHint(handle: Long, addr: String) {}
        override fun pairStart(handle: Long, code: String, phoneName: String) = "{}"
        override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = "{}"
        override fun connect(handle: Long, pcId: String): String { connects++; up = true; return "" }
        override fun call(handle: Long, json: String): Long {
            val id = ids.incrementAndGet()
            calls += id to json
            if (json == "\"Models\"") modelsReply?.let { reply(id, it) } else if (!json.startsWith("{\"Talk\"")) reply(id, "{}")
            return id
        }
        override fun nextEvent(handle: Long, timeoutMs: Long): String? = events.poll(minOf(timeoutMs, 50), TimeUnit.MILLISECONDS)
        override fun connected(handle: Long) = up
        override fun networkChanged(handle: Long) {}
        override fun endedBecause(handle: Long) = ""
        fun reply(id: Long, ok: String) { events.add("""{"seq":${seq.incrementAndGet()},"event":{"Reply":{"call_id":$id,"result":{"Ok":$ok}}}}""") }
        fun ready() {
            events.add("""{"seq":1,"event":{"Welcome":{"proto":2,"pc_name":"Test PC","pc_version":"0.21","min_app":"","run_id":"r1","features":["talk","chat_sync","truth","engines"]}}}""")
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

    private val vaultFile get() = File(tmp.root, "link.vault")
    private fun vault() = Vault(File(vaultFile.path), Plain, { ByteArray(32) }, tries = 1, pause = {})

    private fun station(link: FakeLink): LinkStation {
        if (!vaultFile.exists()) { val v = vault(); v.save(v.load().copy(pcId = "pc-1", pcName = "Test PC")) }
        val st = LinkStation(vault(), "0.21", relays = false, hints = emptyList(), secure = { true }, api = link)
        assertTrue("connected", until { link.connects >= 1 })
        return st
    }

    @Test fun thePcsChoiceIsRememberedAndShownAtTheNextStartBeforeThePcIsReached() {
        val first = FakeLink("""{"chats_engine":"paid"}""")
        val st = station(first)
        first.ready()
        assertTrue("remembered", until { vault().load().chatsEngine == "paid" })
        st.close()
        // Next start: the PC is not reached yet, and the person's choice already shows.
        val later = station(FakeLink())
        val m = later.state.value.models
        assertEquals("paid", m.chatsEngine)
        assertTrue(m.chatsEngineKnown)
        later.close()
    }

    @Test fun neverReadIsNotKnown() {
        val st = station(FakeLink())
        assertFalse(st.state.value.models.chatsEngineKnown)
        st.close()
    }

    @Test fun messagesWaitUntilTheChoiceIsReadOnThisConnection() {
        val link = FakeLink()
        val st = station(link)
        link.ready()
        assertTrue("ready", until { link.callsOf("\"Models\"").isNotEmpty() && st.canTalk() })
        assertFalse("not read yet", st.choiceRead())
        link.reply(link.callsOf("\"Models\"").first().first, """{"chats_engine":"free"}""")
        assertTrue("read", until { st.choiceRead() })
        assertEquals("free", st.state.value.models.chatsEngine)
        st.close()
    }
}
