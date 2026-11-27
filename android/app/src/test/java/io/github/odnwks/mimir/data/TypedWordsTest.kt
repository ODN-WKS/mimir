package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.LinkApi
import io.github.odnwks.mimir.link.Vault
import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.standalone.TurnFile
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

/** Words typed into a message box go to the PC marked `typed`; everything else goes without the field. */
@RunWith(RobolectricTestRunner::class)
class TypedWordsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Plain : Vault.Codec {
        override fun seal(text: String): ByteArray = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    private class FakeLink : LinkApi {
        val events = LinkedBlockingQueue<String>()
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        private val ids = AtomicLong(0)
        private val seq = AtomicLong(100)
        @Volatile var sendAnswer = """{"Ok":{}}"""
        @Volatile var up = false
        @Volatile var pcUp = false
        override fun start(key: ByteArray, relays: Boolean) = 1L
        override fun stop(handle: Long) {}
        override fun addHint(handle: Long, addr: String) {}
        override fun pairStart(handle: Long, code: String, phoneName: String) = "{}"
        override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = "{}"
        override fun connect(handle: Long, pcId: String): String { if (!pcUp) return "Couldn't connect."; up = true; return "" }
        override fun call(handle: Long, json: String): Long {
            if (!up) return -1L
            val id = ids.incrementAndGet()
            calls += json
            val result = when {
                json.startsWith("\"Projects\"") -> """{"Ok":{"projects":[{"path":"/p/garden","name":"Garden planner"}],"last":null}}"""
                json.startsWith("{\"Send\"") -> sendAnswer
                else -> """{"Ok":{}}"""
            }
            events.add("""{"seq":${seq.incrementAndGet()},"event":{"Reply":{"call_id":$id,"result":$result}}}""")
            return id
        }
        override fun nextEvent(handle: Long, timeoutMs: Long): String? = events.poll(minOf(timeoutMs, 50), TimeUnit.MILLISECONDS)
        override fun connected(handle: Long) = up
        override fun networkChanged(handle: Long) {}
        override fun endedBecause(handle: Long) = ""
        fun ready() {
            pcUp = true
            events.add("""{"seq":1,"event":{"Welcome":{"proto":2,"pc_name":"Test PC","pc_version":"0.21","min_app":"","run_id":"r1","features":["task_files","task_reply_options"]}}}""")
            events.add("""{"seq":2,"event":{"Snapshot":{"run_id":"r1"}}}""")
            events.add("""{"seq":3,"event":"Ready"}""")
            events.add("""{"seq":4,"event":{"Task":{"header":{"id":"t1","title":"Garden","project":"/p/garden","status":"Done","busy":false,"created_unix":1}}}}""")
        }
        /** The bodies of the [name] calls so far. */
        fun bodies(name: String) = synchronized(calls) { calls.filter { it.startsWith("{\"$name\"") }.map { JSONObject(it).getJSONObject(name) } }
    }

    private fun until(ms: Long = 8_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    private fun vault() = Vault(File(tmp.root, "link.vault"), Plain(), { ByteArray(32) }, tries = 1, pause = {})

    private fun station(link: FakeLink): LinkStation {
        val v = vault()
        v.save(v.load().copy(pcId = "pc-1", pcName = "Test PC"))
        return LinkStation(vault(), "0.21", relays = false, hints = emptyList(), secure = { true }, api = link, attachDir = File(tmp.root, "attach"),
            projectStore = ProjectStore.InMemory(listOf("/p/garden" to "Garden planner")))
    }

    private fun connected(link: FakeLink): LinkStation {
        val st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null && link.calls.any { it.startsWith("\"Projects\"") } })
        return st
    }

    private fun typed(o: JSONObject) = o.optBoolean("typed")

    /** An older PC must see exactly the call it always did: the field is left out, never `false`. */
    @Test fun theWireCarriesTypedOnlyWhenSet() {
        val rows = listOf<Pair<String, (Boolean) -> String>>(
            "Send" to { t -> Wire.send("t", "hi", typed = t) },
            "NewTask" to { t -> Wire.newTask("/p", "hi", typed = t) },
            "Chat" to { t -> Wire.chat("m", "hi", typed = t) },
            "Talk" to { t -> Wire.talk("c", "m", emptyList(), typed = t) },
            "NewIncognito" to { t -> Wire.newIncognito("/p", "hi", "ollama:x", typed = t) },
        )
        for ((name, call) in rows) {
            assertFalse(name, JSONObject(call(false)).getJSONObject(name).has("typed"))
            assertTrue(name, JSONObject(call(true)).getJSONObject(name).getBoolean("typed"))
        }
        assertFalse(JSONObject(Wire.send("t", "hi")).getJSONObject("Send").has("typed"))
    }

    @Test fun aReplyIsMarkedOnlyWhenTyped() {
        val link = FakeLink(); val st = connected(link)
        st.reply("t1", "from the box", typed = true)
        assertTrue(until { link.bodies("Send").size == 1 })
        st.reply("t1", "tried again")
        assertTrue(until { link.bodies("Send").size == 2 })
        st.editTaskMessage("t1", "x", "edited words") {}
        assertTrue(until { link.bodies("Send").size == 3 })
        st.reply("t1", "", files = listOf(TurnFile("list.txt", "hi", bytes = byteArrayOf(1), mime = "text/plain")), typed = true)
        assertTrue(until { link.bodies("Send").size == 4 })
        assertEquals(listOf(true, false, true, false), link.bodies("Send").map(::typed))
        st.close()
    }

    @Test fun aReplyHeldWhileThePcIsAwayKeepsItsMarkAcrossARestart() {
        val link = FakeLink(); var st = connected(link)
        link.up = false; link.pcUp = false
        assertTrue(st.reply("t1", "typed while away", typed = true))
        assertTrue(st.reply("t1", "not typed"))
        st.close()
        val link2 = FakeLink(); st = station(link2); link2.ready()
        assertTrue(until { link2.bodies("Send").size == 2 })
        assertEquals(listOf("typed while away" to true, "not typed" to false), link2.bodies("Send").map { it.getString("text") to typed(it) })
        st.close()
    }

    @Test fun aReplyThePcRefusedAndIsTriedAgainIsNotMarked() {
        val link = FakeLink(); link.sendAnswer = """{"Err":"Busy right now."}"""
        val st = connected(link)
        st.reply("t1", "again please", typed = true)
        assertTrue(until { st.state.value.task("t1")?.messages?.any { it.waiting && it.failedWhy.isNotEmpty() } == true })
        link.sendAnswer = """{"Ok":{}}"""
        st.retryReply(st.state.value.task("t1")!!.messages.first { it.waiting }.id)
        assertTrue(until { link.bodies("Send").size == 2 })
        assertEquals(listOf(true, false), link.bodies("Send").map(::typed))
        st.close()
    }

    @Test fun aNewTaskAndAChatMessageAreMarkedOnlyWhenTyped() {
        val link = FakeLink(); val st = connected(link)
        st.startTask("typed task", "Garden planner", incognito = false, typed = true)
        assertTrue(until { link.bodies("NewTask").size == 1 })
        st.startTask("a starter", "Garden planner", incognito = false)
        assertTrue(until { link.bodies("NewTask").size == 2 })
        assertEquals(listOf(true, false), link.bodies("NewTask").map(::typed))
        st.send("typed in chat", typed = true)
        assertTrue(until { link.bodies("Chat").size == 1 })
        st.send("What's the status?")
        assertTrue(until { link.bodies("Chat").size == 2 })
        assertEquals(listOf(true, false), link.bodies("Chat").map(::typed))
        st.close()
    }
}
