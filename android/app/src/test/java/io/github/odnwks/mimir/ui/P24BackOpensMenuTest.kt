package io.github.odnwks.mimir.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
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
 * P24 fix round 2 (owner, 2026-11-26: "If I swipe back or hit the back button, I should be led
 * directly to that main menu"). Back from any screen reaches Chat with the side menu open; the next
 * Back closes the menu; the one after that leaves the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P24BackOpensMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()

    private fun start() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        val station = FakeStation(ExampleData.awake())
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } } }
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun open(vararg screens: Screen) { screens.forEach { compose.runOnUiThread { nav.open(it) }; compose.waitForIdle() } }

    private fun leavesTheApp() = !compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()

    private fun assertChatWithMenuOpen() {
        assertEquals(listOf(Screen.Chat), nav.stack.map { it.screen })
        assertNull(nav.sheet)
        assertTrue("the side menu is open", nav.menuOpen)
        compose.onAllNodesWithText(MENU_SETTINGS).onLast().assertIsDisplayed()
    }

    private fun assertChatAlone() {
        assertEquals(listOf(Screen.Chat), nav.stack.map { it.screen })
        assertFalse("the side menu is closed", nav.menuOpen)
    }

    @Test fun oneBackFromASubPageIsChatWithTheMenuOpen() {
        start()
        open(Screen.Task(ExampleData.TASK_ALLOW), Screen.Settings, Screen.Notifications)
        systemBack()
        assertChatWithMenuOpen()
    }

    @Test fun theScreensOwnArrowOpensTheMenuToo() {
        start()
        open(Screen.Settings, Screen.PrivacyCheck)
        compose.onAllNodesWithContentDescription("Back").onFirst().performClick()
        compose.waitForIdle()
        assertChatWithMenuOpen()
    }

    @Test fun theNextBackClosesTheMenuAndTheOneAfterLeaves() {
        start()
        open(Screen.Task(ExampleData.TASK_ALLOW), Screen.Settings, Screen.Notifications)
        systemBack()
        assertChatWithMenuOpen()
        assertFalse(leavesTheApp())
        systemBack()
        assertChatAlone()
        assertTrue(leavesTheApp())
    }

    @Test fun aSecondBackWhileTheMenuStillSlidesInClosesIt() {
        start()
        open(Screen.Settings)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(48)
        assertFalse("Back is still taken by the app", leavesTheApp())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertChatAlone()
        assertTrue(leavesTheApp())
    }

    // The same, once the screen has redrawn and the menu has started to move.
    @Test fun aSecondBackAfterTheSlideHasStartedClosesIt() {
        start()
        open(Screen.Settings)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed(); Snapshot.sendApplyNotifications() }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.runOnUiThread { Snapshot.sendApplyNotifications() } }
        assertTrue("the menu is on its way", nav.menuOpen)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertChatAlone()
        assertTrue(leavesTheApp())
    }

    @Test fun aSheetClosesAloneThenBackOpensTheMenu() {
        start()
        open(Screen.Task(ExampleData.TASK_ALLOW))
        compose.runOnUiThread { nav.openSheet(Sheet.TaskOptions(ExampleData.TASK_ALLOW)) }
        compose.waitForIdle()
        systemBack()
        assertNull(nav.sheet)
        assertEquals(Screen.Task(ExampleData.TASK_ALLOW), nav.current)
        assertFalse(nav.menuOpen)
        systemBack()
        assertChatWithMenuOpen()
    }

    // A tapped notification (or any other way in) is not left hidden under the menu Back opened.
    @Test fun aScreenOpenedFromElsewhereIsNotLeftUnderTheMenu() {
        start()
        open(Screen.Settings)
        systemBack()
        assertChatWithMenuOpen()
        open(Screen.Task(ExampleData.TASK_ALLOW))
        assertEquals(Screen.Task(ExampleData.TASK_ALLOW), nav.current)
        assertFalse(nav.menuOpen)
    }

    // The menu is always showing on a wide window: nothing extra to close, so the next Back leaves.
    @Test @Config(qualifiers = "w1280dp-h800dp-xhdpi") fun onAWideWindowTheNextBackLeaves() {
        start()
        open(Screen.Settings, Screen.PrivacyCheck)
        var presses = 0
        while (!leavesTheApp() && presses < 4) { systemBack(); presses++ }
        assertEquals(listOf(Screen.Chat), nav.stack.map { it.screen })
        assertTrue(nav.detail.isEmpty())
        assertFalse(nav.menuOpen)
        assertTrue(presses <= 2)
    }
}
