package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
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
 * One reply box everywhere (owner, 2026-11-25, "this is the standard"): the words on top, and one
 * row under them with + on the left, the "Model · Effort" chip in the centre and a small Send on
 * the right; no mic in the box. On the project page the chip opens the same model and effort sheet
 * as a task's, and the task starts with what was picked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P12OneBoxTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val project = "Budget-App"

    private fun show(st: FakeStation, screen: Screen?) {
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle()
        if (screen != null) { nav.open(screen); compose.waitForIdle() }
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
    }

    private fun dp(px: Float) = px / compose.density.density
    private fun box() = hasTestTag(REPLY_ROW_TAG)
    // The merged tree: a screen covered by the one on top is cleared from it.
    private fun inRow(m: SemanticsMatcher) = compose.onNode(m and androidx.compose.ui.test.hasAnyAncestor(box()))

    /** + left of the chip, the chip left of Send, Send drawn 28 dp and touched at 48 dp, and no mic. */
    private fun oneRow() {
        val plus = inRow(hasContentDescription("Attach")).fetchSemanticsNode().boundsInRoot
        val chip = inRow(hasContentDescription("Model and effort for this task", substring = true)).fetchSemanticsNode().boundsInRoot
        val send = inRow(hasContentDescription("Send")).fetchSemanticsNode().boundsInRoot
        val dot = compose.onAllNodes(hasTestTag(SEND_DOT_TAG), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.first { send.contains(it.center) }
        assertTrue("+ is left of the chip: $plus / $chip", plus.right <= chip.left)
        assertTrue("the chip is left of Send: $chip / $send", chip.right <= send.left)
        assertTrue("one row", plus.center.y in chip.top..chip.bottom && send.center.y in chip.top..chip.bottom)
        assertEquals("Send is drawn 28 dp", 28f, dp(dot.width), 0.6f)
        assertEquals("Send is touched at 48 dp", 48f, dp(send.width), 0.6f)
        val composer = compose.onNodeWithTag(COMPOSER_TAG).fetchSemanticsNode().boundsInRoot
        val mics = compose.onAllNodes(hasContentDescription("Talk", substring = true) or hasContentDescription("Stop listening"), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { it.boundsInRoot.overlaps(composer) }
        assertTrue("no mic in the box: ${mics.map { it.config }}", mics.isEmpty())
    }

    @Test fun theProjectPageHasTheOneRow() {
        show(FakeStation(), Screen.Project(project))
        oneRow()
    }

    @Test fun theChatScreenHasTheOneRow() {
        // The + is there once the box will start a task (a project chosen in "To:").
        nav.composeTo = io.github.odnwks.mimir.nav.ComposeTo(project = project)
        show(FakeStation(), null)
        oneRow()
    }

    @Test fun theTaskScreenHasTheOneRow() {
        val st = FakeStation().also { f -> f.set { s -> s.copy(tasks = s.tasks.map { if (it.id == ExampleData.TASK_ALLOW) it.copy(permission = null, question = null, busy = false, status = "Done") else it }) } }
        show(st, Screen.Task(ExampleData.TASK_ALLOW))
        oneRow()
    }

    @Test fun pickingAModelAndEffortOnTheProjectPageStartsTheTaskWithBoth() {
        val st = FakeStation()
        show(st, Screen.Project(project))
        compose.onNodeWithContentDescription("Model and effort for this task: Auto · Default Effort. Change").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Model for this task").assertIsDisplayed()
        compose.onNodeWithText("Opus 5.5").performClick(); compose.waitForIdle()
        // A pick keeps the sheet open, as on a task.
        compose.onNodeWithText("Model for this task").assertIsDisplayed()
        compose.onNodeWithText("High").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Default Effort").assertIsDisplayed()
        compose.runOnIdle { nav.closeSheet() }; compose.waitForIdle()
        // The chip names the choice.
        compose.onNodeWithContentDescription("Model and effort for this task: Opus 5.5 · High. Change").assertIsDisplayed()
        compose.onNodeWithContentDescription("New task in $project").performTextInput("Tidy the budget screen")
        compose.onNodeWithContentDescription("Send").performClick(); compose.waitForIdle()
        assertEquals("opus" to "high", st.startedOptions.last())
        // The task it opens shows the same choice on its chip.
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        compose.onNodeWithContentDescription("Model and effort for this task: Opus 5.5 · High. Change").assertIsDisplayed()
    }

    @Test fun onAnOlderPcTheChipNamesTheModelOnlyAndNoEffortGoes() {
        val st = FakeStation().also { it.optionsOk = false }
        show(st, Screen.Project(project))
        compose.onNodeWithText(UPDATE_PC_FOR_OPTIONS).assertIsDisplayed()
        compose.onNodeWithContentDescription("Model and effort for this task: Auto. Change").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Opus 5.5").performClick(); compose.waitForIdle()
        assertTrue("no effort choice on an old PC", compose.onAllNodesWithText("Default Effort").fetchSemanticsNodes().isEmpty())
        compose.runOnIdle { nav.closeSheet() }; compose.waitForIdle()
        compose.onNodeWithContentDescription("New task in $project").performTextInput("Tidy the budget screen")
        compose.onNodeWithContentDescription("Send").performClick(); compose.waitForIdle()
        assertEquals("opus" to null, st.startedOptions.last())
    }

    @Test fun voiceTypingIsInTheProjectPagesThreeDots() {
        show(FakeStation(), Screen.Project(project))
        compose.onNodeWithContentDescription("Options for $project").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Talk (voice typing)").assertIsDisplayed()
    }

    @Test fun chatsThreeDotsHaveWhereItGoesAndVoiceTyping() {
        show(FakeStation(), null)
        compose.onNodeWithContentDescription(CHAT_MENU).performClick(); compose.waitForIdle()
        compose.onNodeWithText("To: Auto").assertIsDisplayed()
        compose.onNodeWithText("Talk (voice typing)").assertIsDisplayed()
    }
}
