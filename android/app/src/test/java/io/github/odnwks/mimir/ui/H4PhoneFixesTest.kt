package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithText
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.PcSession
import io.github.odnwks.mimir.data.PcSessionState
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.data.signedOutWords
import io.github.odnwks.mimir.data.taskMessageOf
import io.github.odnwks.mimir.link.LEntry
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** QA13 phone re-check (H4): each test names the finding it came from. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class H4PhoneFixesTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val id = ExampleData.TASK_ALLOW

    private fun station(mut: (Task) -> Task, sessions: List<PcSession>? = null) = FakeStation().also { st ->
        st.set { s -> s.copy(pcSessions = sessions, tasks = s.tasks.map { if (it.id == id) mut(it) else it }) }
    }
    private fun show(st: FakeStation) { compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }; compose.waitForIdle(); nav.open(Screen.Task(id)); compose.waitForIdle() }
    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size
    private val quiet = { t: Task ->
        t.copy(busy = false, stopped = false, status = "Done", permission = null, question = null, steps = emptyList(),
            messages = listOf(TaskMessage("m1", false, "Sort the seeds"), TaskMessage("m2", true, "All sorted.", "Play the reply")))
    }

    // N1: the same answer offers Try again on a task of its own, and not while it runs in a terminal
    @Test fun n1_aTaskOfItsOwnOffersTryAgain() {
        show(station(quiet))
        compose.onAllNodesWithText("All sorted.", substring = true).onLast().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(1, count("Try again"))
    }

    @Test fun n1_aSessionRunningElsewhereOffersNoTryAgain() {
        val live = PcSession("s-1", "Plan", "Garden planner", false, false, id, PcSessionState.Working, 1_800_000_000L)
        show(station({ quiet(it).copy(liveElsewhere = true) }, listOf(live)))
        compose.onNodeWithText("Still running in a terminal. Stop it to carry on here.").assertIsDisplayed()
        compose.onAllNodesWithText("All sorted.", substring = true).onLast().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(0, count("Try again"))
    }

    // N3: the view follows new lines only from the bottom (or for the person's own message, or the keyboard)
    @Test fun n3_followsTheBottomOnlyFromTheBottom() {
        assertTrue(followsBottom(atBottom = true, lastIsOwn = false, keyboard = false))
        assertFalse(followsBottom(atBottom = false, lastIsOwn = false, keyboard = false))
        assertTrue(followsBottom(atBottom = false, lastIsOwn = true, keyboard = false))
        assertTrue(followsBottom(atBottom = false, lastIsOwn = false, keyboard = true))
    }

    // N4: Claude Code's sign-in error is one plain line
    @Test fun n4_aSignInErrorReadsPlain() {
        val plain = "Claude isn't signed in on your PC. Open Mímir on your PC to sign in, or use your PC's own model."
        assertEquals(plain, signedOutWords("Not logged in · Please run /login"))
        assertEquals(plain, taskMessageOf("t", 1, LEntry.Claude("Not logged in · Please run /login"), false)!!.text)
        assertNull(signedOutWords("Here is how /login works in Claude Code, step by step, with a long explanation so it is not a short error line at all, which is what this guard is for."))
        assertEquals("Done.", taskMessageOf("t", 1, LEntry.Claude("Done."), false)!!.text)
    }

    // N6: the hand-over line is a dim note, a real message of the person stays a message
    @Test fun n6_theHandOverLineIsANote() {
        val m = taskMessageOf("t", 0, LEntry.You("Carry on with this task from the summary."), false)!!
        assertTrue(m.note)
        assertFalse(taskMessageOf("t", 0, LEntry.You("Sort the seeds"), false)!!.note)
    }

    // N8: a project with a session working elsewhere does not read idle while "Your PC" says it runs
    @Test fun n8_aProjectWithASessionRunningElsewhereIsNotIdle() {
        val st = FakeStation()
        val state = st.state.value
        val p = state.projects.first()
        val live = PcSession("s-9", "Plan", p.name, false, false, null, PcSessionState.Working, 1_800_000_000L)
        val running = state.copy(pcSessions = listOf(live), paired = true, pc = state.pc.copy(online = true))
        assertEquals(1, projectRunningElsewhere(running, p))
        assertEquals(0, projectRunningElsewhere(running.copy(pcSessions = listOf(live.copy(here = true))), p))
        assertEquals("idle", projectState(p.copy(needsYou = 0, tasksRunning = 0), 0).first)
        assertEquals("● 1 task running", projectState(p.copy(needsYou = 0, tasksRunning = 0), 1).first)
    }
}
