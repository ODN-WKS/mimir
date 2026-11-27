package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P42 (QA16 Q22): Fresh start sits above Archive and Delete in a task's menu. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class P42FreshRowTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val id = ExampleData.TASK_ALLOW

    @Test fun freshStartIsAboveArchiveAndDelete() {
        val st = FakeStation().also { s ->
            s.set { x -> x.copy(pcSessions = null, tasks = x.tasks.map { if (it.id == id) it.copy(busy = false, stopped = false, status = "Done", permission = null, question = null) else it }) }
        }
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle(); nav.open(Screen.Task(id)); compose.waitForIdle()
        nav.openSheet(Sheet.TaskOptions(id)); compose.waitForIdle()
        val top = { words: String -> compose.onNodeWithText(words).fetchSemanticsNode().positionInRoot.y }
        val fresh = top("Fresh start")
        assertTrue("Fresh start above Archive", fresh < top("Archive"))
        assertTrue("Fresh start above Delete", fresh < top("Delete…"))
    }
}
