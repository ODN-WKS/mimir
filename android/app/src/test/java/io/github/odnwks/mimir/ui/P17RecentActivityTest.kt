package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.Chip
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.PastChat
import io.github.odnwks.mimir.data.ProjectTask
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
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
 * P17 (owner, 2026-11-25): "Recent activity" takes the place of the menu's Updates row, Whisper sits
 * under New chat, and the Recent activity page lists every conversation, newest first, each in its own
 * two-line bubble (mock-up 10). Pictures are saved only when P17_SHOT names a folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
// Real text measuring, so a line too long for its room shows.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P17RecentActivityTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val out = System.getenv("P17_SHOT")

    /** Wednesday, Nov 25 2026, 7:12 PM on this phone's clock. */
    private val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 25, 19, 12) }.timeInMillis
    private fun minsAgo(m: Long) = (now - m * 60_000) / 1000

    private fun task(id: String, title: String, project: String, first: String, atUnix: Long, status: String, busy: Boolean = false) = Task(
        id, title, project, "Sonnet", "", emptyList(),
        listOf(TaskMessage("$id-0", false, first), TaskMessage("$id-1", true, "On it.")),
        null, null, null, status = status, busy = busy, createdUnix = atUnix, activeUnix = atUnix,
    )

    private val tasks = listOf(
        task("t-water", "Nov 25, 7:02 PM", "Garden app", "make the watering reminder louder", minsAgo(10), "Working", busy = true),
        task("t-plants", "Nov 25, 5:50 PM", "Garden app", "continue the plant list page", minsAgo(82), "Done"),
        task("t-search", "Nov 25, 2:14 PM", "Recipes site", "the search box misses some recipes", minsAgo(298), "Needs you"),
        task("t-theme", "Nov 24, 9:31 AM", "Garden app", "add a dark colour theme", minsAgo(34 * 60), "Done"),
        task("t-print", "Nov 23, 8:15 PM", "Recipes site", "add a print button to each recipe", minsAgo(47 * 60), "Done"),
    )
    private val chats = listOf(
        PastChat("c-soil", "Best soil for tomatoes", now - 3 * 3_600_000L, 4, "which soil is best for tomatoes in pots Loamy soil with compost.", "which soil is best for tomatoes in pots"),
        PastChat("c-trip", "Trip packing list", now - 40 * 3_600_000L, 6, "what should I pack for a week away Light layers.", "what should I pack for a week away"),
    )

    private fun state(withTasks: List<Task> = tasks, withChats: List<PastChat> = chats): StationState = ExampleData.awake().let { s ->
        s.copy(
            tasks = withTasks, pastChats = withChats, pcTalks = false, needsYou = emptyList(),
            projects = listOf(
                s.projects.first().copy(name = "Garden app", tasks = withTasks.filter { it.project == "Garden app" }.map { ProjectTask(it.id, it.title, if (it.busy) "Working" else it.status, if (it.busy) Chip.Running else Chip.Done) }),
                s.projects.first().copy(name = "Recipes site", tasks = emptyList(), tasksRunning = 0),
            ),
        )
    }

    private fun showMenu(st: StationState, fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MimirTheme(reduceMotion = true) { SideMenu(st, null, FakeStation(st), null, nav, MenuFold(null)) }
            }
        }
        compose.waitForIdle()
    }

    private fun showRecent(st: StationState, fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MimirTheme(reduceMotion = true) { RecentActivityScreen(st, FakeStation(st), null, nav, now = now) }
            }
        }
        compose.waitForIdle()
    }

    private fun top(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().first().let { compose.onNodeWithText(text).getUnclippedBoundsInRoot().top }

    @Test fun theMenuOrderIsNewChatWhisperNeedsYouRecentActivityYourPc() {
        showMenu(state())
        val order = listOf(MENU_NEW_CHAT, MENU_WHISPER, MENU_NEEDS_YOU, MENU_RECENT, MENU_PC)
        val tops = order.map { top(it) }
        assertEquals("order of $order is $tops", tops.sorted(), tops)
        out?.let { compose.onRoot().captureRoboImage("$it/p17_menu.png") }
    }

    @Test fun noUpdatesAnywhereInTheMenu() {
        showMenu(state())
        compose.onAllNodesWithText("Updates", substring = true, ignoreCase = true).fetchSemanticsNodes().let {
            assertTrue("the menu still says Updates: ${it.size}", it.isEmpty())
        }
        compose.onNodeWithText(MENU_RECENT).assertIsDisplayed()
    }

    @Test fun recentActivitySaysHowManyWorkAndNothingWhenNone() {
        showMenu(state())
        compose.onNodeWithText("1 working").assertIsDisplayed()
        compose.onNodeWithText(MENU_RECENT).performClick(); compose.waitForIdle()
        assertEquals(Screen.Recent, nav.current)
    }

    @Test fun recentActivityRowIsPlainWhenNothingWorks() {
        showMenu(state(withTasks = tasks.map { it.copy(busy = false, status = "Done") }))
        compose.onNodeWithText(MENU_RECENT).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("working", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun thePageListsChatsAndTasksNewestFirstUnderTodayAndEarlier() {
        val items = recentActivity(state(), null, now)
        assertEquals(
            listOf("t-water", "t-plants", "c-soil", "t-search", "t-theme", "c-trip", "t-print"),
            items.map { it.key.substringAfter(':') },
        )
        assertEquals(listOf(true, true, true, true, false, false, false), items.map { recentIsToday(it.at, now) })
        showRecent(state())
        val order = listOf(RECENT_TODAY, "Nov 25, 7:02 PM", "Nov 25, 5:50 PM", "Best soil for tomatoes", "Nov 25, 2:14 PM", RECENT_EARLIER, "Nov 24, 9:31 AM", "Trip packing list", "Nov 23, 8:15 PM")
        val list = compose.onNode(hasScrollAction())
        val tops = order.map { list.performScrollToNode(hasText(it)); compose.waitForIdle(); it to compose.onNodeWithText(it).getUnclippedBoundsInRoot().top }
        // Scrolling moves things, so each pair is compared right after both show.
        for (i in 0 until order.size - 1) {
            list.performScrollToNode(hasText(order[i])); compose.waitForIdle()
            list.performScrollToNode(hasText(order[i + 1])); compose.waitForIdle()
            val a = compose.onNodeWithText(order[i]).getUnclippedBoundsInRoot().top
            val b = compose.onNodeWithText(order[i + 1]).getUnclippedBoundsInRoot().top
            assertTrue("${order[i]} ($a) is above ${order[i + 1]} ($b); all: $tops", a < b)
        }
        compose.onNodeWithText(RECENT_TITLE).assertIsDisplayed()
    }

    @Test fun eachItemShowsItsTwoLinesWithTheProjectOnlyWhereThereIsOne() {
        val items = recentActivity(state(), null, now).associateBy { it.key.substringAfter(':') }
        assertEquals("Garden app", items.getValue("t-water").project)
        assertEquals("make the watering reminder louder", items.getValue("t-water").words)
        assertEquals("working", items.getValue("t-water").right)
        assertEquals(RecentTone.Working, items.getValue("t-water").tone)
        assertEquals("needs you", items.getValue("t-search").right)
        assertEquals(RecentTone.Needs, items.getValue("t-search").tone)
        assertEquals("done", items.getValue("t-plants").right)
        assertEquals(null, items.getValue("c-soil").project)
        assertEquals("3 h", items.getValue("c-soil").right)
        showRecent(state())
        compose.onNodeWithText("Garden app · make the watering reminder louder").assertIsDisplayed()
        compose.onNodeWithText("Recipes site · the search box misses some recipes").assertIsDisplayed()
        compose.onNodeWithText("Chat · which soil is best for tomatoes in pots").assertIsDisplayed()
        compose.onNodeWithText("working").assertIsDisplayed()
        compose.onNodeWithText("needs you").assertIsDisplayed()
        compose.onNodeWithText("3 h").assertIsDisplayed()
        // A chat has no project in blue: its second line starts with "Chat".
        assertTrue(compose.onAllNodesWithText("Garden app · which soil", substring = true).fetchSemanticsNodes().isEmpty())
        out?.let { compose.onRoot().captureRoboImage("$it/p17_recent_activity.png") }
    }

    @Test fun aTapOpensTheRightScreen() {
        showRecent(state())
        compose.onNodeWithText("Nov 25, 5:50 PM").performClick(); compose.waitForIdle()
        assertEquals(Screen.Task("t-plants"), nav.current)
        nav.back(); compose.waitForIdle()
        compose.onNodeWithText("Best soil for tomatoes").performClick(); compose.waitForIdle()
        assertEquals(Tab.Chat, nav.selectedTab)
        assertEquals(Screen.Chat, nav.current)
    }

    @Test fun theEmptyPageSaysSo() {
        showRecent(state(withTasks = emptyList(), withChats = emptyList()))
        compose.onNodeWithText(RECENT_EMPTY).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(RECENT_TODAY).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aProjectTaskCardShowsItsFirstWordsUnderTheTimeName() {
        val st = state()
        compose.setContent { MimirTheme(reduceMotion = true) { ProjectScreen("Garden app", st, FakeStation(st), nav) } }
        compose.waitForIdle()
        compose.onNodeWithText("Nov 25, 5:50 PM").assertIsDisplayed()
        compose.onNodeWithText("continue the plant list page").assertIsDisplayed()
    }

    private fun fitsOneLine(t: String, width: Float, mayCut: Boolean = false) {
        val n = compose.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().first()
        val results = mutableListOf<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val r = results.single()
        val box = n.boundsInRoot
        val dens = compose.density.density
        assertTrue("past the edge: $t (${box.left / dens}..${box.right / dens})", box.left >= 0f && box.right / dens <= width + 0.5f)
        if (!mayCut) {
            assertEquals("\"$t\" wraps", 1, r.lineCount)
            assertFalse("\"$t\" is cut", r.isLineEllipsized(0))
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun nothingIsCutOffInTheMenuAt320dpAndTextSize13() {
        showMenu(state(), fontScale = 1.3f)
        for (t in listOf(MENU_NEW_CHAT, MENU_WHISPER, MENU_NEEDS_YOU, MENU_RECENT, "1 working", MENU_PC)) fitsOneLine(t, 320f)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun nothingIsCutOffOnThePageAt320dpAndTextSize13() {
        showRecent(state(), fontScale = 1.3f)
        fitsOneLine(RECENT_TITLE, 320f)
        for (t in listOf("Nov 25, 7:02 PM", "working", "Nov 25, 5:50 PM", "done", "Best soil for tomatoes", "3 h")) fitsOneLine(t, 320f)
        // The second line is one line, cut with "…" when long, never past the edge.
        fitsOneLine("Garden app · make the watering reminder louder", 320f, mayCut = true)
    }
}
