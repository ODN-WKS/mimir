package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.Chip
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.PcSession
import io.github.odnwks.mimir.data.PcSessionState
import io.github.odnwks.mimir.data.ProjectTask
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.data.shortTitle
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** QA11 hunt, phone task screens (F3): each test names the finding it came from. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class F3HuntTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val now = 1_800_000_000L
    private val id = ExampleData.TASK_ALLOW

    private fun elsewhere(app: Boolean) = PcSession("s-1", "Plan", "Garden planner", false, app, id, PcSessionState.Working, now)
    private fun station(mut: (Task) -> Task = { it }, sessions: List<PcSession>? = null) = FakeStation().also { st ->
        st.set { s -> s.copy(pcSessions = sessions, tasks = s.tasks.map { if (it.id == id) mut(it) else it }) }
    }
    private fun show(st: FakeStation) { compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }; compose.waitForIdle(); nav.open(Screen.Task(id)); compose.waitForIdle() }
    private fun gone(text: String) = assertTrue("\"$text\" should not be on screen", compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())

    // T1 (regression: "Carry on here" grey with no reason while a session still runs elsewhere)
    @Test fun t1_aSessionStillRunningElsewhereHidesCarryOnHereAndSaysWhere() {
        show(station({ it.copy(liveElsewhere = true, messages = it.messages + TaskMessage("n1", true, "Opened from an earlier session. Send a message to carry on.", note = true) + TaskMessage("n2", true, "Running in another app; you can follow it here.", note = true)) }, listOf(elsewhere(false))))
        compose.onNodeWithText("Still running in a terminal. Stop it to carry on here.").assertIsDisplayed()
        gone(FOLLOW_CARRY_ON)
        gone("Opened from an earlier session. Send a message to carry on.")
        gone("Running in another app; you can follow it here.")
    }

    // T1 with an older PC that doesn't say where
    @Test fun t1_anOlderPcSaysYourPc() {
        show(station({ it.copy(liveElsewhere = true) }, emptyList()))
        compose.onNodeWithText("Still running on your PC. Stop it to carry on here.").assertIsDisplayed()
    }

    // T1 with the PC's new field naming the place
    @Test fun t1_thePcsOwnFieldNamesTheApp() {
        show(station({ it.copy(liveElsewhere = true, elsewhereIn = "app") }, emptyList()))
        compose.onNodeWithText("Still running in the Claude app. Stop it to carry on here.").assertIsDisplayed()
    }

    // T3: a followed session's menu shows only what applies
    @Test fun t3_aFollowedSessionsMenuHasNoModelModeOrStop() {
        val st = station({ it.copy(liveElsewhere = true) }, listOf(elsewhere(true)))
        show(st)
        nav.openSheet(Sheet.TaskOptions(id)); compose.waitForIdle()
        compose.onNodeWithText("Rename…").assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        for (w in listOf("Change the model…", "Ask me first before changes", "Plan first, change nothing yet", "Stop this task…", "Move to another project…", "Always allowed in this task…")) gone(w)
    }

    // T6: no Stop on a finished task, no second Continue
    @Test fun t6_aDoneTaskMenuHasNoStopAndNoContinue() {
        val st = station({ it.copy(permission = null, question = null, busy = false, stopped = false, status = "Done", messages = listOf(TaskMessage("1", false, "go"), TaskMessage("2", true, "All saved."))) })
        show(st)
        nav.openSheet(Sheet.TaskOptions(id)); compose.waitForIdle()
        compose.onNodeWithText("Rename…").assertIsDisplayed()
        gone("Stop this task…"); gone("Continue")
    }

    // T4 / T9 names
    @Test fun t4_aTaskWhoseProgramDiedIsStoppedUnexpectedlyWithContinue() {
        val st = station({ it.copy(permission = null, question = null, busy = false, stopped = false, status = "Stopped unexpectedly") })
        assertEquals("Stopped unexpectedly", st.state.value.task(id)!!.stateWords)
        assertEquals(Chip.StoppedUnexpectedly, Chip.ofWords("Stopped unexpectedly"))
        assertEquals(Chip.StoppedUnexpectedly, Chip.of(false, "Failed"))
        assertTrue(!st.state.value.task(id)!!.showsStop)
        show(st)
        compose.onNodeWithText("Continue").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Stopped unexpectedly").fetchSemanticsNodes().isNotEmpty())
        assertTrue(taskMatchesFilter("Stopped unexpectedly", "Stopped"))
    }

    @Test fun t9_oneNameForATaskThatNeedsYou() {
        val t = ExampleData.awake().tasks.first().copy(question = null, permission = null, busy = false, stopped = false, status = "Done",
            messages = listOf(TaskMessage("1", true, "Want me to go on?")))
        // G1: a finished task whose closing words are a question is Done, as on the PC.
        assertEquals("Done", t.stateWords)
        assertTrue(!t.showsStop)
        val asking = t.copy(status = "Needs you")
        assertEquals("Needs you", asking.stateWords)
        assertEquals("Needs you", asking.stateLine)
        assertEquals(Chip.NeedsYou, Chip.ofWords("Waiting for you"))
    }

    // T7: Stop while the PC is away is a line on the task, not Done
    @Test fun t7_aStopThatCouldNotGoOutSaysItWaits() {
        show(station({ it.copy(stopPending = true) }))
        compose.onNodeWithText(STOP_WAITING).assertIsDisplayed()
        assertEquals("Stop waiting: sends when your PC is back", STOP_WAITING)
        assertTrue(station({ it.copy(stopPending = true) }).state.value.task(id)!!.stateWords != "Done")
    }

    // T10: the state is said once in a project's task rows
    @Test fun t10_aProjectRowSaysItsStateOnce() {
        val st = FakeStation()
        st.set { s -> s.copy(projects = s.projects.map { p -> if (p.tasks.isEmpty()) p else p.copy(tasks = listOf(ProjectTask("x", "Write it up", "Done", Chip.Done))) }) }
        val project = st.state.value.projects.first { it.tasks.isNotEmpty() }
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        nav.open(Screen.Project(project.name)); compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Done").fetchSemanticsNodes().size)
    }

    // T11: a long title is cut at a whole word
    @Test fun t11_aTitleIsCutAtAWholeWord() {
        assertEquals("Write one very long sentence about…", shortTitle("Write one very long sentence about everything a gardener should know"))
        assertEquals("Short one", shortTitle("Short one"))
        assertEquals("X".repeat(40) + "…", shortTitle("X".repeat(60)))
    }

    // T13: the model list says which ones go online and ticks the current one
    @Test fun t13_theModelListMarksOnlineAndTicksTheCurrent() {
        val st = station({ it.copy(model = "Opus 5.5") })
        show(st)
        nav.openSheet(Sheet.TaskModel(id)); compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Online").fetchSemanticsNodes().isNotEmpty())
        // One tick, on the model the task uses.
        assertEquals(1, compose.onAllNodesWithContentDescription("In use", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    // T15: the move sheet is titled for what it does and never lists where the task already is
    @Test fun t15_theMoveSheetListsOnlyOtherProjects() {
        val st = station()
        show(st)
        val here = st.state.value.task(id)!!.project
        nav.openSheet(Sheet.ProjectPicker(here)); compose.waitForIdle()
        compose.onNodeWithText("Move to another project").assertIsDisplayed()
        val others = st.state.value.pickable.filter { it != here }
        if (others.isEmpty()) compose.onNodeWithText("No other project yet. Make one below.").assertIsDisplayed()
        else compose.onNodeWithText(others.first()).assertIsDisplayed()
        gone(here)
    }

    // T17: a new project's message never shows a file path
    @Test fun t17_aNewProjectMessageHasNoPath() {
        var said = ""
        FakeStation().newProject("Garden planner") { _, m -> said = m }
        assertTrue(said, !said.contains("/") && !said.contains("~"))
    }

    // T18: the New project row and its form are not shown together
    @Test fun t18_theNewProjectRowGoesWhenItsFormOpens() {
        val st = station()
        show(st)
        nav.openSheet(Sheet.ProjectPicker("")); compose.waitForIdle()
        compose.onNodeWithText("New project…").performClick(); compose.waitForIdle()
        gone("New project…")
        compose.onNodeWithText("Make the project").assertIsDisplayed()
    }

    // T5: a red cross is for work that ended with something not done, not for progress while it runs
    @Test fun t5_noRedCrossWhileItWorks() {
        show(station({ it.copy(permission = null, question = null, busy = true, status = "Working", steps = listOf("✓ Read a file", "✕ Run a test")) }))
        gone("✕")
    }

    // T9: the question is said once on screen
    @Test fun t9_aQuestionIsNotRepeatedInItsCard() {
        val q = "Which herb do you prefer: basil or mint?"
        show(station({ it.copy(permission = null, question = io.github.odnwks.mimir.data.Question("q1", q, "", "", emptyList()), messages = listOf(TaskMessage("1", true, q))) }))
        // 0.09 (item 22): the card is one slim line quoting the question, above the reply box: the answer and that line.
        assertEquals(2, compose.onAllNodesWithText(q, substring = true).fetchSemanticsNodes().size)
        gone("A question for you")
        gone("Answer in the box below.")
    }

    // T21: a paused task says "Paused" once, with Continue; the PC's own "Press Continue" note is not repeated
    @Test fun t21_aPausedTaskHasOneLineWithContinue() {
        show(station({ it.copy(permission = null, question = null, busy = false, stopped = false, status = "Paused", messages = listOf(TaskMessage("1", false, "go"), TaskMessage("2", true, "Paused. Press Continue to carry on.", note = true))) }))
        compose.onNodeWithText("Continue").assertIsDisplayed()
        gone("Paused. Press Continue to carry on.")
    }
}

/** More from the hunt (coordinator's list): R14 and the PC lines. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class F3MoreHuntTest {
    @org.junit.Test fun r14_aSmallSizeIsNeverInBytes() {
        assertEquals("under 1 KB", bytesWords(688))
        assertEquals("under 1 KB", bytesWords(1))
        assertTrue(!bytesWords(4096).contains("bytes"))
    }

    // T4 from the PC's own field: a Stopped task with failed = true reads Stopped unexpectedly
    @org.junit.Test fun t4_theFailedFieldMakesItStoppedUnexpectedly() {
        val h = io.github.odnwks.mimir.link.LinkTask("t", "T", "/p", "Stopped", false, null, 0L, emptyList(), failed = true)
        assertEquals("Stopped unexpectedly", io.github.odnwks.mimir.data.Mapping.taskWords(h, asks = false, connected = true))
        assertEquals("Stopped", io.github.odnwks.mimir.data.Mapping.taskWords(h.copy(failed = false), asks = false, connected = true))
        assertEquals("Stopped unexpectedly", io.github.odnwks.mimir.data.Mapping.truthWords("Stopped", failed = true))
        assertEquals("Stopped", io.github.odnwks.mimir.data.Mapping.truthWords("Stopped"))
    }
}
