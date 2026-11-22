package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.StationState
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

/** Settings after the QA1 re-think: each moved item is found by search AND by its row, and lives in one place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class F2MovedItemsTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()

    private fun start(data: (StationState) -> StationState = { it }) {
        compose.setContent { MimirTheme { AppRoot(FakeStation(data(ExampleData.awake())), nav) } }
        compose.waitForIdle()
        nav.open(Screen.Settings); compose.waitForIdle()
    }

    private fun row(text: String) { compose.onNodeWithText(text).performScrollTo().performClick(); compose.waitForIdle() }

    private fun found(query: String, screen: Screen, paired: Boolean = true) =
        assertTrue("$query -> $screen", findSettings(query, paired).any { it.screen == screen })

    @Test fun searchFindsEveryMovedItemWhereItNowLives() {
        found("key", Screen.Models); found("keys", Screen.Models); found("online", Screen.Models)
        found("update", Screen.About); found("new version", Screen.About)
        found("updates", Screen.HelpAnswer("updates")); found("spoken", Screen.HelpAnswer("updates"))
        found("lost phone", Screen.HelpAnswer("lost"))
        found("pair", Screen.PhonePc); found("pair", Screen.Pair, paired = false)
        found("notifications", Screen.Notifications)
        found("bigger text", Screen.General); found("text size", Screen.General); found("animation", Screen.General)
        found("weather", Screen.PrivacyCheck); found("hidden route", Screen.PrivacyCheck)
        found("rules", Screen.GroundRules); found("other voices", Screen.Voices); found("voice", Screen.Voice)
    }

    @Test fun notificationsIsItsOwnRowOnTheSettingsHomeAndLookAndSizeHasNoAlerts() {
        start()
        row("Notifications")
        assertEquals(Screen.Notifications, nav.current)
        nav.back(); compose.waitForIdle()
        row("Look and size")
        compose.onNodeWithText("ALERTS").assertDoesNotExist()
    }

    @Test fun yourPcRowOpensItsPageWithPairingHelpAndDisconnectAndBackGoesToSettings() {
        start()
        row("Your PC")
        assertEquals(Screen.PhonePc, nav.current)
        compose.onNodeWithText("New phones are paired from Mímir on the PC.").assertIsDisplayed()
        compose.onNodeWithText("Pair another phone").assertDoesNotExist()
        compose.onNodeWithText("Disconnect this phone").performScrollTo().assertIsDisplayed()
        nav.back(); compose.waitForIdle()
        assertEquals(Screen.Settings, nav.current)
    }

    @Test fun privacyHoldsTheLookupSwitchesAndWhoAnswersDoesNot() {
        start()
        row("Privacy")
        compose.onNodeWithText(LOOKUPS_SWITCH).performScrollTo().assertIsDisplayed()
        nav.resetTo(Screen.Chat); nav.open(Screen.Settings); nav.open(Screen.Models); compose.waitForIdle()
        compose.onNodeWithText(LOOKUPS_SWITCH).assertDoesNotExist()
        compose.onNodeWithText("Rules for every chat").performScrollTo().assertIsDisplayed()
    }

    @Test fun voiceChoicesAreOnlyUnderVoice() {
        start()
        nav.open(Screen.Models); compose.waitForIdle()
        compose.onNodeWithText("Read aloud on this phone", substring = true).assertDoesNotExist()
        nav.resetTo(Screen.Chat); nav.open(Screen.Settings); nav.open(Screen.Voice); compose.waitForIdle()
        compose.onNodeWithText("Other voices").performScrollTo().assertIsDisplayed()
    }

    @Test fun appUpdatesAlwaysGivesAResult() {
        assertEquals("You have the newest version (1.0).", updateResultWords(UpdateResult.Newest("1.0")))
        assertEquals("Version 2.0 is ready.", updateResultWords(UpdateResult.Ready("2.0")))
        assertEquals("Couldn't check just now.", updateResultWords(UpdateResult.Couldnt))
        val s = ExampleData.awake()
        assertTrue(updateResult(s.copy(paired = false)) is UpdateResult.Couldnt)
        assertTrue(updateResult(s.copy(pcVersion = "9.9")) is UpdateResult.Ready)
    }

    @Test fun modelNamesAreSaidInPlainWords() {
        assertEquals("Qwen 3.6 coding", plainModelName("qwen3.6:35b-a3b-coding"))
        assertEquals("Qwen 3.6 uncensored", plainModelName("huihui_ai/Qwen3.6-abliterated:35b-a3b"))
        assertEquals("Claude Sonnet", plainModelName("Claude Sonnet"))
        assertFalse(plainModelName("qwen3.6:35b-a3b-coding").contains(":"))
    }
}
