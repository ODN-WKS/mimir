package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import io.github.odnwks.mimir.alerts.Notifier
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lock Mímir: off unless chosen, turning it on needs a real unlock, the rules of when it asks, the lock screen, the notes, search. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class AppLockTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class Fake(var screenLock: Boolean = true, var succeed: Boolean = true) : AppLockAuth {
        var asked = 0
        override fun hasScreenLock() = screenLock
        override fun available() = screenLock
        override fun ask(title: String, ok: () -> Unit, fail: (Boolean) -> Unit) { asked++; if (succeed) ok() else fail(true) }
    }

    @Before fun clean() { ctx.getSharedPreferences(AppLock.PREFS, 0).edit().clear().commit(); AppLock.resetForTest() }
    @After fun after() { ctx.getSharedPreferences(AppLock.PREFS, 0).edit().clear().commit(); AppLock.resetForTest() }

    private fun privacy(auth: AppLockAuth) {
        val station = FakeStation(ExampleData.awake())
        val nav = Navigator(Screen.PrivacyCheck)
        compose.setContent { CompositionLocalProvider(LocalAppLockAuth provides auth) { MimirTheme { AppRoot(station, nav) } } }
        compose.waitForIdle()
    }

    @Test fun offUnlessChosen() {
        assertFalse(AppLock.on(ctx))
        assertEquals(AppLock.After.Minute, AppLock.after(ctx))
        privacy(Fake())
        compose.onNodeWithText("Lock Mímir").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ask for your fingerprint, face or phone PIN to open Mímir.").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Lock again after").assertCountEquals(0)
    }

    @Test fun turningOnNeedsASuccessfulUnlock() {
        val auth = Fake(succeed = false)
        privacy(auth)
        compose.onNodeWithText("Lock Mímir").performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(1, auth.asked)
        assertFalse(AppLock.on(ctx))
        compose.onAllNodesWithText("Lock again after").assertCountEquals(0)
        auth.succeed = true
        compose.onNodeWithText("Lock Mímir").performScrollTo().performClick(); compose.waitForIdle()
        assertTrue(AppLock.on(ctx))
        compose.onNodeWithText("Lock again after").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 minute").assertIsDisplayed()
        compose.onNodeWithText("Right away").performClick(); compose.waitForIdle()
        assertEquals(AppLock.After.Now, AppLock.after(ctx))
    }

    @Test fun noScreenLockSaysSoAndStaysOff() {
        val auth = Fake(screenLock = false)
        privacy(auth)
        compose.onNodeWithText("Lock Mímir").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Set up a screen lock in Android Settings first.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open settings").assertIsDisplayed()
        assertEquals(0, auth.asked)
        assertFalse(AppLock.on(ctx))
    }

    @Test fun theTimeoutRules() {
        val m = 60_000L
        assertFalse("off never locks", AppLock.shouldLock(false, true, 1L, 10 * m, m))
        assertTrue("already locked stays", AppLock.shouldLock(true, true, 0L, 0L, m))
        assertFalse("back within the time", AppLock.shouldLock(true, false, 1_000L, 1_000L + m - 1, m))
        assertTrue("back after the time", AppLock.shouldLock(true, false, 1_000L, 1_000L + m, m))
        assertTrue("right away: every time", AppLock.shouldLock(true, false, 1_000L, 1_000L, 0L))
        assertFalse("never left: no lock", AppLock.shouldLock(true, false, 0L, 99 * m, m))
    }

    @Test fun theRunningStateFollowsTheRules() {
        AppLock.setOn(ctx, true)
        AppLock.coldStart(ctx, 0L)
        assertTrue("a fresh start is locked", AppLock.locked.value)
        AppLock.unlocked()
        AppLock.left(ctx, 10_000L); AppLock.back(ctx, 10_000L + 30_000L)
        assertFalse(AppLock.locked.value)
        AppLock.left(ctx, 100_000L); AppLock.back(ctx, 100_000L + 61_000L)
        assertTrue(AppLock.locked.value)
        AppLock.unlocked(); AppLock.setAfter(ctx, AppLock.After.Now)
        AppLock.left(ctx, 5L); AppLock.back(ctx, 6L)
        assertTrue(AppLock.locked.value)
    }

    @Test fun aFreshStartWithTheLockOffIsOpen() {
        AppLock.coldStart(ctx, 0L)
        assertFalse(AppLock.locked.value)
    }

    @Test fun theLockScreenShowsNoChat() {
        compose.setContent { MimirTheme { LockScreen(null, onUnlock = {}) } }
        compose.onNodeWithText("Mímir is locked").assertIsDisplayed()
        compose.onNodeWithText("Unlock").assertIsDisplayed()
        // Nothing from the example chats is anywhere in the tree.
        compose.onAllNodesWithText("Garden", substring = true).assertCountEquals(0)
    }

    @Test fun notificationsSayNothingWhileLocked() {
        val item = io.github.odnwks.mimir.link.LItem("a1", "question", "/p/Garden-planner", "t1", "Which bed first?", "Tomatoes or beans?", emptyList(), listOf("Tomatoes", "Beans"), true, 1000, null)
        val open = Notifier.words(item)
        assertTrue(open.first + open.second, open.first != "Mímir has a reply")
        val shut = Notifier.words(item, locked = true)
        assertEquals("Mímir has a reply" to "Open the app to see it", shut)
        assertFalse((shut.first + shut.second).contains("Garden") || (shut.first + shut.second).contains("Tomatoes"))
    }

    @Test fun searchFindsIt() {
        for (w in listOf("lock", "fingerprint", "face", "biometric", "PIN", "security")) {
            assertTrue("'$w' finds Lock Mímir", findSettings(w, true).any { it.title == "Lock Mímir" })
        }
    }

    /** QA2 #12: the first time away after switching the lock on always asks, even on a long "lock again after". */
    @Test fun theFirstTimeAwayAfterSwitchingItOnAlwaysAsks() {
        AppLock.setAfter(ctx, AppLock.After.Five)
        AppLock.setOn(ctx, true)
        AppLock.left(ctx, 1_000L)
        AppLock.back(ctx, 1_500L)
        assertTrue(AppLock.locked.value)
        AppLock.unlocked()
        AppLock.left(ctx, 2_000L)
        AppLock.back(ctx, 2_500L)
        assertFalse("later short trips follow the chosen time", AppLock.locked.value)
    }

    /** QA2 #13: the lock is on but the phone lost its screen lock: the app stays shut and says so. */
    @Test fun aPhoneThatLostItsScreenLockStaysLockedAndSaysSo() {
        AppLock.setOn(ctx, true)
        AppLock.coldStart(ctx, 0L)
        unlockNow(ctx, Fake(screenLock = false))
        assertTrue(AppLock.locked.value)
        assertTrue(AppLock.noScreenLock.value)
        assertTrue(AppLock.on(ctx))
        assertEquals("Lock Mímir is on, but this phone has no screen lock. Set one up in Android settings.", AppLock.note.value)
    }
}
