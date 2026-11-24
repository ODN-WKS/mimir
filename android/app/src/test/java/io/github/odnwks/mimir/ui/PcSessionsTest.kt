package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.PcSession
import io.github.odnwks.mimir.data.PcSessionState
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.pcAgo
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Running on your PC" (S2b): the menu row, both tabs, the rows, opening one, the banner, and a PC that doesn't list sessions. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class PcSessionsTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val now = 1_800_000_000L

    private val mine = PcSession("s-1", "Plan the spring planting", "Garden planner", false, true, null, PcSessionState.Working, now - 5)
    private val task = PcSession("s-2", "Add a seasons page", "Garden planner", true, false, "t-seasons", PcSessionState.Working, now - 120)
    private val idle = PcSession("s-3", "Tidy the recipe list", "Recipe box", false, false, null, PcSessionState.Idle, now - 11 * 3600)
    private val ended = PcSession("s-4", "Fix the shopping list", "Recipe box", false, false, null, PcSessionState.Ended, now - 86_400)

    private fun station(list: List<PcSession>?) = FakeStation().also { st -> st.set { it.copy(pcSessions = list) } }

    @Test fun theTimesAreShort() {
        assertEquals("now", pcAgo(now - 5, now))
        assertEquals("2m", pcAgo(now - 120, now))
        assertEquals("11h", pcAgo(now - 11 * 3600, now))
        assertEquals("1d", pcAgo(now - 86_400, now))
        assertEquals("now", pcAgo(now + 30, now))
    }

    @Test fun whereASessionStartedIsInTheSmallLine() {
        assertEquals("Garden planner · in the Claude app", mine.line)
        assertEquals("Garden planner · a Mime task".replace("Mime", "Mímir"), task.line)
        assertEquals("Recipe box · in a terminal", idle.line)
    }

    @Test fun theMenuRowSaysHowManyRunWithAGreenDot() {
        val s = station(listOf(mine, task, idle)).state.value
        assertEquals("● 2 running", pcMenuRight(s))
        assertEquals(2, pcRunning(s))
        assertEquals("connected", pcMenuRight(s.copy(pcSessions = listOf(idle, ended))))
        assertEquals("connected", pcMenuRight(s.copy(pcSessions = null)))
        assertEquals("not connected", pcMenuRight(s.copy(pc = s.pc.copy(online = false))))
    }

    @Test fun theMenuRowShowsItAndOpensTheListScreen() {
        val st = station(listOf(mine, task, idle))
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle()
        compose.openMenu()
        compose.onAllNodesWithText("● 2 running").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.menuRow(MENU_PC)
        assertEquals(Screen.PhonePc, nav.current)
        compose.onNodeWithText(pcRunningLabel(2)).assertIsDisplayed()
    }

    @Test fun runningShowsOnlyTheWorkingOnesAndAllShowsEverything() {
        val st = station(listOf(mine, task, idle, ended))
        compose.setContent { MimirTheme(reduceMotion = true) { PcSessionsScreen(st.state.value, st, nav, nowUnix = now) } }
        compose.waitForIdle()
        compose.onNodeWithText(pcRunningLabel(2)).assertIsDisplayed()
        compose.onNodeWithText("Plan the spring planting").assertIsDisplayed()
        compose.onNodeWithText("Garden planner · in the Claude app").assertIsDisplayed()
        compose.onNodeWithText("now").assertIsDisplayed()
        compose.onNodeWithText("2m").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Tidy the recipe list").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(PC_ALL_TAB).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Tidy the recipe list").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Recipe box · in a terminal").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("11h").assertIsDisplayed()
        compose.onNodeWithText("Fix the shopping list").assertIsDisplayed()
        compose.onNodeWithText("1d").assertIsDisplayed()
    }

    @Test fun noneRunningSaysSo() {
        val st = station(listOf(idle))
        compose.setContent { MimirTheme(reduceMotion = true) { PcSessionsScreen(st.state.value, st, nav, nowUnix = now) } }
        compose.waitForIdle()
        compose.onNodeWithText(pcRunningLabel(0)).assertIsDisplayed()
        compose.onNodeWithText(PC_NOTHING_RUNNING).assertIsDisplayed()
    }

    @Test fun theMoreLineCountsTheOthersFromToday() {
        val day = now - now % 86_400
        val (w, n) = pcRunningSplit(listOf(mine, idle, ended), day + 12 * 3600 - 0)
        assertEquals(listOf(mine), w)
        assertEquals(0, n)
        val (_, many) = pcRunningSplit(listOf(mine, idle, ended), now - 12 * 3600)
        assertEquals(1, many)
        assertEquals("2 more from earlier today · ", pcMoreLine(2))
    }

    @Test fun aTapOpensTheSessionAsATask() {
        val asked = mutableListOf<String>()
        val base = station(listOf(mine, idle))
        val st = object : Station by base {
            override fun openPcSession(session: String, done: (String?) -> Unit) { asked += session; done("t-$session") }
        }
        compose.setContent { MimirTheme(reduceMotion = true) { PcSessionsScreen(base.state.value, st, nav, nowUnix = now) } }
        compose.waitForIdle()
        compose.onNodeWithText("Plan the spring planting").performClick()
        compose.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(listOf("s-1"), asked)
        assertEquals(Screen.Task("t-s-1"), nav.current)
    }

    @Test fun pullToRefreshAsksThePcAgainAndPcSettingsIsOneRowAway() {
        var refreshed = 0
        val base = station(listOf(mine))
        val st = object : Station by base { override fun refreshPcSessions() { refreshed++ } }
        compose.setContent { MimirTheme(reduceMotion = true) { PcSessionsScreen(base.state.value, st, nav, nowUnix = now) } }
        compose.waitForIdle()
        compose.onNodeWithText(PC_SETTINGS_ROW).performClick(); compose.waitForIdle()
        assertEquals(Screen.PcSettings, nav.current)
        assertEquals(0, refreshed)
    }

    @Test fun pcSettingsKeepsEverythingTheOldScreenHad() {
        val st = station(listOf(mine))
        compose.setContent { MimirTheme(reduceMotion = true) { PhonePcScreen(st.state.value, st, nav, settings = true) } }
        compose.waitForIdle()
        compose.onNodeWithText("PC settings").assertExists()
        compose.onNodeWithText("Phones that can reach it").assertExists()
        compose.onNodeWithText("Disconnect this phone").assertExists()
    }

    @Test fun anOlderPcKeepsTodaysYourPcScreenAndMenuRow() {
        val st = station(null)
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle()
        compose.menuRow(MENU_PC)
        assertEquals(Tab.Status, nav.selectedTab)
        nav.open(Screen.PhonePc); compose.waitForIdle()
        compose.onNodeWithText("Phones that can reach it").assertExists()
        assertTrue(compose.onAllNodesWithText(PC_SETTINGS_ROW).fetchSemanticsNodes().isEmpty())
    }

    @Test fun whileThePcIsAwayTheListIsDimmedWithNoGreenAndNoCount() {
        val base = station(listOf(mine, idle))
        val away = base.state.value.let { it.copy(pc = it.pc.copy(online = false, lastSeen = "09:41")) }
        compose.setContent { MimirTheme(reduceMotion = true) { PcSessionsScreen(away, base, nav, nowUnix = now) } }
        compose.waitForIdle()
        compose.onNodeWithText(PC_RUNNING_TAB).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(pcRunningLabel(1)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(pcLastSeenLine("09:41")).assertIsDisplayed()
        assertEquals("Your PC is away. Last seen 09:41.", pcLastSeenLine("09:41"))
        compose.onNodeWithText("Plan the spring planting").assertIsDisplayed()
    }

    @Test fun theAllTabSaysNothingHereYetWhenEmpty() {
        val st = station(emptyList())
        compose.setContent { MimirTheme(reduceMotion = true) { PcSessionsScreen(st.state.value, st, nav, nowUnix = now) } }
        compose.waitForIdle()
        compose.onNodeWithText(PC_ALL_TAB).performClick(); compose.waitForIdle()
        compose.onNodeWithText(PC_NOTHING_YET).assertIsDisplayed()
    }

    @Test fun aSecondTapWhileOpeningIsIgnored() {
        val asked = mutableListOf<String>()
        val base = station(listOf(mine))
        val st = object : Station by base { override fun openPcSession(session: String, done: (String?) -> Unit) { asked += session } }
        compose.setContent { MimirTheme(reduceMotion = true) { PcSessionsScreen(base.state.value, st, nav, nowUnix = now) } }
        compose.waitForIdle()
        compose.onNodeWithText("Plan the spring planting").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Plan the spring planting").performClick(); compose.waitForIdle()
        assertEquals(listOf("s-1"), asked)
    }

    @Test fun theTimesRefreshEveryHalfMinute() {
        var t = now
        val st = station(listOf(task))
        compose.setContent { MimirTheme(reduceMotion = true) { PcSessionsScreen(st.state.value, st, nav, clock = { t }) } }
        compose.waitForIdle()
        compose.onNodeWithText("2m").assertIsDisplayed()
        t = now + 180
        compose.mainClock.advanceTimeBy(PC_TIME_REFRESH_MS + 100); compose.waitForIdle()
        compose.onNodeWithText("5m").assertIsDisplayed()
    }

    @Test fun aSessionWorkingElsewhereIsFollowedFromTheFirstFrame() {
        val id = ExampleData.TASK_ALLOW
        val base = station(listOf(mine.copy(here = false, task = id)))
        assertTrue(followedElsewhere(base.state.value, id))
        assertTrue(!followedElsewhere(base.state.value.copy(pcSessions = listOf(mine.copy(here = true, task = id))), id))
        assertTrue(!followedElsewhere(base.state.value.copy(pcSessions = listOf(idle.copy(task = id))), id))
        assertTrue(!followedElsewhere(base.state.value.copy(pcSessions = null), id))
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(base, nav) } }
        compose.waitForIdle()
        nav.open(Screen.Task(id)); compose.waitForIdle()
        // Still running: the one line and Stop; "Carry on here" is not there at all (not grey).
        compose.onNodeWithText(followBannerText("the Claude app")).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(FOLLOW_CARRY_ON).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aSessionGoingOnElsewhereShowsTheBannerAndStopWorks() {
        val id = ExampleData.TASK_ALLOW
        val linked = mine.copy(here = true, task = id)
        val base = station(listOf(linked))
        base.set { s -> s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(liveElsewhere = true) else it }) }
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(base, nav) } }
        compose.waitForIdle()
        nav.open(Screen.Task(id)); compose.waitForIdle()
        compose.onNodeWithText("Still running in the Claude app. Stop it to carry on here.").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(FOLLOW_CARRY_ON).fetchSemanticsNodes().isEmpty())
        assertEquals("Still running in a terminal. Stop it to carry on here.", followBannerText("a terminal"))
        // An older PC that doesn't say where: "your PC".
        assertEquals(null, followedIn(base.state.value.copy(pcSessions = emptyList()), base.state.value.task(id)!!))
        assertEquals("Still running on your PC. Stop it to carry on here.", followBannerText(null))
        compose.onNodeWithContentDescription(FOLLOW_STOP).performClick() // 0.09: asks "Stop?" first
        compose.onNodeWithContentDescription(FOLLOW_STOP).performClick(); compose.waitForIdle()
        assertTrue(base.state.value.task(id)!!.stopped)
        // Once it has stopped: Carry on here is the one button, and the line is gone.
        compose.onNodeWithText(FOLLOW_CARRY_ON).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithContentDescription(FOLLOW_STOP).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText(followBannerText("the Claude app")).fetchSemanticsNodes().isEmpty())
    }
}
