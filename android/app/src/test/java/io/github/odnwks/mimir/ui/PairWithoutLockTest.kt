package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.data.PairProgress
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Owner, 2026-11-19: a phone with no screen lock is warned once when pairing, never refused. */
@RunWith(RobolectricTestRunner::class)
class PairWithoutLockTest {
    @get:Rule val compose = createComposeRule()

    private fun start(lock: Boolean): Pair<FakeStation, Navigator> {
        val station = FakeStation(LinkStation.empty("test").copy(paired = false, screenLock = lock))
        val nav = Navigator()
        compose.setContent { MimirTheme { AppRoot(station, nav) } }
        nav.open(Screen.Pair); compose.waitForIdle()
        compose.onNodeWithText(CONNECT_TYPE_INSTEAD).performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Pairing code").performTextInput("CODE")
        compose.onNodeWithText("Pair").performClick(); compose.waitForIdle()
        return station to nav
    }

    @Test fun noScreenLockWarnsOnceAndPairAnywayPairs() {
        val (station, nav) = start(lock = false)
        compose.onNodeWithText(NO_LOCK_WARNING).assertIsDisplayed()
        compose.onNodeWithText("Pair anyway").performClick(); compose.waitForIdle()
        assertTrue(station.state.value.pairing is PairProgress.ConfirmOnPc)
        assertEquals(Screen.PairDone, nav.current)
    }

    @Test fun cancelLeavesThePairScreen() {
        val (station, nav) = start(lock = false)
        compose.onNodeWithText("Cancel").performClick(); compose.waitForIdle()
        assertEquals(PairProgress.Idle, station.state.value.pairing)
        assertEquals(Screen.Pair, nav.current)
    }

    @Test fun withAScreenLockNothingIsAsked() {
        val (station, _) = start(lock = true)
        compose.onNodeWithText(NO_LOCK_WARNING).assertDoesNotExist()
        assertTrue(station.state.value.pairing is PairProgress.ConfirmOnPc)
    }
}
