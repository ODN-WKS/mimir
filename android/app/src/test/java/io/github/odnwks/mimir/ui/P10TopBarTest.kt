package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
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

/** P10: the compact task top (one row, Stop and Find inside the menu). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P10TopBarTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val id = ExampleData.TASK_ALLOW
    private val ctx get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val longTitle = "Continue with the handoff and publish now" // 40 characters

    private fun station(mut: (Task) -> Task = { it }) = FakeStation().also { st -> st.set { s -> s.copy(tasks = s.tasks.map { if (it.id == id) mut(it) else it }) } }

    private fun show(st: FakeStation, scale: Float = 1f, mode: StopMode = StopMode.Confirm) {
        StopPrefs.set(ctx, mode)
        TaskMenuAsk.finding = null
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        }
        compose.waitForIdle(); nav.open(Screen.Task(id)); compose.waitForIdle()
    }

    private fun gone(text: String) = assertTrue("\"$text\" should not be on screen", compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
    private fun working(t: Task) = t.copy(permission = null, question = null, stopped = false, busy = true, status = "Working")
    private fun done(t: Task) = t.copy(permission = null, question = null, busy = false, stopped = false, status = "Done", messages = listOf(TaskMessage("1", false, "go"), TaskMessage("2", true, "All saved.")))

    private fun lines(scale: Float) {
        show(station { working(it).copy(title = longTitle) }, scale)
        val node = compose.onNode(hasText("Continue", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        val out = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        assertEquals("the title is one line at text scale $scale", 1, out[0].lineCount)
        val shownText = out[0].layoutInput.text.text
        assertTrue("cut with an ellipsis, not wrapped: $shownText", shownText.endsWith("…") || shownText == longTitle)
        // Cut after a whole word.
        if (shownText != longTitle) assertTrue("whole words only: $shownText", longTitle.startsWith(shownText.removeSuffix("…")) && longTitle[shownText.length - 1] == ' ')
    }

    @Test fun theTitleIsOneLineAtScale1() = lines(1f)
    @Test fun theTitleIsOneLineAtScale1_3() = lines(1.3f)
    @Test fun theTitleIsOneLineAtScale2() = lines(2f)

    @Test fun cutAtWordKeepsWholeWords() {
        val fits = { t: String -> t.length <= 20 }
        assertEquals("Continue with the…", cutAtWord("Continue with the handoff and publish now", fits))
        assertEquals("short", cutAtWord("short", fits))
    }

    @Test fun noStopOrSearchButtonInTheTopRow() {
        show(station { working(it) })
        assertEquals(0, compose.onAllNodesWithContentDescription("Stop").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("Find in this task").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Task options").assertIsDisplayed()
        // The ⋯ button says Stop is in there while work runs.
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "with Stop")).assertExists()
    }

    @Test fun theMenusFirstRowIsStopWhileWorkingAndAbsentWhenDone() {
        show(station { working(it) })
        compose.onNodeWithContentDescription("Task options").performClick(); compose.waitForIdle()
        val stop = compose.onNodeWithText(STOP_MENU_ROW).fetchSemanticsNode().boundsInRoot.top
        val find = compose.onNodeWithText("Find in this task").fetchSemanticsNode().boundsInRoot.top
        val rename = compose.onNodeWithText("Rename…").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Stop is above Find, Find above Rename", stop < find && find < rename)
        nav.closeSheet(); compose.waitForIdle()
    }

    @Test fun aDoneTasksMenuHasNoStopButHasFind() {
        show(station { done(it) })
        compose.onNodeWithContentDescription("Task options").performClick(); compose.waitForIdle()
        gone(STOP_MENU_ROW)
        compose.onNodeWithText("Find in this task").assertIsDisplayed()
    }

    @Test fun findInTheMenuOpensTheFindBox() {
        show(station { done(it) })
        compose.onNodeWithContentDescription("Task options").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Find in this task").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Find in this task").performTextInput("zzzz-nothing"); compose.waitForIdle()
        compose.onNodeWithText("No message has \"zzzz-nothing\"").assertIsDisplayed()
    }

    @Test fun stopFromTheMenuStopsInConfirmModeWithoutAThirdTap() {
        val st = station { working(it) }
        show(st, mode = StopMode.Confirm)
        compose.onNodeWithContentDescription("Task options").performClick(); compose.waitForIdle()
        compose.onNodeWithText(STOP_MENU_ROW).performClick(); compose.waitForIdle()
        assertEquals("Stopped", st.state.value.task(id)!!.stateWords)
        assertEquals(null, nav.sheet)
    }

    @Test fun stopFromTheMenuIsOneTapWhenOff() {
        val st = station { working(it) }
        show(st, mode = StopMode.Off)
        compose.onNodeWithContentDescription("Task options").performClick(); compose.waitForIdle()
        compose.onNodeWithText(STOP_MENU_ROW).performClick(); compose.waitForIdle()
        assertEquals("Stopped", st.state.value.task(id)!!.stateWords)
    }

    @Test fun stopFromTheMenuNeedsTheHoldInHoldMode() {
        val st = station { working(it) }
        show(st, mode = StopMode.Hold)
        compose.onNodeWithContentDescription("Task options").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Stop").performClick(); compose.waitForIdle()
        assertTrue("a quick tap does nothing", st.state.value.task(id)!!.stateWords != "Stopped")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Stop").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(STOP_HOLD_MS + 300L)
        // (the menu closes when the hold ends the work, so there is nothing left to let go of)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("Stopped", st.state.value.task(id)!!.stateWords)
    }

    @Test fun theTopAreaIsOneRowPlusOneLine() {
        show(station { working(it) })
        val d = compose.density.density
        val bottom = compose.onNodeWithContentDescription("Task options").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("the top row ends within 60 dp: ${bottom / d}", bottom / d <= 60f)
    }
}
