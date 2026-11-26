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

/** Pictures and files in a task's reply box (owner: "couldn't send photos"), and the model and effort beside it. */
@RunWith(RobolectricTestRunner::class)
class TaskReplyAttachTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Plain : Vault.Codec {
        override fun seal(text: String): ByteArray = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    private class FakeLink(val features: String) : LinkApi {
        val events = LinkedBlockingQueue<String>()
        val calls = java.util.Collections.synchronizedList(ArrayList<Pair<Long, String>>())
        private val ids = AtomicLong(0)
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
    private val file = TurnFile("notes.pdf", "", bytes = byteArrayOf(9, 8, 7), mime = "application/pdf")

    private val all = "\"talk\",\"chat_sync\",\"truth\",\"task_files\",\"task_reply_options\""

    @Test fun aPictureAloneIsAReplyAndReachesTheWireWithItsPictureAndFile() {
        val link = FakeLink(all); val st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null && st.canAttachToTasks() })
        assertTrue("a picture with no words is taken", st.reply("t1", "", listOf(pic), listOf(file)))
        assertTrue("sent", until { link.callsOf("{\"Send\"").size == 1 })
        val send = JSONObject(link.callsOf("{\"Send\"").single().second).getJSONObject("Send")
        assertEquals(1, send.getJSONArray("pictures").length())
        assertEquals("image/jpeg", send.getJSONArray("pictures").getJSONObject(0).getString("media_type"))
        assertEquals("notes.pdf", send.getJSONArray("files").getJSONObject(0).getString("name"))
        st.close()
    }

    @Test fun anEmptyReplyWithNothingAttachedIsNotTaken() {
        val link = FakeLink(all); val st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null })
        assertFalse(st.reply("t1", "  ", emptyList(), emptyList()))
        st.close()
    }

    @Test fun aReplyWithPicturesWhileAwayKeepsThemAfterTheAppIsRestartedAndSendsThemOnce() {
        val link = FakeLink(all)
        // First run: the PC knows the task, then goes away.
        var st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null })
        link.up = false; link.pcUp = false
        assertTrue(st.reply("t1", "look at this", listOf(pic), listOf(file)))
        assertTrue("kept sealed on the phone", attachDir.listFiles().orEmpty().any { it.name.endsWith(".att") })
        st.close()
        // Second run: a new app process; the held pictures go with the words.
        val link2 = FakeLink(all); st = station(link2); link2.ready()
        assertTrue("sent after the restart", until { link2.callsOf("{\"Send\"").size == 1 })
        val send = JSONObject(link2.callsOf("{\"Send\"").single().second).getJSONObject("Send")
        assertEquals("look at this", send.getString("text"))
        assertEquals(1, send.getJSONArray("pictures").length())
        assertEquals(1, send.getJSONArray("files").length())
        st.close()
    }

    @Test fun anOldPcWithoutTheFeaturesOffersNeitherAttachingNorTheEffortChoice() {
        val link = FakeLink("\"talk\",\"chat_sync\",\"truth\""); val st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null })
        assertFalse(st.canAttachToTasks())
        assertFalse(st.canSetReplyOptions())
        st.setTaskEffort("t1", "high")
        Thread.sleep(200)
        assertTrue("no effort call to an old PC", link.callsOf("{\"SetEffort\"").isEmpty())
        st.close()
    }

    @Test fun aPcWithTheFeatureTakesTheEffortAndTheChipShowsIt() {
        val link = FakeLink(all); val st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null && st.canSetReplyOptions() })
        st.setTaskEffort("t1", "xhigh")
        assertTrue(until { link.callsOf("{\"SetEffort\"").size == 1 })
        assertEquals("xhigh", JSONObject(link.callsOf("{\"SetEffort\"").single().second).getJSONObject("SetEffort").getString("effort"))
        assertTrue(until { st.state.value.task("t1")?.effort == "xhigh" })
        st.close()
    }

    @Test fun wireSendCarriesPicturesAndFilesOnlyWhenThereAreSome() {
        val plain = JSONObject(Wire.send("t", "hi", "m1")).getJSONObject("Send")
        assertEquals(0, plain.getJSONArray("pictures").length())
        assertFalse(plain.has("files"))
        val with = JSONObject(Wire.send("t", "", "m2", listOf(LPicture("image/jpeg", "AAA")), listOf(LFile("a.txt", "text/plain", "BBB")))).getJSONObject("Send")
        assertEquals("AAA", with.getJSONArray("pictures").getJSONObject(0).getString("base64"))
        assertEquals("BBB", with.getJSONArray("files").getJSONObject(0).getString("base64"))
        val eff = JSONObject(Wire.newTask("p", "x", effort = "max")).getJSONObject("NewTask")
        assertEquals("max", eff.getString("effort"))
    }

    @Test fun theFakeStationKeepsWhatWasSentWithAReplyAndShowsAPictureOnlyLine() {
        val fake = FakeStation()
        val id = fake.state.value.tasks.first().id
        assertTrue(fake.reply(id, "", listOf(pic), emptyList()))
        assertEquals(listOf(Triple(id, 1, emptyList<String>())), fake.repliedWith)
        assertEquals("A picture", fake.state.value.task(id)!!.messages.last().text)
    }

    @Test fun aNewTaskCarriesItsPicturesFilesAndEffortToThePc() {
        val link = FakeLink(all); val st = station(link); link.ready()
        assertTrue(until { st.state.value.task("t1") != null && st.canAttachToTasks() })
        assertTrue("ready", until { link.callsOf("\"Projects\"").isNotEmpty() })
        val id = st.startTask("Look at this", "Garden planner", incognito = false, pictures = listOf(pic), files = listOf(file), effort = "high")
        assertTrue("started: " + st.startProblem(), id.startsWith("t-"))
        assertTrue("calls: " + link.calls.map { it.second.take(60) }, until { link.callsOf("{\"NewTask\"").size == 1 })
        val nt = JSONObject(link.callsOf("{\"NewTask\"").single().second).getJSONObject("NewTask")
        assertEquals(1, nt.getJSONArray("pictures").length())
        assertEquals("notes.pdf", nt.getJSONArray("files").getJSONObject(0).getString("name"))
        assertEquals("high", nt.getString("effort"))
        st.close()
    }

    @Test fun theNewTaskScreenKeepsEffortInItsOptionsAndAnOldPcHidesIt() {
        assertTrue(io.github.odnwks.mimir.nav.ComposeTo().auto)
        assertFalse(io.github.odnwks.mimir.nav.ComposeTo(effort = "max").auto)
        // The effort is picked in the model and effort sheet now (P12), not stepped through on a chip.
        assertEquals("Opus 5.5 · High", io.github.odnwks.mimir.ui.optionsWords("Opus 5.5", "high"))
        assertEquals("Opus 5.5", io.github.odnwks.mimir.ui.optionsWords("Opus 5.5", "high", withEffort = false))
        assertFalse(FakeStation().also { it.optionsOk = false }.canSetReplyOptions())
    }
}
