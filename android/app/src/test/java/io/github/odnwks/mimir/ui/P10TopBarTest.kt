package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The compact task top (owner, 2026-11-25): one short row with the state, a small round Stop while
 * work runs and the ⋯ menu; no task name; Find and voice typing in the menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P10TopBarTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val id = ExampleData.TASK_ALLOW
    private val ctx get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val longTitle = "Continue with the handoff and publish now"

    private fun station(mut: (Task) -> Task = { it }) = FakeStation().also { st -> st.set { s -> s.copy(tasks = s.tasks.map { if (it.id == id) mut(it) else it }) } }

    private fun show(st: FakeStation, mode: StopMode = StopMode.Confirm) {
        StopPrefs.set(ctx, mode)
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle(); nav.open(Screen.Task(id)); compose.waitForIdle()
    }

    private fun gone(text: String) = assertTrue("\"$text\" should not be on screen", compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
    private fun dp(px: Float) = px / compose.density.density
    private fun working(t: Task) = t.copy(permission = null, question = null, stopped = false, busy = true, status = "Working")
    private fun done(t: Task) = t.copy(permission = null, question = null, busy = false, stopped = false, status = "Done", messages = listOf(TaskMessage("1", false, "go"), TaskMessage("2", true, "All saved.")))

    @Test fun theTaskNameIsNotInTheTopRow() {
        show(station { working(it).copy(title = longTitle) })
        gone(longTitle)
        // It is one tap away on the state.
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("shows the details") { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == "Show the task name and project" })[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText(longTitle, substring = true).assertIsDisplayed()
    }

    @Test fun aSmallRoundStopSitsInTheTopRowWhileWorking() {
        show(station { working(it) })
        assertEquals(1, compose.onAllNodesWithContentDescription("Stop").fetchSemanticsNodes().size)
        val stop = compose.onNodeWithContentDescription("Stop").fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithContentDescription("Task options").fetchSemanticsNode().boundsInRoot
        assertTrue("Stop is drawn small: ${dp(stop.width)} dp", dp(stop.width) <= 30f)
        assertTrue("Stop is beside the ⋯ button", stop.right <= more.left && stop.bottom > more.top && stop.top < more.bottom)
        // Find is no button of its own at the top.
        assertEquals(0, compose.onAllNodesWithContentDescription("Find in this task").fetchSemanticsNodes().size)
    }

    @Test fun aDoneTaskHasNoStopAtTheTop() {
        show(station { done(it) })
        assertEquals(0, compose.onAllNodesWithContentDescription("Stop").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Task options").assertIsDisplayed()
    }

    @Test fun findAndTalkAreInTheMenu() {
        show(station { working(it) })
        compose.onNodeWithContentDescription("Task options").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Find in this task").assertIsDisplayed()
        compose.onNodeWithText("Talk (voice typing)").assertIsDisplayed()
    }

    @Test fun findInTheMenuOpensTheFindBox() {
        show(station { done(it) })
        compose.onNodeWithContentDescription("Task options").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Find in this task").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Find in this task").performTextInput("zzzz-nothing"); compose.waitForIdle()
        compose.onNodeWithText("No message has \"zzzz-nothing\"").assertIsDisplayed()
    }

    @Test fun theRoundStopAsksForASecondTapInConfirmMode() {
        val st = station { working(it) }
        show(st, mode = StopMode.Confirm)
        compose.onNodeWithContentDescription("Stop").performClick(); compose.waitForIdle()
        assertTrue("one tap only arms it", st.state.value.task(id)!!.stateWords != "Stopped")
        compose.onNodeWithContentDescription("Stop").performClick(); compose.waitForIdle()
        assertEquals("Stopped", st.state.value.task(id)!!.stateWords)
    }

    @Test fun theRoundStopIsOneTapWhenOff() {
        val st = station { working(it) }
        show(st, mode = StopMode.Off)
        compose.onNodeWithContentDescription("Stop").performClick(); compose.waitForIdle()
        assertEquals("Stopped", st.state.value.task(id)!!.stateWords)
    }

    @Test fun theRoundStopNeedsTheHoldInHoldMode() {
        val st = station { working(it) }
        show(st, mode = StopMode.Hold)
        compose.onNodeWithContentDescription("Stop").performClick(); compose.waitForIdle()
        assertTrue("a quick tap does nothing", st.state.value.task(id)!!.stateWords != "Stopped")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Stop").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(STOP_HOLD_MS + 300L)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("Stopped", st.state.value.task(id)!!.stateWords)
    }

    @Test fun theTopAreaIsOneShortRow() {
        show(station { working(it) })
        val bottom = compose.onNodeWithContentDescription("Task options").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("the top row ends within 60 dp: ${dp(bottom)}", dp(bottom) <= 60f)
    }
}
