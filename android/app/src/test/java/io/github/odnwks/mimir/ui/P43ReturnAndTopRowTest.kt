package io.github.odnwks.mimir.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** At S25 width and real text size, a task's top shows its state and mode tags whole. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class P43TaskTopRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nav = Navigator()
    private val id = ExampleData.TASK_ALLOW
    private val long = "Garden planner for the whole long summer"

    private fun check(state: String, mode: String, chip: String, change: (Task) -> Task) {
        val station = FakeStation().also { s ->
            s.set { x -> x.copy(tasks = x.tasks.map { if (it.id == id) change(it).copy(mode = mode, project = long) else it }) }
        }
        val words = listOf(state, chip)
        compose.setContent {
            MimirTheme(reduceMotion = true) {
                Box(Modifier.fillMaxSize()) {
                    AppRoot(station, nav)
                    // The same tags with all the room they want, to measure what each needs.
                    words.forEach { w -> Box(Modifier.align(Alignment.BottomStart).wrapContentWidth(unbounded = true)) { Tag(w, Palette.Text, Palette.Ground, Modifier.testTag("need-$w")) } }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { nav.open(Screen.Task(id)) }
        compose.waitForIdle()
        val d = compose.density.density
        words.forEach { w ->
            val needs = compose.onNodeWithTag("need-$w", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width - 18f * d
            val shown = compose.onAllNodesWithText(w, useUnmergedTree = true).fetchSemanticsNodes()
                .filter { it.boundsInRoot.top < 90f * d && !it.config.contains(SemanticsProperties.TestTag) }
                .maxOfOrNull { it.boundsInRoot.width } ?: 0f
            assertTrue("\"$w\" is cut off: ${shown / d} dp of ${needs / d} dp", shown + d >= needs)
        }
        val name = compose.onAllNodesWithText(long, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the project name still shows", name.any { it.boundsInRoot.top < 90f * d && it.boundsInRoot.width > 0f })
    }

    private val calm: (Task) -> Task = { it.copy(permission = null, question = null, stopped = false, busy = false) }
    private val working: (Task) -> Task = { calm(it).copy(busy = true, status = "Working") }

    private fun every(state: String, mode: String, chip: String) = check(
        state, mode, chip,
        when (state) {
            "Needs you" -> { t -> t }
            "Working" -> working
            else -> { t -> calm(t).copy(status = if (state == "Stopped unexpectedly") "Failed" else state, stopped = state == "Stopped") }
        },
    )

    @Test fun needsYouAutoEdit() = every("Needs you", "AcceptEdits", "Auto-edit")
    @Test fun needsYouAuto() = every("Needs you", "Auto", "Auto")
    @Test fun needsYouGoAhead() = every("Needs you", "NoAsk", "Go ahead")
    @Test fun workingAutoEdit() = every("Working", "AcceptEdits", "Auto-edit")
    @Test fun workingGoAhead() = every("Working", "NoAsk", "Go ahead")
    @Test fun startingGoAhead() = check("Starting", "NoAsk", "Go ahead") { calm(it).copy(status = "Starting", busy = true, messages = emptyList(), steps = emptyList()) }
    @Test fun doneGoAhead() = every("Done", "NoAsk", "Go ahead")
    @Test fun pausedGoAhead() = every("Paused", "NoAsk", "Go ahead")
    @Test fun stoppedGoAhead() = every("Stopped", "NoAsk", "Go ahead")
    @Test fun stoppedUnexpectedlyGoAhead() = every("Stopped unexpectedly", "NoAsk", "Go ahead")
    @Test fun stoppedUnexpectedlyAutoEdit() = every("Stopped unexpectedly", "AcceptEdits", "Auto-edit")
}

/** Coming back to a list by Back never puts the cursor in its search box. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xhdpi")
class P43ReturnNeverFocusesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private val station = FakeStation(ExampleData.awake())

    private fun settle() { compose.mainClock.advanceTimeBy(1000); compose.waitForIdle() }
    private fun searchFocused() = compose.onNodeWithContentDescription("Search tasks", useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }

    @Test fun backFromATaskToAllTasks() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } } }
        compose.waitForIdle()
        // A deliberate first open (the person has touched the app) still takes the cursor.
        compose.runOnUiThread { nav.keyboard.release(); nav.open(Screen.AllTasks()) }
        settle()
        assertEquals(true, searchFocused())
        compose.onAllNodesWithText(station.state.value.task(ExampleData.TASK_ALLOW)!!.title).onFirst().performClick()
        settle()
        assertEquals(Screen.Task(ExampleData.TASK_ALLOW), nav.current)
        compose.runOnUiThread { nav.keyboard.release(); compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertTrue(nav.current is Screen.AllTasks)
        assertEquals(false, searchFocused())
    }
}
