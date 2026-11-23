package io.github.odnwks.mimir.data

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.odnwks.mimir.link.LEntry
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.AppRoot
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a followed session wrote on its own (a helper finished) reads as one small line, never raw tags. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class SystemLinesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aHelperFinishedLineIsASmallNote() {
        val m = taskMessageOf("t", 1, LEntry.Note("A helper finished: Seed packet sorting"), false)!!
        assertTrue(m.note)
    }

    @Test fun theTaskScreenShowsTheLineAndNoRawTags() {
        val base = ExampleData.awake()
        val task = base.tasks.first().let {
            it.copy(messages = it.messages + TaskMessage("n1", false, "3 helpers finished", note = true))
        }
        val st = FakeStation(base.copy(tasks = base.tasks.map { if (it.id == task.id) task else it }))
        val nav = Navigator()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        nav.open(Screen.Task(task.id)); compose.waitForIdle()
        compose.onNodeWithText("3 helpers finished").assertIsDisplayed()
        compose.onNodeWithText("task-notification", substring = true).assertDoesNotExist()
    }
}
