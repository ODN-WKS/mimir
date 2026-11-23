package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner, 2026-11-19: paired, the chat had no Whisper eye like the phone's own chat. Now it has one,
 * in the same place, and it starts a Whisper on this phone or on the PC, whichever there is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w339dp-h740dp-xhdpi")
class PairedWhisperTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var station: FakeStation
    private lateinit var engine: Engine

    /** The example PC, with no Whisper task already running. */
    private fun quiet() = ExampleData.awake().let { s -> s.copy(incognito = null, chat = s.chat.filterNot { it is io.github.odnwks.mimir.data.ChatEntry.IncognitoRunning }) }

    private fun start(mimir: Boolean, pcFree: Boolean, data: (StationState) -> StationState = { it }) {
        station = FakeStation(data(quiet()).let { if (pcFree) it else it.copy(models = it.models.copy(local = emptyList())) })
        engine = testEngine(tmp.newFolder(), ready = mimir)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, nav) } }
        }
        compose.waitForIdle()
    }

    private fun eye(label: String = whoLabel("Your PC")) = compose.onNodeWithContentDescription(label)

    @Test fun withBothItAsksWhereAndEachChoiceStartsItsWhisper() {
        start(mimir = true, pcFree = true)
        assertTrue(station.state.value.models.local.isNotEmpty())
        compose.menuRow(MENU_WHISPER)
        compose.onNodeWithText(WHISPER_ON_PHONE).assertIsDisplayed()
        compose.onNodeWithText(WHISPER_ON_PC).assertIsDisplayed().performClick(); compose.waitForIdle()
        // On the PC: the chat itself turns teal and the next message goes as a Whisper task.
        assertTrue(nav.composeTo.whisper)
        assertEquals(Screen.Chat, nav.current)
        compose.onNodeWithText("Whisper to your PC's free model").assertExists()
        // The eye again stops it, before anything is sent.
        eye(END_PC_WHISPER).performClick(); compose.waitForIdle()
        assertFalse(nav.composeTo.whisper)
        // On this phone: Mímir's chat opens, in a Whisper.
        compose.menuRow(MENU_WHISPER)
        compose.onNodeWithText(WHISPER_ON_PHONE).performClick(); compose.waitForIdle()
        assertTrue(engine.state.value.whisper)
        assertEquals(Screen.Standalone, nav.current)
        assertFalse(nav.composeTo.whisper)
    }

    @Test fun withOnlyThePcsFreeModelItStartsThereAtOnce() {
        start(mimir = false, pcFree = true)
        compose.menuRow(MENU_WHISPER)
        compose.onNodeWithText(WHISPER_ON_PHONE).assertDoesNotExist()
        assertTrue(nav.composeTo.whisper)
        assertFalse(engine.state.value.whisper)
    }

    @Test fun withOnlyMimirItStartsOnThisPhoneAtOnce() {
        start(mimir = true, pcFree = false)
        compose.menuRow(MENU_WHISPER)
        compose.onNodeWithText(WHISPER_ON_PC).assertDoesNotExist()
        assertTrue(engine.state.value.whisper)
        assertEquals(Screen.Standalone, nav.current)
        assertFalse(nav.composeTo.whisper)
    }

    @Test fun withNeitherItSaysWhatIsNeededAndWhereToGetIt() {
        start(mimir = false, pcFree = false)
        compose.menuRow(MENU_WHISPER)
        compose.onNodeWithText("A Whisper needs a private model").assertIsDisplayed()
        assertFalse(nav.composeTo.whisper)
        assertFalse(engine.state.value.whisper)
        compose.onNodeWithText("Add a model on your PC").assertIsDisplayed()
        compose.onNodeWithText("Get $AI_NAME").assertIsDisplayed().performClick(); compose.waitForIdle()
        assertEquals(Screen.Models, nav.current)
    }

    @Test fun aWhisperTaskAlreadyRunningOnThePcIsOneTapAway() {
        start(mimir = true, pcFree = true) { ExampleData.awake() }
        eye(OPEN_PC_WHISPER).performClick(); compose.waitForIdle()
        assertEquals(Screen.IncognitoTask, nav.current)
    }

    /** The who-answers label is centred and keeps its row: its words change width, so its middle and top must not move. */
    private fun assertSamePlace(a: androidx.compose.ui.geometry.Rect, b: androidx.compose.ui.geometry.Rect) {
        assertEquals(a.center.x, b.center.x, 1f)
        assertEquals(a.top, b.top, 1f)
        assertEquals(a.bottom, b.bottom, 1f)
    }

    @Test fun theEyeNeverMoves() {
        val awake = FakeStation(quiet())
        val away = FakeStation(quiet().let { it.copy(pc = it.pc.copy(online = false)) })
        var which by androidx.compose.runtime.mutableStateOf(awake)
        station = awake
        engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(which, nav) } }
        }
        compose.waitForIdle()
        val at = eye().fetchSemanticsNode().boundsInRoot
        compose.menuRow(MENU_WHISPER)
        compose.onNodeWithText(WHISPER_ON_PC).performClick(); compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
        // Whispering, it sits in its teal circle in the same place.
        assertSamePlace(at, eye(END_PC_WHISPER).fetchSemanticsNode().boundsInRoot)
        eye(END_PC_WHISPER).performClick(); compose.waitForIdle()
        // The PC away: a line shows beside the dot, and the eye still keeps its place.
        which = away; compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
        assertSamePlace(at, eye().fetchSemanticsNode().boundsInRoot)
    }

    @Test fun settingsSearchFindsWhisperPairedAndItStartsOne() {
        start(mimir = true, pcFree = true)
        nav.open(Screen.Settings); compose.waitForIdle()
        compose.onNodeWithContentDescription("Search settings").performTextReplacement("whisper")
        compose.waitForIdle()
        compose.onNodeWithText(whisperExplained(true)).assertIsDisplayed()
        compose.onNodeWithText("Start a Whisper").performClick(); compose.waitForIdle()
        assertEquals(Screen.Chat, nav.current)
        compose.onNodeWithText(WHISPER_ON_PC).performClick(); compose.waitForIdle()
        assertTrue(nav.composeTo.whisper)
    }
}
