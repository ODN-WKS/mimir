package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.online.LookupLog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The redesigned Settings: its list, its footer, each page's sections, and that nothing became unreachable. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class R9SettingsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var engine: Engine
    private lateinit var fake: FakeStation

    @After fun tidy() { LookupLog.clear() }

    private fun start(paired: Boolean = true) {
        val station = FakeStation(if (paired) ExampleData.awake() else io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        fake = station
        engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, nav) } } }
        compose.waitForIdle()
        nav.open(Screen.Settings); compose.waitForIdle()
    }

    private fun open(row: String) { compose.onNodeWithText(row).performScrollTo().performClick(); compose.waitForIdle() }

    @Test fun theFooterHoldsTheNameAndVersionAndNoOtherPageShowsThem() {
        start()
        compose.onNodeWithText(AI_NAME).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("version ", substring = true).performScrollTo().assertIsDisplayed()
        for (r in listOf("Voice", "Privacy", "Look and size", "Storage")) {
            nav.resetTo(Screen.Chat); nav.open(Screen.Settings); compose.waitForIdle()
            open(r)
            compose.onNodeWithText("version ", substring = true).assertDoesNotExist()
        }
    }

    @Test fun unpairedTheYourPcRowSaysConnectItAndOpensPairing() {
        start(paired = false)
        compose.onNodeWithText("Not connected · connect it").performClick(); compose.waitForIdle()
        assertEquals(Screen.Pair, nav.current)
    }

    @Test fun everyOldSettingIsStillFoundBySearchAndReachableByAPlace() {
        // Search: each old place still leads where it did.
        val wanted = mapOf(
            "models" to Screen.Models, "rules" to Screen.GroundRules, "weather" to Screen.PrivacyCheck, "voices" to Screen.Voices,
            "text size" to Screen.General, "notifications" to Screen.Notifications, "relay" to Screen.PhonePc,
            "privacy" to Screen.PrivacyCheck, "storage" to Screen.Storage, "version" to Screen.About, "help" to Screen.Help,
            "voice typing" to Screen.Voice,
        )
        wanted.forEach { (q, s) -> assertTrue("$q -> $s", findSettings(q, paired = true).any { it.screen == s }) }
        // Places: each thing has one row and one home (see F2MovedItemsTest for the moved ones).
        start()
        open("Notifications"); compose.onNodeWithText("Notifications").assertIsDisplayed()
        nav.resetTo(Screen.Chat); nav.open(Screen.Settings); compose.waitForIdle()
        open("Privacy")
        compose.onNodeWithText("Phones").assertDoesNotExist()
        compose.onNodeWithText("Rules for every chat", substring = true).assertDoesNotExist()
    }

    @Test fun searchFindsTheNewNames() {
        start()
        compose.onNodeWithContentDescription("Search settings").performTextReplacement("dark")
        compose.waitForIdle()
        compose.onNodeWithText("Look and size").assertIsDisplayed()
    }

    @Test fun voiceHasReadingAloudAndTalkingSectionsAndAWordForTheSpeed() {
        start()
        open("Voice")
        compose.onNodeWithText("READING ALOUD").assertIsDisplayed()
        compose.onNodeWithText("Ingrid").assertIsDisplayed()
        compose.onNodeWithText("A natural voice, built into the app").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play a sample of Ingrid").assertExists()
        compose.onNodeWithText("Speed").assertIsDisplayed()
        compose.onNodeWithTag("speed-word").assertTextEquals(speedWord(io.github.odnwks.mimir.audio.ReadSpeed.snap(fake.state.value.player.speed)))
        compose.onNodeWithText("TALKING INSTEAD OF TYPING").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Microphone button").assertIsDisplayed()
        assertEquals("Slower", speedWord(0.85f)); assertEquals("Faster", speedWord(1.2f)); assertEquals("Normal", speedWord(1f))
    }

    @Test fun privacyShowsTodaysLookupsWhatWentOutAndHowItWorks() {
        val now = System.currentTimeMillis()
        LookupLog.add(LookupLog.Entry(now, "open-meteo.com", "weather in Leeds", LookupLog.Route.Phone, 900), io.github.odnwks.mimir.online.ChatMode.Normal)
        start()
        open("Privacy")
        compose.onNodeWithText("1 lookup today").assertIsDisplayed()
        compose.onNodeWithText("Everything else stayed between this phone and your PC.").assertIsDisplayed()
        compose.onNodeWithText(PRIVACY_WENT_OUT).assertIsDisplayed()
        compose.onNodeWithText("“weather in Leeds”").assertIsDisplayed()
        compose.onNodeWithText("to open-meteo.com", substring = true).assertIsDisplayed()
        compose.onNodeWithText(PRIVACY_HOW).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your chats").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("When online answers").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(CLEAR_THIS_LIST).performScrollTo().performClick(); compose.waitForIdle()
        assertTrue(LookupLog.entries.value.isEmpty())
        compose.onNodeWithText("0 lookups today").performScrollTo().assertIsDisplayed()
    }

    @Test fun unpairedPrivacySaysEverythingStayedOnThisPhone() {
        start(paired = false)
        open("Privacy")
        compose.onNodeWithText("Everything else stayed on this phone.").assertIsDisplayed()
        assertEquals("Everything else stayed on this phone.", stayedWords(false))
    }

    @Test fun lookupsTodayLeavesOutYesterday() {
        val now = System.currentTimeMillis()
        val e = { at: Long -> LookupLog.Entry(at, "x.org", "w", LookupLog.Route.Phone, 1) }
        assertEquals(1, lookupsToday(listOf(e(now), e(now - 2 * 86_400_000L)), now).size)
        assertEquals("3 lookups today", lookupsTodayWords(3))
    }

    @Test fun storageHasItsCardRowsAndTheCleanUpNote() {
        start()
        open("Storage")
        compose.onNodeWithText("used by $AI_NAME", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Ingrid's voice").assertIsDisplayed()
        compose.onNodeWithText("Needed").assertIsDisplayed()
        compose.onNodeWithText("cleans up after itself", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun lookAndSizeHasTheSizeSliderPreviewAndDarkOnly() {
        start()
        open("Look and size")
        compose.onNodeWithText("TEXT SIZE").assertIsDisplayed()
        compose.onNodeWithContentDescription("App size").assertIsDisplayed()
        compose.onNodeWithText("Give me three easy dinner ideas").assertIsDisplayed()
        compose.onNodeWithText("COLORS").assertIsDisplayed()
        compose.onNodeWithText("Dark").assertIsDisplayed()
        compose.onNodeWithText("Light").assertDoesNotExist()
    }

    @Test fun helpAnswersNeverOfferRemovingAPhoneFromAPhoneAndHaveNoLostCard() {
        assertEquals(7, HELP_TOPICS.size)
        assertEquals("Pictures you send go to your PC (or to Anthropic when Online answers) and are kept with that chat.", helpAnswer("pictures", true))
        assertEquals("You remove it at your PC: $AI_NAME › Settings › Phones.", helpAnswer("lost", true))
        assertFalse(HELP_TOPICS.any { helpAnswer(it.id, true).isEmpty() })
        start()
        open("Help and about")
        compose.onNodeWithText("Lost a phone?").assertDoesNotExist()
    }
}
