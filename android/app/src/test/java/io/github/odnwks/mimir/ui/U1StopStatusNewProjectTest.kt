package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** U1: Stop is always there while working, "Done" is honest, and the side menu can make a project. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class U1StopStatusNewProjectTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val station = FakeStation()
    private val nav = Navigator()

    private fun finished(last: String) = ExampleData.awake().tasks.first().copy(
        question = null, permission = null, stopped = false, busy = false, status = "Done",
        messages = listOf(TaskMessage("1", false, "Please look at the tests"), TaskMessage("2", true, last)),
    )

    @Test fun stopStaysOnScreenWhileTypingAndStopsTheTask() {
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } }
        val id = ExampleData.TASK_CELL
        nav.open(Screen.Task(id)); compose.waitForIdle()
        // P10: Stop is the first row of the ⋯ menu (red dot on the button while work runs).
        compose.onNodeWithContentDescription("Task options").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reply to this task").performTextInput("a few words")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(BUTTON_SETTLE_MS); compose.waitForIdle()
        compose.onNodeWithContentDescription("Task options").performClick(); compose.waitForIdle()
        compose.onNodeWithText(STOP_MENU_ROW).performClick()
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        assertEquals("Stopped", station.state.value.task(id)!!.stateWords)
    }

    @Test fun aTaskThatEndedWithAQuestionIsDoneLikeOnThePc() {
        // The PC says Done; its question stays visible in the text (G1).
        assertEquals("Done", finished("I fixed the first test. Want me to go on with the other two?").stateWords)
        assertEquals("Done", finished("Done with step one.\n\nNext steps:\n1. Fix the rest\n2. Run everything").stateWords)
        assertEquals("Done", finished("Should I open the change for review? **Tell me.**").stateWords)
        assertEquals("Done", finished("Shall I carry on?").stateLine)
    }

    @Test fun aFinishedTaskIsStillDone() {
        assertEquals("Done", finished("All three tests pass now. The change is saved.").stateWords)
        assertEquals("Done", finished("What was wrong: the box was empty. It is fixed.").stateWords)
        // Only a finished turn counts: a stopped task stays stopped.
        assertEquals("Stopped", finished("Want me to go on?").copy(status = "Stopped").stateWords)
    }

    @Test fun waitingForYouIsAmberLikeNeedsYou() {
        // One name for the state: the old second name no longer comes from a task.
        assertEquals("Needs you", finished("Shall I go on?").copy(status = "Needs you").stateWords)
        assertEquals("Done", finished("Shall I go on?").stateWords)
    }

    @Test fun theSideMenuHasNewProjectAsTheLastRowUnderProjects() {
        val st = FakeStation(ExampleData.awake())
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle()
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences(MenuFold.PREFS, 0).edit().clear().commit()
        compose.openMenu()
        // Projects is folded (the default) and the row is still there.
        compose.onNodeWithText("New project…").assertIsDisplayed()
        compose.onNodeWithText("New project…").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Make the project").assertIsDisplayed()
        compose.onNodeWithContentDescription("New project name", substring = true).performTextInput("Garden planner")
        compose.waitForIdle()
        compose.onNodeWithText("Make the project").performClick()
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
        assertEquals(Screen.Project("Garden planner"), nav.current)
    }

    @Test fun stopIsThereDuringAPermissionPromptAndSaysTheTaskIsWorking() {
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } }
        nav.open(Screen.Task(ExampleData.TASK_ALLOW)); compose.waitForIdle()
        // P10: the ⋯ button says Stop is in the menu while the task works (also during a permission prompt).
        compose.onNodeWithContentDescription("Task options").assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "with Stop")).assertExists()
    }

    @Test fun whateverTheClosingWordsAFinishedTaskIsDoneLikeOnThePc() {
        assertEquals("Done", finished("Options parsing was fixed. All tests pass.").stateWords)
        assertEquals("Done", finished("Next step: none. Everything is saved.").stateWords)
        assertEquals("Done", finished("Fixed the bug. Let me know if you need anything else.").stateWords)
        assertEquals("Done", finished("All saved.\n\nAnything else?").stateWords)
        assertEquals("Done", finished("Fixed it.\n\n```\nwhy?\n```").stateWords)
        assertEquals("Done", finished("Want me to go on? Let me know.").stateWords)
        assertEquals("Done", finished("Soll ich weitermachen\u2753\u3000Was meinst du\uff1f").stateWords)
        assertEquals("Done", finished("Continue now?!").stateWords)
        assertEquals("Done", finished("\u0647\u0644 \u0623\u0643\u0645\u0644\u061f").stateWords)
        assertEquals("Done", finished("Done with step one.\n\nNext steps:\n1. Fix the rest").stateWords)
    }

    @Test fun aTaskThatEndedWithAQuestionIsFoundUnderTheNeedsYouFilter() {
        assertEquals(true, taskMatchesFilter("Needs you", "Needs you"))
        assertEquals(false, taskMatchesFilter("Needs you", "Done"))
        assertEquals(true, taskMatchesFilter("Starting", "Working"))
        assertEquals(true, taskMatchesFilter("Needs you", "All"))
    }
}
