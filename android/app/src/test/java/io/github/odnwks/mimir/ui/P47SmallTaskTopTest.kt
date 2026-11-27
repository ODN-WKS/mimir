package io.github.odnwks.mimir.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P47 (owner, 2026-11-27): a task's top is one small line: state at the very left, the mode tag, the
 * project, a small Stop while it works, the three dots. No Back arrow and no task name.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class P47SmallTaskTopTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nav = Navigator()
    private val id = ExampleData.TASK_ALLOW
    private val title = "Nov 27, 1:48 AM"
    private val long = "Garden planner for the whole long summer"

    private fun top(w: String): Rect {
        val d = compose.density.density
        return compose.onAllNodesWithText(w, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { !it.config.contains(SemanticsProperties.TestTag) && it.boundsInRoot.top < 48f * d && it.boundsInRoot.width > 0f }
            .also { assertTrue("\"$w\" is in the top line", it.isNotEmpty()) }.first().boundsInRoot
    }

    private fun check() {
        val station = FakeStation().also { s ->
            s.set { x -> x.copy(tasks = x.tasks.map { if (it.id == id) it.copy(permission = null, question = null, stopped = false, busy = true, status = "Working", mode = "NoAsk", project = long, title = title) else it }) }
        }
        val tags = listOf("Working", "Go ahead")
        compose.setContent {
            MimirTheme(reduceMotion = true) {
                Box(Modifier.fillMaxSize()) {
                    AppRoot(station, nav)
                    tags.forEach { w -> Box(Modifier.align(Alignment.BottomStart).wrapContentWidth(unbounded = true)) { Tag(w, Palette.Text, Palette.Ground, Modifier.testTag("need-$w")) } }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { nav.open(Screen.Task(id)) }
        compose.waitForIdle()
        val d = compose.density.density
        val state = top("Working")
        val mode = top("Go ahead")
        val project = top(long)
        val stop = compose.onNodeWithContentDescription("Stop").fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithContentDescription("Task options").fetchSemanticsNode().boundsInRoot
        // The tag's text sits 9 dp inside the tag.
        assertTrue("the state is at the very left: ${state.left / d} dp", state.left / d <= 22f)
        val order = listOf(state, mode, project, stop, more)
        order.zipWithNext().forEach { (a, b) -> assertTrue("left to right: $order", a.right <= b.left + 1f) }
        order.forEach { assertTrue("one line: $order", it.top < more.center.y + 4f * d && it.bottom > more.center.y - 4f * d) }
        assertTrue("one small line: ${more.bottom / d} dp", more.bottom / d <= 41f)
        tags.forEach { w ->
            val needs = compose.onNodeWithTag("need-$w", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width - 18f * d
            assertTrue("\"$w\" is cut: ${top(w).width / d} of ${needs / d} dp", top(w).width + d >= needs)
        }
        assertEquals(0, compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun oneSmallLineAt384() = check()
    @Test @Config(qualifiers = "w339dp-h733dp-xxhdpi") fun oneSmallLineAt339() = check()
}
