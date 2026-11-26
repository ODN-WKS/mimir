package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** P13: a task with two replies in a row at 390x844, text size 1 and 1.3. Pictures only when P13_SHOT names a folder. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P13Shots {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val out = System.getenv("P13_SHOT")

    private fun task(scale: String) {
        val t = Task(
            id = "t1", title = "Fix the title", project = "Recipe box", model = "Opus 5.5", age = "", steps = emptyList(),
            messages = listOf(
                TaskMessage("t1-0", false, "Make the title bigger"),
                TaskMessage("t1-1", true, "Done: the title is **bigger** now.", "Play the reply"),
                TaskMessage("t1-2", true, "I also checked it on a small screen: it fits on one line.", "Play the reply"),
            ),
            question = null, permission = null, change = null, status = "Done", busy = false, running = false,
        )
        val st = FakeStation(ExampleData.awake().let { it.copy(tasks = listOf(t)) })
        val nav = Navigator()
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } } }
        compose.waitForIdle()
        nav.open(Screen.Task("t1"))
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        // The two replies (the screen under the task may hold more).
        assertTrue(compose.onAllNodesWithTag(REPLY_BUBBLE_TAG, useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
        out?.let { compose.onRoot().captureRoboImage("$it/p13_task_two_replies_$scale.png") }
    }

    @Test fun aTaskWithTwoReplies() = task("1.0")
    @Config(fontScale = 1.3f) @Test fun aTaskWithTwoRepliesAt13() = task("1.3")
}
