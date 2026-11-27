package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.link.Ev
import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P48 (owner, 2026-11-27): the project's name and the context window at the very top of the task's menu. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P48ContextBarTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val id = ExampleData.TASK_ALLOW

    private fun openMenu(used: Long, window: Long) {
        val st = FakeStation().also { st ->
            st.set { s -> s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(project = "Garden planner", contextUsed = used, contextWindow = window) else it }) }
        }
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle(); nav.open(Screen.Task(id)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Task options").performClick(); compose.waitForIdle()
    }

    private fun header(extra: String) = (Wire.event(
        "{\"seq\":1,\"event\":{\"Task\":{\"header\":{\"id\":\"t\",\"title\":\"T\",\"project\":\"p\"$extra}}}}",
    ).second as Ev.TaskHeader).task

    @Test fun theContextNumbersAreReadFromThePc() {
        val t = header(",\"context_used\":84000,\"context_window\":200000")
        assertEquals(84_000L, t.contextUsed)
        assertEquals(200_000L, t.contextWindow)
    }

    @Test fun anOlderPcSendsNeitherAndBothAreZero() {
        val t = header("")
        assertEquals(0L, t.contextUsed)
        assertEquals(0L, t.contextWindow)
    }

    @Test fun theNumberLineHasThousandsSeparatorsAndAPercent() {
        assertEquals("84,000 of 200,000 · 42%", contextLine(84_000, 200_000))
        assertEquals("950 of 32,768 · 2%", contextLine(950, 32_768))
        assertNull(contextLine(5_000, 0))
    }

    @Test fun theBarWarnsOnlyPastTheFreshStartLimit() {
        assertTrue(!contextWarns(99_999))
        assertTrue(contextWarns(100_000))
    }

    @Test fun projectThenNumberThenBarThenTheOldRows() {
        openMenu(84_000, 200_000)
        val name = compose.onNodeWithTag(CONTEXT_PROJECT_TAG, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val number = compose.onNodeWithText("84,000 of 200,000 · 42%", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag(CONTEXT_BAR_TAG, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Task options", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("Find in this task").fetchSemanticsNode().boundsInRoot
        assertTrue("project, number, bar, title, rows", name.bottom <= number.top && number.bottom <= bar.top && bar.bottom <= title.top && title.bottom <= first.top)
        assertEquals("same left edge", name.left, number.left, 0.5f)
        assertEquals("same left edge", name.left, bar.left, 0.5f)
        assertTrue("the bar is the menu's width", bar.width >= 340 * compose.density.density)
    }

    @Test fun anUnknownWindowShowsNoNumberAndNoBar() {
        openMenu(5_000, 0)
        compose.onNodeWithTag(CONTEXT_PROJECT_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag(CONTEXT_BAR_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("5,000", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }
}
