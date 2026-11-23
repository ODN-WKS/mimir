package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.MimirTheme
import androidx.compose.ui.test.onAllNodesWithText
import io.github.odnwks.mimir.data.PcSession
import io.github.odnwks.mimir.data.PcSessionState
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** QA12 phone re-hunt (H2): each test names the finding it came from. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class H2PhoneFixesTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val id = ExampleData.TASK_ALLOW

    private fun show(st: FakeStation) { compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }; compose.waitForIdle(); nav.open(Screen.Task(id)); compose.waitForIdle() }

    // P2: choosing another project in "Move to another project" moves the task
    @Test fun p2_tappingAProjectInTheMoveSheetMovesTheTask() {
        val st = FakeStation()
        st.set { s -> s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(busy = false, stopped = false, status = "Done", permission = null, question = null) else it }) }
        show(st)
        val here = st.state.value.task(id)!!.project
        val other = st.state.value.pickable.first { it != here }
        nav.openSheet(Sheet.TaskOptions(id)); compose.waitForIdle()
        compose.onNodeWithText("Move to another project…").performClick(); compose.waitForIdle()
        compose.onNodeWithText(other).performClick(); compose.waitForIdle()
        assertEquals(other, st.state.value.task(id)!!.project)
    }

    private fun gone(text: String) = assertTrue("\"$text\" should not be on screen", compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
    private fun station(mut: (Task) -> Task, sessions: List<PcSession>? = null) = FakeStation().also { st ->
        st.set { s -> s.copy(pcSessions = sessions, tasks = s.tasks.map { if (it.id == id) mut(it) else it }) }
    }

    // P2: when the PC says no, the person is told why on the task page (not left in a list of notes elsewhere)
    @Test fun p2_aRefusedMoveSaysWhy() {
        val st = FakeStation()
        st.moveRefusal = "The history of this conversation wasn't found."
        st.set { s -> s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(busy = false, stopped = false, status = "Done", permission = null, question = null) else it }) }
        show(st)
        val here = st.state.value.task(id)!!.project
        val other = st.state.value.pickable.first { it != here }
        nav.openSheet(Sheet.TaskOptions(id)); compose.waitForIdle()
        compose.onNodeWithText("Move to another project…").performClick(); compose.waitForIdle()
        compose.onNodeWithText(other).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(); compose.waitForIdle()
        compose.onNodeWithText("The history of this conversation wasn't found.").assertIsDisplayed()
        assertEquals(here, st.state.value.task(id)!!.project)
    }

    // P1 / P8: a stopped session carried on here loses the old "Opened from…" and "Running in another app…" lines, and has one way to carry on
    @Test fun p1_carryOnHereDropsTheOldLines() {
        val notes = { t: Task -> t.copy(liveElsewhere = true, stopped = true, busy = false, status = "Stopped", permission = null, question = null, messages = t.messages + TaskMessage("n1", true, "Opened from an earlier session. Send a message to carry on.", note = true) + TaskMessage("n2", true, "Running in another app; you can follow it here.", note = true)) }
        val past = PcSession("s-1", "Plan", "Garden planner", false, false, id, PcSessionState.Ended, 1_800_000_000L)
        show(station(notes, listOf(past)))
        gone("Opened from an earlier session. Send a message to carry on.")
        gone("Running in another app; you can follow it here.")
        // One way to carry on: the blue button, not also "Stopped · Continue".
        gone("Stopped ·")
        compose.onNodeWithText(FOLLOW_CARRY_ON).performClick(); compose.waitForIdle()
        gone("Opened from an earlier session. Send a message to carry on.")
        gone("Running in another app; you can follow it here.")
    }

    // P7: the old task says it was carried on, with a way to open the new one
    @Test fun p7_theOldTaskOffersTheNewOne() {
        val st = FakeStation()
        val old = st.state.value.task(id)!!
        val fresh = old.copy(id = "fresh-1", title = old.title.trim() + " (continued)", messages = listOf(TaskMessage("f1", false, "Carry on with this task from the summary.")))
        st.set { s -> s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(busy = false, stopped = false, status = "Done", permission = null, question = null, messages = it.messages + TaskMessage("c1", true, CARRIED_ON_NOTE, note = true)) else it } + fresh) }
        show(st)
        compose.onNodeWithText("Open the new task").performClick(); compose.waitForIdle()
        assertEquals(Screen.Task("fresh-1"), nav.current)
    }

    // P4: a task last seen working while the PC is away has no red cross
    @Test fun p4_noRedCrossOnATaskWhosePcIsAway() {
        show(station({ it.copy(permission = null, question = null, busy = false, stopped = false, status = "Was working (PC away)", steps = listOf("✓ Read a file", "✕ Run a test"), messages = listOf(TaskMessage("1", false, "go"))) }))
        gone("✕")
    }
}
