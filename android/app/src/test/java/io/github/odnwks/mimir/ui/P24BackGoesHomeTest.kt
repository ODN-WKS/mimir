package io.github.odnwks.mimir.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P24, Back goes home (owner, 2026-11-26: "when I hit back from any screen it doesn't go back to the
 * homepage"). Home is Chat, the screen the app opens on. One Back from any screen goes there; a
 * sheet, the side menu or a tablet's right pane closes first; on Chat, Back leaves the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P24BackGoesHomeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var station: FakeStation

    private fun start() {
        station = FakeStation(ExampleData.awake())
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } } }
        compose.waitForIdle()
    }

    /** The system's Back (the gesture or the button), as the phone sends it. */
    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun openAll(vararg screens: Screen) { screens.forEach { nav.open(it); compose.waitForIdle() } }

    private fun atHome() {
        assertEquals(listOf(Screen.Chat), nav.stack.map { it.screen })
        assertTrue(nav.detail.isEmpty())
        assertNull(nav.sheet)
    }

    // 1. Three screens in a row, then Back once: home.
    @Test fun threeScreensThenOneBackIsHome() {
        val n = Navigator()
        n.open(Screen.Task("t")); n.open(Screen.Settings); n.open(Screen.Notifications)
        assertTrue(n.back())
        assertEquals(listOf(Screen.Chat), n.stack.map { it.screen })
        assertFalse(n.canGoBack)
        // From another section too, however deep.
        n.selectTab(Tab.Status); n.open(Screen.Project("Garden planner")); n.open(Screen.Task("t")); n.open(Screen.Change("t"))
        assertTrue(n.back())
        assertEquals(listOf(Screen.Chat), n.stack.map { it.screen })
    }

    @Test fun theSystemBackFromThreeScreensIsHome() {
        start()
        openAll(Screen.Task(ExampleData.TASK_ALLOW), Screen.Settings, Screen.Notifications)
        systemBack()
        atHome()
    }

    // 2. The app's own Back arrow on any screen: home.
    @Test fun everyBackArrowGoesHome() {
        start()
        val project = station.state.value.projects.first().name
        val trails = listOf(
            listOf(Screen.Task(ExampleData.TASK_ALLOW), Screen.Settings, Screen.Notifications),
            listOf(Screen.Settings, Screen.PrivacyCheck),
            listOf(Screen.Settings, Screen.General),
            listOf(Screen.Project(project), Screen.Task(ExampleData.TASK_ALLOW), Screen.Change(ExampleData.TASK_ALLOW)),
            listOf(Screen.Settings, Screen.Models, Screen.GroundRules),
            listOf(Screen.Settings, Screen.PhonePc, Screen.Pair, Screen.PairError),
            listOf(Screen.Settings, Screen.Project(project)),
        )
        for (trail in trails) {
            nav.resetTo(Screen.Chat); compose.waitForIdle()
            openAll(*trail.toTypedArray())
            compose.onAllNodesWithContentDescription("Back").onFirst().performClick()
            compose.waitForIdle()
            assertEquals("Back arrow on ${trail.last()}", listOf(Screen.Chat), nav.stack.map { it.screen })
        }
    }

    // 3. A sheet or the side menu open: Back closes only that, the next Back goes home.
    @Test fun aSheetClosesFirstThenHome() {
        val n = Navigator()
        n.open(Screen.Task("t")); n.open(Screen.Change("t"))
        n.openSheet(Sheet.TaskOptions("t"))
        assertTrue(n.back())
        assertNull(n.sheet)
        assertEquals(Screen.Change("t"), n.current)
        assertTrue(n.back())
        assertEquals(listOf(Screen.Chat), n.stack.map { it.screen })
    }

    @Test fun theSideMenuClosesFirstThenHome() {
        start()
        // The menu button sits on the pages the menu leads to (here Recent activity, three screens in).
        openAll(Screen.Task(ExampleData.TASK_ALLOW), Screen.Change(ExampleData.TASK_ALLOW), Screen.Recent)
        compose.openMenu()
        assertTrue(nav.menuOpen)
        systemBack()
        assertFalse(nav.menuOpen)
        assertEquals(Screen.Recent, nav.current)
        systemBack()
        atHome()
    }

    // 4. On the home screen, Back leaves the app (nothing in the app takes it).
    @Test fun onHomeBackLeavesTheApp() {
        start()
        assertFalse(nav.canGoBack)
        assertFalse(nav.back())
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    // 5. Two panes: Back closes the right pane, then home.
    @Test fun twoPanesCloseTheRightPaneThenHome() {
        val n = Navigator().also { it.fitPanes(true) }
        n.selectTab(Tab.Status)
        n.open(Screen.Task("b")); n.open(Screen.Change("b"))
        assertEquals(listOf(Screen.Task("b"), Screen.Change("b")), n.detail.map { it.screen })
        assertTrue(n.back())
        assertTrue(n.detail.isEmpty())
        assertEquals(Screen.Status, n.current)
        assertTrue(n.back())
        assertEquals(listOf(Screen.Chat), n.stack.map { it.screen })
        // Settings with a section and a page inside it on the right.
        n.open(Screen.Settings); n.open(Screen.Help); n.open(Screen.About)
        assertEquals(listOf(Screen.Help, Screen.About), n.detail.map { it.screen })
        assertTrue(n.back())
        assertTrue(n.detail.isEmpty())
        assertTrue(n.back())
        assertEquals(listOf(Screen.Chat), n.stack.map { it.screen })
        assertFalse(n.back())
    }

    // 6. A screen closing itself after its job (not a Back press) still returns where it came from.
    @Test fun aScreenDoneWithItsJobStillReturnsOneStep() {
        val n = Navigator()
        n.open(Screen.Settings); n.open(Screen.Project("Garden planner")); n.open(Screen.Task("t")); n.open(Screen.Change("t"))
        assertTrue(n.closeScreen())
        assertEquals(Screen.Task("t"), n.current)
        assertTrue(n.closeScreen())
        assertEquals(Screen.Project("Garden planner"), n.current)
    }
}
