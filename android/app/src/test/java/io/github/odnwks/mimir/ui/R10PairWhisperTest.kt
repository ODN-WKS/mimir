package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.data.PairProgress
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
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

/** Redesign R10: Connect your PC, the four words, and a Whisper that looks different. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class R10PairWhisperTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun connectYourPcIsThreePlainStepsAndOneButton() {
        assertEquals(
            listOf(
                "On your PC, open $AI_NAME and go to Settings › Phones.",
                "Click Pair a phone. A square code appears.",
                "Point this phone's camera at it.",
            ),
            CONNECT_STEPS,
        )
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { MimirTheme(reduceMotion = true) { PairScreen(station, Navigator()) } }
        compose.waitForIdle()
        compose.onNodeWithText(CONNECT_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Once connected, this phone reaches your PC from anywhere. It stays private, with no account to make.").assertIsDisplayed()
        CONNECT_STEPS.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Scan the code").assertIsDisplayed()
        compose.onNodeWithText("Can't scan? Paste the code instead").assertIsDisplayed()
    }

    @Test fun theUnpairedYourPcPageUsesTheSameSteps() {
        assertEquals(CONNECT_STEPS, YOUR_PC_STEPS)
    }

    @Test fun typingTheCodeOpensTheBoxAndPair() {
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { MimirTheme(reduceMotion = true) { PairScreen(station, Navigator()) } }
        compose.onNodeWithText(CONNECT_TYPE_INSTEAD).performClick()
        compose.onNodeWithContentDescription("Pairing code").assertIsDisplayed()
        compose.onNodeWithText("Pair").assertIsDisplayed()
    }

    @Test fun theFourWordsSitInAGridOfCards() {
        val words = listOf("amber", "river", "stone", "lantern")
        val state = ExampleData.awake().copy(pairing = PairProgress.ConfirmOnPc(words))
        compose.setContent { MimirTheme(reduceMotion = true) { PairDoneScreen(state, Navigator()) } }
        compose.waitForIdle()
        compose.onNodeWithText("Almost there").assertIsDisplayed()
        compose.onNodeWithText("Your PC is showing four words. Are they the same as these?").assertIsDisplayed()
        val b = words.map { compose.onNodeWithText(it).also { n -> n.assertIsDisplayed() }.fetchSemanticsNode().boundsInRoot }
        // 2 x 2: the first pair share a row, the second pair the next; the columns line up.
        assertEquals(b[0].top, b[1].top, 1f)
        assertEquals(b[2].top, b[3].top, 1f)
        assertTrue(b[2].top > b[0].bottom)
        assertEquals(b[0].left, b[2].left, 1f)
        assertEquals(b[1].left, b[3].left, 1f)
        compose.onNodeWithText("If they match, click \"Yes, they match\" on your PC. Matching words mean nobody is in between.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun theWhisperCardSaysWhatIsTrueForEachMode() {
        assertEquals("When you tap End, this chat is forgotten on this phone and on your PC. This Whisper doesn't use the internet.", whisperCardBody(web = false, paired = true))
        assertEquals("When you tap End, this chat is forgotten on this phone and on your PC. Lookups go by a hidden route, so websites see less about who is asking.", whisperCardBody(web = true, paired = true))
        assertFalse(whisperCardBody(web = false, paired = false).contains("Tor"))
        assertFalse(whisperCardBody(web = false, paired = true).contains("Tor"))
        assertEquals(0xFF0B1513.toInt(), WhisperTint.Ground.value.shr(32).toInt())
    }

    @Test fun theSwitchTurnsTheInternetOnAndStartsOff() {
        var web by mutableStateOf(false)
        compose.setContent { MimirTheme(reduceMotion = true) { WhisperCard(web, paired = true) { web = it } } }
        compose.onNodeWithText(WHISPER_CARD_TITLE).assertIsDisplayed()
        compose.onNodeWithText("This Whisper doesn't use the internet.", substring = true).assertIsDisplayed()
        compose.onNodeWithText(WHISPER_NET_SWITCH).performClick()
        compose.waitForIdle()
        assertTrue(web)
        compose.onNodeWithText("Lookups go by a hidden route", substring = true).assertIsDisplayed()
    }

    @Test fun theTopBarEndsAtOneTap() {
        var ended = 0
        compose.setContent { MimirTheme(reduceMotion = true) { WhisperTopBar(onEnd = { ended++ }, waiting = 0) } }
        compose.onNodeWithText("Whisper").assertIsDisplayed()
        compose.onNodeWithText("End").performClick()
        assertEquals(1, ended)
    }

    @Test fun aRealWhisperShowsTheCardAndEndEndsIt() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, Navigator()) } } }
        compose.waitForIdle()
        engine.startWhisper()
        compose.waitForIdle()
        compose.onNodeWithText(WHISPER_CARD_TITLE).assertIsDisplayed()
        compose.onNodeWithText("This Whisper doesn't use the internet.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Whisper").assertIsDisplayed()
        compose.onNodeWithText("End").performClick()
        compose.waitForIdle()
        assertFalse(engine.state.value.whisper)
        compose.onNodeWithText(WHISPER_CARD_TITLE).assertDoesNotExist()
    }
}
