package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.Ev
import io.github.odnwks.mimir.link.Wire
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatusCardTest {
    @Test fun onlyAWholeStatusAskGetsTheCard() {
        listOf("status", "Status?", "  status report. ", "What’s the status?", "what is running", "please give me a status update").forEach {
            assertTrue(it, asksStatus(it))
        }
        listOf("fix the status bar", "status of the build in Recipe box", "what's the status of my order with the shop", "how's it going", "", "stat").forEach {
            assertFalse(it, asksStatus(it))
        }
    }

    @Test fun theChecklistComesWithTheTaskHeader() {
        val line = """{"seq":1,"event":{"Task":{"header":{"id":"t-1","title":"Dark colours","project":"/p","status":"Working",
            "plan":[{"id":"0","text":"Read","done":true,"doing":false},{"id":"1","text":"Paint","done":false,"doing":true}]}}}}"""
        val head = Wire.event(line).second as Ev.TaskHeader
        assertEquals(listOf(PlanStep("Read", true, false), PlanStep("Paint", false, true)), head.task.checklist)
    }

    private val now = 1_800_000_000_000L
    private val sec = now / 1000

    private fun task(id: String, status: String, busy: Boolean = false, checklist: List<PlanStep> = emptyList(), since: Long = 0, whisper: Boolean = false) = Task(
        id = id, title = "Job $id", project = "Garden planner", model = "", age = "", steps = emptyList(), messages = emptyList(),
        question = null, permission = null, change = null, status = status, busy = busy, checklist = checklist, sinceUnix = since, whisper = whisper,
    )

    private fun state(tasks: List<Task>, online: Boolean = true) = ExampleData.awake().let {
        it.copy(tasks = tasks, needsYou = emptyList(), pcSessions = null, pc = it.pc.copy(online = online, seenAtMs = now - 60_000))
    }

    @Test fun percentStepAndNextComeOnlyFromTheChecklist() {
        val plan = listOf(PlanStep("Read", true, false), PlanStep("Paint", true, false), PlanStep("Check", true, false), PlanStep("Test", false, true), PlanStep("Tidy up", false, false))
        val s = statusSnapshot(state(listOf(task("a", "Working", busy = true, checklist = plan, since = sec - 100), task("b", "Working", busy = true, since = sec - 50))), now, ZoneOffset.UTC)
        val (b, a) = s.running
        assertEquals(60, a.percent)
        assertEquals("step 4 of 5", a.step)
        assertEquals("Tidy up", a.next)
        assertEquals(sec - 100, a.timeUnix)
        assertNull("no checklist, no percent", b.percent)
        assertEquals("", b.step)
        assertEquals(now, s.asOfMs)
    }

    @Test fun finishedTodayNeedsYouWhisperAndSessionsElsewhere() {
        val base = state(listOf(
            task("done", "Done", since = sec - 10), task("old", "Done", since = sec - 3 * 86_400), task("ask", "Needs you"),
            task("w", "Working", busy = true, whisper = true),
        ))
        val s = statusSnapshot(base.copy(pcSessions = listOf(
            PcSession("s1", "Recipe search", "Recipe box", here = false, app = false, task = null, state = PcSessionState.Working, lastUnix = sec),
            PcSession("s2", "Asleep", "Recipe box", here = false, app = true, task = null, state = PcSessionState.Idle, lastUnix = sec),
        )), now, ZoneOffset.UTC)
        assertEquals(listOf("Job done"), s.finished.map { it.title })
        assertEquals(listOf("Job ask: needs you"), s.needsYou.map { it.words })
        assertEquals(setOf("Whisper task", "Recipe search"), s.running.map { it.title }.toSet())
        assertEquals("Your PC, in a terminal · Recipe box", s.running.single { it.title == "Recipe search" }.where)
    }

    /** The PC may give a stopped task only the time it stopped, with no "last active". */
    @Test fun aTaskThatStoppedUnexpectedlyTodayNeedsYou() {
        val s = statusSnapshot(state(listOf(task("x", "Stopped unexpectedly", since = sec - 10), task("y", "Stopped unexpectedly", since = sec - 3 * 86_400))), now, ZoneOffset.UTC)
        assertEquals(listOf("Job x: stopped unexpectedly"), s.needsYou.map { it.words })
    }

    @Test fun withThePcAwayTheCardSaysWhenItWasLastTrue() {
        val s = statusSnapshot(state(listOf(task("a", "Was working (PC away)")), online = false), now, ZoneOffset.UTC)
        assertTrue(s.away)
        assertEquals(now - 60_000, s.asOfMs)
        assertEquals(1, s.running.size)
    }
}
