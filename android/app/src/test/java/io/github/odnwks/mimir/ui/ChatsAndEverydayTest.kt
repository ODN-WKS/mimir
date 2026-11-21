package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import io.github.odnwks.mimir.data.ChatEntry
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.FilingKind
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.LookupLog
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.online.Place
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

/**
 * The paired chat answers everyday messages directly (owner's screenshot, 2026-11-19), and every
 * chat's eye opens Chats, New chat and New Whisper; a swipe in from the left edge opens Chats too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w339dp-h740dp-xhdpi")
class ChatsAndEverydayTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var station: FakeStation
    private lateinit var engine: Engine
    private val web = FakeWeb()

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
        LookupLog.clear()
    }

    @After fun tearDown() = Online.set(Online.Settings())

    private fun quiet(): StationState = ExampleData.awake().let { s ->
        s.copy(incognito = null, chat = emptyList(), projectChoices = listOf("Recipe-Box", "Recipe box", "S8 unlock"))
    }

    private fun start(mimir: Boolean, data: StationState = quiet()) {
        station = FakeStation(data)
        engine = testEngine(tmp.newFolder(), ready = mimir)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, nav) } }
        }
        compose.waitForIdle()
    }

    private fun type(text: String) {
        compose.onNodeWithContentDescription("Message Mímir").performTextInput(text)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) { Thread.sleep(10); compose.waitForIdle() }
        compose.waitForIdle()
    }

    private fun replies() = station.state.value.chat.filterIsInstance<ChatEntry.Reply>().flatMap { it.paragraphs }

    @Test fun theOwnersWeatherMessagesGetTheForecastNotWhichProject() {
        Online.set(Online.Settings(place = Place("Portland, Oregon, United States", 45.52, -122.68)))
        web.geocode = """{"results":[{"name":"Portland","admin1":"Oregon","country":"United States","latitude":45.5152,"longitude":-122.6784}]}"""
        start(mimir = false)
        type("good morning what's the weather forecast for today")
        waitFor { replies().any { it.startsWith("From open-meteo.com") } }
        type("what is the weather forecast for Portland")
        waitFor { replies().count { it.startsWith("From open-meteo.com") } == 2 }
        val chat = station.state.value.chat
        // Neither went to the PC to be filed, so nothing asks "Which project?".
        assertTrue(chat.filterIsInstance<ChatEntry.Mine>().all { it.filing == null })
        assertTrue(chat.none { it is ChatEntry.Mine && it.filing?.kind == FilingKind.Inbox })
        compose.onNodeWithText("Which project?").assertDoesNotExist()
        assertEquals(2, replies().count { it.startsWith("Weather for Portland, Oregon, United States") })
        assertTrue(replies().any { "Today" in it && "light rain" in it })
        assertTrue(web.asked.any { "geocoding-api" in it && "Portland" in it })
    }

    @Test fun anEverydayQuestionGoesToMimirAndProjectWorkStillGoesToThePc() {
        start(mimir = true)
        type("who wrote Hamlet?")
        waitFor { !engine.state.value.writing && engine.state.value.chat.turns.size >= 2 }
        assertEquals(Screen.Standalone, nav.current)
        assertEquals("who wrote Hamlet?", engine.state.value.chat.turns.first().text)
        assertTrue(station.state.value.chat.isEmpty())
        nav.back(); compose.waitForIdle()
        type("Fix the crash in Recipe box when it starts")
        assertEquals(FilingKind.NewTask, (station.state.value.chat.last() as ChatEntry.Mine).filing?.kind)
    }

    /** Findability: someone wanting a new chat taps New chat in the side menu; the old one is listed there. */
    @Test fun aNewChatIsInTheMenuAndTheOldOneIsListedThere() {
        start(mimir = false, data = quiet().copy(chat = listOf(ChatEntry.Mine("m1", "Make the tablet layout faster", null), ChatEntry.Reply("r1", listOf("On it."), ""))))
        compose.menuRow(MENU_NEW_CHAT)
        assertTrue(station.state.value.chat.isEmpty())
        compose.onNodeWithText("Make the tablet layout faster").assertDoesNotExist()
        // "Where are my old chats?": the menu's Chats section, and All chats opens the whole list.
        nav.open(Screen.Chats); compose.waitForIdle()
        compose.onNode(hasText(EYE_CHATS) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        compose.onNodeWithText("tablet layout faster", substring = true).assertIsDisplayed()
        compose.onNodeWithText("With your PC", substring = true).assertIsDisplayed()
        // A tap opens it again in the chat.
        compose.onNodeWithText("tablet layout faster", substring = true).performClick(); compose.waitForIdle()
        assertEquals(Screen.Chat, nav.current)
        compose.onNodeWithText("Make the tablet layout faster").assertIsDisplayed()
    }

    /** Findability: a swipe in from the left edge opens the side menu (its Chats section lists the earlier chats). */
    @Test fun aSwipeFromTheLeftEdgeOpensTheMenu() {
        start(mimir = true)
        // From the screen's own edge (the mic now sits at the message box's start).
        compose.onRoot().performTouchInput {
            swipeRight(startX = 2f, endX = centerX + width / 3f)
        }
        compose.waitForIdle()
        compose.onNodeWithText(MENU_SEARCH).assertIsDisplayed()
        compose.onNodeWithText(MENU_CHATS.uppercase()).assertIsDisplayed()
    }

    /** Mímir's chats and the PC chat's are in one list, labelled; a Whisper is never in it. */
    @Test fun chatsListsBothKindsLabelledAndNeverAWhisper() {
        start(mimir = true)
        nav.open(Screen.Standalone); compose.waitForIdle()
        engine.send("Give me three easy dinner ideas")
        waitFor { !engine.state.value.writing && engine.state.value.chat.turns.size >= 2 }
        compose.onNodeWithContentDescription(START_WHISPER).performClick(); compose.waitForIdle()
        compose.onNodeWithText(EYE_NEW_WHISPER).performClick(); compose.waitForIdle()
        assertTrue(engine.state.value.whisper)
        compose.onNodeWithContentDescription(END_WHISPER).performClick(); compose.waitForIdle()
        compose.onNodeWithText(EYE_CHATS).performClick(); compose.waitForIdle()
        assertEquals(Screen.Chats, nav.current)
        compose.onNodeWithText("Three easy dinner ideas", substring = true).assertIsDisplayed()
        compose.onNodeWithText(AI_NAME, substring = true).assertIsDisplayed()
        // The Whisper went on, but it isn't listed.
        assertTrue(engine.state.value.whisper)
        compose.onNodeWithText("A Whisper", substring = true).assertDoesNotExist()
        assertFalse(engine.state.value.earlier.any { it.title.contains("Whisper") })
    }

    /** Findability: a Whisper with internet is a press and hold on the menu's Whisper row; the top bar then shows "via Tor". */
    @Test fun aWhisperWithInternetIsOnTheWhisperRowsLongPressAndShowsItsMode() {
        start(mimir = true, data = quiet().copy(paired = false))
        compose.menuRowHold(MENU_WHISPER)
        assertTrue(engine.state.value.whisper && engine.state.value.web)
        compose.onNodeWithContentDescription(END_WHISPER_BUTTON).assertIsDisplayed()
        compose.onNodeWithText("Lookups go by a hidden route", substring = true).assertIsDisplayed()
        compose.onNodeWithText("A $WHISPER_O uses a hidden route").assertExists()
        // A tap on the top bar ends it.
        compose.onNodeWithContentDescription(END_WHISPER_BUTTON).performClick(); compose.waitForIdle()
        assertFalse(engine.state.value.whisper)
        assertFalse(engine.state.value.web)
    }
}
