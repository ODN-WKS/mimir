package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.StayMode
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Stage 1, H9: what section 5 takes off the phone's Status, Updates and Settings. */
@RunWith(RobolectricTestRunner::class)
class StageOneRemovalsTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()

    private fun start(change: (io.github.odnwks.mimir.data.StationState) -> io.github.odnwks.mimir.data.StationState): FakeStation {
        val st = FakeStation().also { it.set(change) }
        compose.setContent { MimirTheme { AppRoot(st, nav) } }
        compose.waitForIdle()
        return st
    }

    @Test fun theMenuAlwaysHasUpdates() {
        // An owner-reported bug: Updates vanished when there were none. It is a menu row paired or not, with or without updates.
        assertEquals(Tab.entries, Tab.shown(paired = true, anyUpdates = false))
        assertEquals(Tab.entries, Tab.shown(paired = true, anyUpdates = true))
        assertEquals(Tab.entries, Tab.shown(paired = false, anyUpdates = false))
        start { it.copy(updates = emptyList()) }
        compose.openMenu()
        compose.onNodeWithText(MENU_UPDATES).assertExists()
        compose.onNodeWithText(MENU_PC).assertExists()
        compose.menuRow(MENU_UPDATES)
        assertEquals(Tab.Updates, nav.selectedTab)
    }

    @Test fun statusHasNoNoChecksYetAndNoClaudeUseWithoutAPaidChoice() {
        start { it.copy(paidOn = false) }
        nav.selectTab(Tab.Status); compose.waitForIdle()
        compose.onNodeWithText("No checks yet").assertDoesNotExist()
        compose.onNodeWithText("Online use this week").assertDoesNotExist()
    }

    @Test fun claudeUseStillShowsWhenAPaidChoiceIsOn() {
        start { it.copy(paidOn = true) }
        nav.selectTab(Tab.Status); compose.waitForIdle()
        compose.onNodeWithText("Online use this week").assertExists()
    }

    @Test fun notificationsSayInOneLineWhenNeedsYouReachesTheClosedApp() {
        start { it.copy(stayMode = StayMode.BatterySaver) }
        nav.open(Screen.Notifications); compose.waitForIdle()
        compose.onNodeWithText("With the app closed, things that need you show when you open it.").assertExists()
        compose.onNodeWithText("Instant alerts aren't set up", substring = true).assertDoesNotExist()
    }
}
