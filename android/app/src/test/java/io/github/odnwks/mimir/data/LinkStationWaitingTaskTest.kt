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

/** A new task started while the PC is away waits on the phone and starts once, in order, when the link is back (QA8 H5). */
@RunWith(RobolectricTestRunner::class)
class LinkStationWaitingTaskTest {
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
        fun ready() {
            pcUp = true
            events.add("""{"seq":1,"event":{"Welcome":{"proto":2,"pc_name":"Test PC","pc_version":"0.21","min_app":"","run_id":"r1","features":["talk","chat_sync","truth","chat_only","engines"]}}}""")
            events.add("""{"seq":2,"event":{"Snapshot":{"run_id":"r1"}}}""")
            events.add("""{"seq":3,"event":"Ready"}""")
        }
        fun callsOf(prefix: String) = calls.filter { it.second.startsWith(prefix) }
    }

    private fun until(ms: Long = 8_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    private lateinit var vaultFile: File
    private fun vault() = Vault(File(vaultFile.path), Plain(), { ByteArray(32) }, tries = 1, pause = {})

    private fun station(link: FakeLink, model: String = "auto"): LinkStation {
        vaultFile = File(tmp.root, "link.vault")
        val v = vault()
        v.save(v.load().copy(pcId = "pc-1", pcName = "Test PC"))
        return LinkStation(vault(), "0.21", relays = false, hints = emptyList(), secure = { true }, api = link,
            projectStore = ProjectStore.InMemory(listOf("/p/garden" to "Garden planner")), modelStore = ModelStore.InMemory(model))
    }

    /** Paired, never connected yet: the PC is away until [FakeLink.ready]. */
    private fun away(link: FakeLink, model: String = "auto"): LinkStation {
        val st = station(link, model)
        // The PC is away: connecting fails until ready() is called.
        assertTrue("shown away", until { st.state.value.pcAway || link.connects == 0 })
        return st
    }

    private fun waitingIds() = vault().load().newTasks.map { it.id }
    private fun newTaskCalls(link: FakeLink) = link.callsOf("{\"NewTask\"")
    private fun msgId(json: String) = org.json.JSONObject(json).getJSONObject("NewTask").getString("msg_id")
    private fun textOf(json: String) = org.json.JSONObject(json).getJSONObject("NewTask").getString("text")
    private fun notes(st: LinkStation) = st.state.value.chat.filterIsInstance<ChatEntry.Note>().map { it.text }

    @Test fun aNewTaskWhileAwayIsKeptShownAsWaitingAndStartsOnceWhenThePcIsBack() {
        val link = FakeLink(); val st = away(link)
        val id = st.startTask("Say hello in five words", "Garden planner", incognito = false)
        assertTrue(id.startsWith("t-"))
        assertEquals(listOf(id), waitingIds())
        assertTrue("nothing sent yet", newTaskCalls(link).isEmpty())
        val t = st.state.value.task(id)!!
        assertEquals(WAITING_WORDS, t.stateWords)
        assertTrue(t.waitingToStart)
        assertEquals("Say hello in five words", t.messages.single().text)
        assertTrue(t.messages.single().waiting)
        val inProject = st.state.value.projects.first { it.name == "Garden planner" }.tasks
        assertEquals(listOf(id), inProject.map { it.taskId })
        assertEquals(WAITING_WORDS, inProject.single().meta)
        link.ready()
        assertTrue("started", until { newTaskCalls(link).size == 1 })
        assertEquals(id, msgId(newTaskCalls(link).single().second))
        link.reply(newTaskCalls(link).single().first, "\"real-1\"")
        assertTrue("answered, so no longer waiting", until { waitingIds().isEmpty() })
        assertEquals("real-1", st.state.value.startedTasks[id])
        st.close()
    }

    @Test fun aWaitingTaskSurvivesARestart() {
        val link = FakeLink(); val st = away(link)
        val id = st.startTask("Plan the beds", "Garden planner", incognito = false, plan = true)
        st.close()
        val again = vault().load().newTasks.single()
        assertEquals(id, again.id); assertEquals("Plan the beds", again.text); assertTrue(again.plan); assertEquals("/p/garden", again.project)
        val link2 = FakeLink()
        val st2 = LinkStation(vault(), "0.21", relays = false, hints = emptyList(), secure = { true }, api = link2,
            projectStore = ProjectStore.InMemory(listOf("/p/garden" to "Garden planner")))
        assertEquals(WAITING_WORDS, st2.state.value.task(id)?.stateWords)
        link2.ready()
        assertTrue(until { link2.connects >= 1 })
        assertTrue(until { newTaskCalls(link2).size == 1 })
        assertEquals(id, msgId(newTaskCalls(link2).single().second))
        assertTrue("sent as a plan", newTaskCalls(link2).single().second.contains("\"Plan\""))
        st2.close()
    }

    @Test fun twoWaitingTasksStartInTheOrderTheyWereMade() {
        val link = FakeLink(); val st = away(link)
        st.startTask("first", "Garden planner", incognito = false)
        st.startTask("second", "Garden planner", incognito = false)
        link.ready()
        assertTrue(until { newTaskCalls(link).size == 2 })
        assertEquals(listOf("first", "second"), newTaskCalls(link).map { textOf(it.second) })
        st.close()
    }

    @Test fun aLinkDroppedMidSendResendsTheSameIdAndNeverMakesASecondTask() {
        val link = FakeLink(); val st = away(link)
        val id = st.startTask("only once", "Garden planner", incognito = false)
        link.ready()
        assertTrue(until { newTaskCalls(link).size == 1 })
        link.up = false
        assertTrue("reconnected", until { link.connects >= 2 })
        link.ready()
        assertTrue("sent again", until { newTaskCalls(link).size == 2 })
        assertEquals("same id, so the PC answers the first task", newTaskCalls(link).map { msgId(it.second) }, listOf(id, id))
        link.reply(newTaskCalls(link).last().first, "\"real-1\"")
        assertTrue(until { waitingIds().isEmpty() })
        Thread.sleep(300)
        assertEquals("not sent a third time", 2, newTaskCalls(link).size)
        st.close()
    }

    @Test fun cancellingAWaitingTaskSendsNothing() {
        val link = FakeLink(); val st = away(link)
        val id = st.startTask("never mind", "Garden planner", incognito = false)
        st.cancelWaitingTask(id)
        assertTrue(waitingIds().isEmpty())
        assertEquals(null, st.state.value.task(id))
        assertTrue(st.state.value.projects.first { it.name == "Garden planner" }.tasks.isEmpty())
        link.ready()
        assertTrue(until { link.callsOf("\"Projects\"").isNotEmpty() })
        Thread.sleep(300)
        assertTrue(newTaskCalls(link).isEmpty())
        st.close()
    }

    @Test fun aWhisperTaskNeverWaits() {
        val link = FakeLink(); val st = away(link, "ollama:tiny")
        st.startTask("secret", "Garden planner", incognito = true)
        assertTrue(waitingIds().isEmpty())
        assertTrue(vault().load().newTasks.isEmpty())
        assertTrue("says why", until { notes(st).any { it.contains("can't wait") } })
        link.ready()
        Thread.sleep(300)
        assertTrue(link.callsOf("{\"NewIncognito\"").isEmpty())
        st.close()
    }

    @Test fun cancelCannotTakeBackATaskThatAlreadyWentToThePc() {
        val link = FakeLink(); val st = away(link)
        val id = st.startTask("on its way", "Garden planner", incognito = false)
        link.ready()
        assertTrue(until { newTaskCalls(link).size == 1 })
        assertFalse("not offered while it is on its way", st.state.value.task(id)!!.canCancel)
        st.cancelWaitingTask(id)
        assertEquals("still kept", listOf(id), waitingIds())
        assertTrue(until { notes(st).any { it.contains("already went to your PC") } })
        link.reply(newTaskCalls(link).single().first, "\"real-1\"")
        assertTrue(until { waitingIds().isEmpty() })
        st.close()
    }

    @Test fun aTaskThePcRefusesStaysAsAFailedItemWithItsWordsAndCanBeTriedAgainOrRemoved() {
        val link = FakeLink(); val st = away(link)
        val id = st.startTask("lost project", "Garden planner", incognito = false)
        link.ready()
        assertTrue(until { newTaskCalls(link).size == 1 })
        link.refuse(newTaskCalls(link).single().first, "That project isn't on your PC.")
        assertTrue(until { st.state.value.task(id)?.failedWhy?.isNotEmpty() == true })
        assertEquals("That project isn't on your PC.", st.state.value.task(id)!!.failedWhy)
        assertEquals("words kept", "lost project", vault().load().newTasks.single().text)
        assertEquals("words kept on the screen", "lost project", st.state.value.task(id)!!.messages.single().text)
        st.retryWaitingTask(id)
        assertTrue("sent again", until { newTaskCalls(link).size == 2 })
        link.refuse(newTaskCalls(link).last().first, "That project isn't on your PC.")
        assertTrue(until { st.state.value.task(id)?.failedWhy?.isNotEmpty() == true })
        st.cancelWaitingTask(id)
        assertTrue(waitingIds().isEmpty())
        st.close()
    }

    @Test fun aTaskThatCouldNotBeHandedToTheLinkWhileConnectedCanStillBeCancelled() {
        val link = FakeLink(); val st = away(link)
        val id = st.startTask("stuck", "Garden planner", incognito = false)
        link.ready()
        assertTrue(until { newTaskCalls(link).size == 1 })
        link.reply(newTaskCalls(link).single().first, "\"x\"")
        val id2 = run { link.dropped = true; st.startTask("second", "Garden planner", incognito = false) }
        Thread.sleep(300)
        assertTrue("not in flight, so it can be cancelled", st.state.value.task(id2)!!.canCancel)
        st.cancelWaitingTask(id2)
        assertFalse(waitingIds().contains(id2))
        st.close()
    }

    @Test fun startTaskSaysWhyItCouldNotStart() {
        val link = FakeLink()
        vaultFile = File(tmp.root, "link.vault")
        val v = vault(); v.save(v.load().copy(pcId = "pc-1", pcName = "Test PC"))
        val st = LinkStation(vault(), "0.21", relays = false, hints = emptyList(), secure = { true }, api = link)
        assertEquals("", st.startTask("words", "Nowhere", incognito = false))
        assertEquals("Pick a project first.", st.startProblem())
        st.close()
    }

    @Test fun theStopLineGoesOnlyOnceTheStopWentOutAndAWhisperLineStays() {
        val link = FakeLink(); val st = away(link, "ollama:tiny")
        st.startTask("secret", "Garden planner", incognito = true)
        st.stopTask("t1")
        assertTrue(until { notes(st).any { it.contains("Stop goes out") } })
        link.ready()
        assertTrue(until { link.callsOf("{\"Stop\"").size == 1 })
        assertTrue("the Stop line goes", until { notes(st).none { it.contains("Stop goes out") } })
        assertTrue("the Whisper line stays", notes(st).any { it.contains("can't wait") })
        st.close()
    }

    @Test fun aPcAwayLineGoesByItselfWhenTheLinkIsBack() {
        val link = FakeLink(); val st = away(link)
        st.stopTask("t1")
        assertTrue(until { notes(st).any { it.startsWith("Your PC is away") } })
        link.ready()
        assertTrue("the line is gone", until { notes(st).none { it.startsWith("Your PC is away") } })
        assertFalse(notes(st).any { it.startsWith("Your PC is away") })
        st.close()
    }
}
