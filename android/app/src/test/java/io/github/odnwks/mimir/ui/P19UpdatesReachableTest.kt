package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Heard
import io.github.odnwks.mimir.data.PastChat
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.data.Update
import io.github.odnwks.mimir.data.UpdateTarget
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

/**
 * P19 (coordinator, 2026-11-25): Updates left the menu, but its spoken reports stay one tap away from
 * the Recent activity page: "Listen to N new updates" above TODAY while some are unheard, else a dim
 * "Past updates" link at the foot when there are heard ones, else nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P19UpdatesReachableTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()

    /** Wednesday, Nov 25 2026, 7:12 PM on this phone's clock. */
    private val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 25, 19, 12) }.timeInMillis
    private fun minsAgo(m: Long) = (now - m * 60_000) / 1000

    private fun task(id: String, title: String, project: String, first: String, atUnix: Long) = Task(
        id, title, project, "Sonnet", "", emptyList(),
        listOf(TaskMessage("$id-0", false, first), TaskMessage("$id-1", true, "On it.")),
        null, null, null, status = "Done", busy = false, createdUnix = atUnix, activeUnix = atUnix,
    )

    private val tasks = listOf(
        task("t-plants", "Nov 25, 5:50 PM", "Garden app", "continue the plant list page", minsAgo(82)),
        task("t-theme", "Nov 24, 9:31 AM", "Garden app", "add a dark colour theme", minsAgo(34 * 60)),
    )
    private val chats = listOf(
        PastChat("c-soil", "Best soil for tomatoes", now - 3 * 3_600_000L, 4, "which soil is best for tomatoes in pots Loamy soil.", "which soil is best for tomatoes in pots"),
    )

    private fun update(id: String, heard: Heard) =
        Update(id, "Garden app", "6:40 pm", "at 6:40 pm", "The plant list is done. It sorts by name.", heard, UpdateTarget.TaskOf("t-plants"), 20)

    private fun state(newCount: Int, heardCount: Int): StationState = ExampleData.awake().copy(
        tasks = tasks, pastChats = chats, pcTalks = false, needsYou = emptyList(),
        updates = List(newCount) { update("n$it", Heard.New) } + List(heardCount) { update("h$it", Heard.Heard) },
    )

    private fun showRecent(st: StationState, fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MimirTheme(reduceMotion = true) { RecentActivityScreen(st, FakeStation(st), null, nav, now = now) }
            }
        }
        compose.waitForIdle()
    }

    private fun none(text: String) = assertTrue("\"$text\" shows", compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty())

    @Test fun threeUnheardShowTheLineAboveTodayAndATapOpensUpdates() {
        val st = state(newCount = 3, heardCount = 2)
        // The menu button's number still counts them.
        assertEquals(3, menuCount(st))
        showRecent(st)
        compose.onNodeWithText("Listen to 3 new updates").assertIsDisplayed()
        val line = compose.onNodeWithText("Listen to 3 new updates").getUnclippedBoundsInRoot().top
        val today = compose.onNodeWithText(RECENT_TODAY).getUnclippedBoundsInRoot().top
        assertTrue("the line ($line) is above TODAY ($today)", line < today)
        // Unheard ones come first: no foot link while the line shows.
        none(PAST_UPDATES)
        compose.onNodeWithText("Listen to 3 new updates").performClick(); compose.waitForIdle()
        assertEquals(Screen.Updates, nav.current)
    }

    @Test fun oneUnheardSaysUpdate() {
        assertEquals("Listen to 1 new update", listenNewWords(1))
        showRecent(state(newCount = 1, heardCount = 0))
        compose.onNodeWithText("Listen to 1 new update").assertIsDisplayed()
    }

    @Test fun heardOnlyShowsTheFootLink() {
        showRecent(state(newCount = 0, heardCount = 2))
        none("Listen to")
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText(PAST_UPDATES)); compose.waitForIdle()
        compose.onNodeWithText(PAST_UPDATES).assertIsDisplayed()
        // At the very bottom: below the last item.
        val link = compose.onNodeWithText(PAST_UPDATES).getUnclippedBoundsInRoot().top
        val last = compose.onNodeWithText("Nov 24, 9:31 AM").getUnclippedBoundsInRoot().top
        assertTrue("the link ($link) is below the last item ($last)", link > last)
        compose.onNodeWithText(PAST_UPDATES).performClick(); compose.waitForIdle()
        assertEquals(Screen.Updates, nav.current)
    }

    @Test fun noReportsShowNothing() {
        showRecent(state(newCount = 0, heardCount = 0))
        none("Listen to")
        none(PAST_UPDATES)
        compose.onNodeWithText(RECENT_TODAY).assertIsDisplayed()
    }

    @Test fun anEmptyPageStillReachesThem() {
        showRecent(state(newCount = 2, heardCount = 0).copy(tasks = emptyList(), pastChats = emptyList()))
        compose.onNodeWithText(RECENT_EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Listen to 2 new updates").performClick(); compose.waitForIdle()
        assertEquals(Screen.Updates, nav.current)
    }

    private fun fitsOneLine(t: String, width: Float) {
        val n = compose.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().first()
        val results = mutableListOf<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val r = results.single()
        val box = n.boundsInRoot
        val dens = compose.density.density
        assertTrue("past the edge: $t (${box.left / dens}..${box.right / dens})", box.left >= 0f && box.right / dens <= width + 0.5f)
        assertEquals("\"$t\" wraps", 1, r.lineCount)
        assertFalse("\"$t\" is cut", r.isLineEllipsized(0))
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun theLineIsNotCutOffAt320dpAndTextSize13() {
        showRecent(state(newCount = 3, heardCount = 0), fontScale = 1.3f)
        fitsOneLine("Listen to 3 new updates", 320f)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun theLinkIsNotCutOffAt320dpAndTextSize13() {
        showRecent(state(newCount = 0, heardCount = 2), fontScale = 1.3f)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(PAST_UPDATES)); compose.waitForIdle()
        fitsOneLine(PAST_UPDATES, 320f)
    }
}
