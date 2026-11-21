package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.LookupLog
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mímir online on screen: the place question, the sources line, the settings and the Privacy check. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w339dp-h740dp-xhdpi")
class OnlineFindabilityTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var engine: Engine
    private val web = FakeWeb()

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
        LookupLog.clear()
        io.github.odnwks.mimir.online.Search.forget()
        engine = testEngine(tmp.newFolder(), ready = true)
        val unpaired = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        compose.waitForIdle()
    }

    @After fun tearDown() { Online.set(Online.Settings()) }

    private fun settle() {
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        compose.waitForIdle()
    }

    @Test fun theWeatherAsksForAPlaceOnceAndTheAnswerSaysWhereItCameFrom() {
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("What is the weather forecast for today")
        compose.onNodeWithContentDescription("Send").performClick()
        settle()
        compose.onNodeWithText(PLACE_QUESTION).assertIsDisplayed()
        compose.onNodeWithText(ROUGH_ONCE).assertExists()
        compose.onNodeWithContentDescription("Town or city for the weather").performTextInput("Leeds")
        compose.onNodeWithText("Use this place").performClick()
        settle()
        compose.onNodeWithText(PLACE_QUESTION).assertDoesNotExist()
        compose.onNode(hasContentDescription("Source: open-meteo.com", substring = true)).assertIsDisplayed()
        // Every lookup is listed in the Privacy check, with the words that were sent.
        nav.open(Screen.Settings); compose.waitForIdle()
        compose.onNodeWithText("Privacy").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText(PRIVACY_WENT_OUT).performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("\u201cLeeds\u201d", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun searchFindsLookingThingsUpByWeatherInternetAndLocationAndItTurnsOff() {
        for (words in listOf("weather", "internet", "location", "online")) {
            nav.resetTo(Screen.Chat); nav.open(Screen.Settings); compose.waitForIdle()
            compose.onNodeWithContentDescription("Search settings").performTextReplacement(words)
            compose.waitForIdle()
            compose.onNodeWithText("Looking things up").performClick()
            compose.waitForIdle()
            assertEquals(Screen.PrivacyCheck, nav.current)
        }
        compose.onNodeWithText(LOOKUPS_SWITCH).performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        assertFalse(Online.settings.value.lookups)
        compose.onNodeWithText(LOOKUPS_SWITCH).assertIsOff()
        compose.onNodeWithText(WEATHER_PLACE).performScrollTo().assertIsDisplayed()
    }

    @Test fun theChatsPrivacyLineSaysLookupsSendOnlyTheWordsLookedUp() {
        compose.onNodeWithText("Your chats stay on this phone.", substring = true).assertIsDisplayed()
        Online.set(Online.Settings(lookups = false))
        nav.open(Screen.Settings); compose.waitForIdle(); nav.back(); compose.waitForIdle()
        assertTrue(privacyLine() == PRIVACY_LINE_OFFLINE)
    }

    @Test fun theFirstSearchWarnsOnceAndSearchingIsFoundAndTurnedOffInSettings() {
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("search for cheap flights to Oslo")
        compose.onNodeWithContentDescription("Send").performClick()
        settle()
        compose.onNodeWithText(SEARCH_QUESTION).assertIsDisplayed()
        compose.onNodeWithText(io.github.odnwks.mimir.online.Search.WARNING).assertIsDisplayed()
        compose.onNodeWithText(SEARCH_YES).performScrollTo().performClick()
        settle()
        compose.onNodeWithText(SEARCH_QUESTION).assertDoesNotExist()
        compose.onNode(hasContentDescription("Source: coindesk.com", substring = true)).assertIsDisplayed()
        // "duckduckgo" or "search the web" in Settings' search finds the switch.
        for (words in listOf("duckduckgo", "search the web")) {
            nav.resetTo(Screen.Chat); nav.open(Screen.Settings); compose.waitForIdle()
            compose.onNodeWithContentDescription("Search settings").performTextReplacement(words)
            compose.waitForIdle()
            compose.onNodeWithText("Looking things up").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText(SEARCH_SWITCH).performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        assertFalse(Online.settings.value.search)
    }

    @Test fun aBlockedSearchOffersItsResultsInTheAppsOwnBrowser() {
        Online.set(Online.Settings(searchAccepted = true))
        web.blocked = true
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("search for cheap flights to Oslo")
        compose.onNodeWithContentDescription("Send").performClick()
        settle()
        compose.onNodeWithText(OPEN_RESULTS).performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(Screen.Browse(io.github.odnwks.mimir.online.Search.resultsPage("cheap flights to Oslo")), nav.current)
    }

    @Test fun theEncyclopediasAreFoundByNameAndChosenInSettings() {
        for (words in listOf("wikipedia", "grokipedia", "encyclopedia")) {
            nav.resetTo(Screen.Chat); nav.open(Screen.Settings); compose.waitForIdle()
            compose.onNodeWithContentDescription("Search settings").performTextReplacement(words)
            compose.waitForIdle()
            compose.onNodeWithText("Looking things up").performClick()
            compose.waitForIdle()
            assertEquals(Screen.PrivacyCheck, nav.current)
        }
        compose.onNodeWithText(ENCYCLOPEDIAS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Both (compare)").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Wikipedia").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(io.github.odnwks.mimir.online.Encyclopedias.Wikipedia, Online.settings.value.encyclopedias)
    }

    @Test fun aLinkYouSendIsReadWithOneTapOnReadSiteUnderIt() {
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("is this true? https://news.example.com/bridge")
        compose.onNodeWithContentDescription("Send").performClick()
        settle()
        assertTrue(web.asked.none { "news.example.com" in it })
        compose.onNodeWithText(readWords("news.example.com")).performScrollTo().assertIsDisplayed().performClick()
        settle()
        assertTrue(web.asked.any { "news.example.com" in it })
        compose.onNode(hasContentDescription("Source: news.example.com", substring = true)).assertIsDisplayed()
        // Read once: the button goes.
        compose.onNodeWithText(readWords("news.example.com")).assertDoesNotExist()
    }
}
