package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.data.PairProgress
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.standalone.LocalState
import io.github.odnwks.mimir.standalone.Turn
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

/** 0.07 F4: the QA11 phone-rest findings (pairing, chat, Whisper, Who answers, settings), one test each. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class F4HuntFixesTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun gone(text: String) = assertEquals("'$text' must not show", 0, compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)

    /** R1: the PC dropped mid-answer: one line says so; neither "Waking your PC's model..." nor "Waiting for ..." stays. */
    @Test fun r1PcAwayMidChatSaysOneThing() {
        val e = testEngine(tmp.newFolder(), ready = true)
        val s: LocalState = e.state.value
        val away = s.copy(writing = true, onPc = true, pcWaiting = true, pcLine = "Waking your PC's model…")
        assertEquals(PC_AWAY_WORDS, writingWords(away))
        assertEquals("Your PC is away. Your message is kept and sends when it's back.", PC_AWAY_WORDS)
        // With the PC reachable again its own line shows.
        assertEquals("Waking your PC's model…", writingWords(away.copy(pcWaiting = false)))
        // With words already written they stay, and the away line sits under them (not the same text twice).
        assertEquals("Once upon", writingWords(away.copy(partial = "Once upon")))
    }

    /** R2/R3: with a PC paired, Settings > Who answers never says "You can still chat..." or talks of the phone's memory. */
    @Test fun r3PairedPcNeverGetsThePhoneLimitTalk() {
        compose.setContent { MimirTheme { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { OnlineStore(canRun = false, ramBytes = 4_000_000_000L, nav = Navigator(), paired = true) } } }
        compose.waitForIdle()
        gone(ONLINE_STILL_CHAT); gone("too little"); gone("GB memory"); gone(ONLINE_CONNECT_PC)
    }

    /** R2: the sheet with the PC away says so on the PC card, and Online (through the PC) is not offered. */
    @Test fun r2WhoSheetWhilePcAway() {
        val away = whoCards(Answerer.Pc, phoneCan = false, paired = true, claudeOffered = true, pcOnline = false)
        assertEquals(listOf("This phone", "Your PC"), away.map { it.title })
        assertEquals(WHO_PC_AWAY, away.first { it.who == Answerer.Pc }.about)
        assertTrue(away.first { it.who == Answerer.Pc }.chosen)
        val here = whoCards(Answerer.Pc, phoneCan = false, paired = true, claudeOffered = true, pcOnline = true)
        assertEquals(listOf("This phone", "Your PC", "Online"), here.map { it.title })
        assertEquals(WHO_PC_ABOUT, here.first { it.who == Answerer.Pc }.about)
        // Online already chosen stays visible, so the choice is never hidden.
        assertTrue(whoCards(Answerer.Claude, true, true, true, pcOnline = false).any { it.who == Answerer.Claude && it.chosen })
    }

    /** R4: exactly one of the three groups is chosen, by what really answers. */
    @Test fun r4ChosenChipIsTheOneAnswering() {
        assertEquals("phone", answeringGroup(paired = false, chatsOnline = false, onlineOnly = false))
        assertEquals("free", answeringGroup(paired = true, chatsOnline = false, onlineOnly = false))
        assertEquals("claude", answeringGroup(paired = true, chatsOnline = true, onlineOnly = false))
        assertEquals("claude", answeringGroup(paired = true, chatsOnline = false, onlineOnly = true))
    }

    /** R5: Storage never says a model will come when the phone can't run one. */
    @Test fun r5StorageNoModelLineOnlyForAPhoneThatCanRunOne() {
        assertNull(noModelWords(phoneCan = false, noneHere = true))
        assertNull(noModelWords(phoneCan = true, noneHere = false))
        assertTrue(noModelWords(phoneCan = true, noneHere = true)!!.startsWith("No model is downloaded"))
    }

    private fun pairScreen(): Navigator {
        val nav = Navigator().also { it.pastePairCode = true }
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { MimirTheme(reduceMotion = true) { PairScreen(station, nav) } }
        compose.waitForIdle()
        return nav
    }

    /** R6: in paste mode the steps talk of copying and pasting, not a camera or a square code. */
    @Test fun r6PasteStepsNameNoCamera() {
        pairScreen()
        CONNECT_STEPS_PASTE.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        gone("camera"); gone("square")
        assertEquals(1, compose.onAllNodesWithText("Paste the code", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** R7: Pair with an empty box says why; typing clears it. */
    @Test fun r7PairWithAnEmptyBoxSaysWhy() {
        pairScreen()
        gone(CODE_FIRST)
        compose.onNodeWithText("Pair").performClick(); compose.waitForIdle()
        compose.onNodeWithText(CODE_FIRST).assertIsDisplayed()
    }

    /** R8: a typed text that isn't a code: no camera or words cards, a plain next step. */
    @Test fun r8WrongCodeShowsOnlyWhatApplies() {
        assertTrue(badCodeTyped("That isn't a Mímir pairing code."))
        assertTrue(otherProblems("That isn't a Mímir pairing code.").isEmpty())
        assertEquals(3, otherProblems("The PC said the words don't match.").size)
        val station = FakeStation(LinkStation.empty("test").copy(paired = false)).also { it.pairEnded(PairProgress.Failed("That isn't a Mímir pairing code.")) }
        compose.setContent { MimirTheme(reduceMotion = true) { PairErrorScreen(station.state.value, Navigator()) } }
        compose.waitForIdle()
        gone("Here's what to try"); gone("Camera not allowed"); gone("The words didn't match"); gone("Scan the new code")
        compose.onNodeWithText("That isn't a Mímir pairing code.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    /** R9 (manifest): the scanner follows how the phone is held; no forced landscape. */
    @Test fun r9ScannerIsNotForcedSideways() {
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.map { java.io.File(it, "app/src/main/AndroidManifest.xml") }.first { it.exists() }
        val m = dir.readText()
        val i = m.indexOf("com.journeyapps.barcodescanner.CaptureActivity")
        assertTrue("the scanner's screen is declared", i > 0)
        assertTrue(m.substring(i, m.indexOf("/>", i)).contains("fullSensor"))
    }

    /** R18: a Whisper started from a chat with lines in it shows none of them. */
    @Test fun r18WhisperShowsNoEarlierChatLines() {
        val engine = testEngine(tmp.newFolder(), ready = true, turns = listOf(Turn(true, "Say hi"), Turn(false, "Hi there friend.")))
        engine.newChat()
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, Navigator()) } } }
        compose.waitForIdle()
        engine.startWhisper(); compose.waitForIdle()
        gone("Say hi"); gone("Hi there friend.")
        compose.onNodeWithText(WHISPER_CARD_TITLE).assertIsDisplayed()
    }

    /** R10: "Include the last checks" is never On beside "(not run yet)": off, with the way to turn it on. */
    @Test fun r10LabsChecksSwitchNeedsChecks() {
        val nav = Navigator()
        compose.setContent { MimirTheme(reduceMotion = true) { LabsScreen(nav) } }
        compose.waitForIdle()
        compose.onNodeWithText("Run the checks above first.").assertIsDisplayed()
        gone("(not run yet)")
    }

    /** R11: no milliseconds anywhere in the checks. */
    @Test fun r11ChecksSayNoMilliseconds() {
        assertEquals("under a second", io.github.odnwks.mimir.labs.Checks.words(12))
        assertEquals("2 s", io.github.odnwks.mimir.labs.Checks.words(2_400))
        assertEquals("31 s", io.github.odnwks.mimir.labs.Checks.words(31_000))
    }

    /** R12: the menu's Chats section with no chats says so. */
    @Test fun r12EmptyChatsSaysSo() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, Navigator()) } } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(MENU_BUTTON, substring = true).performClick(); compose.waitForIdle()
        compose.onNodeWithText(MENU_NO_CHATS).assertIsDisplayed()
    }

    /** R15/R16/R19: one name for each thing. */
    @Test fun r15r16r19Names() {
        assertEquals("Keep the app up to date", APP_UPDATES_SWITCH)
        assertTrue("Ingrid is a voice", SETTINGS_VOICE_LINE.contains("voice"))
        assertFalse(MENU_UPDATES == APP_UPDATES_SWITCH)
    }

    /** R20: under half an answer, no Listen or Copy. */
    @Test fun r20NoButtonsUnderAnAnswerBeingWritten() {
        compose.setContent { MimirTheme(reduceMotion = true) { Column { Reply("Half an ans", dim = false, buttons = false); Reply("A whole one.", dim = false) } } }
        compose.waitForIdle()
        // P13: buttons under the whole answer only; a half answer has no hold menu either, a whole one has.
        assertEquals(1, compose.onAllNodesWithText(LISTEN, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Half an ans", substring = true).onLast().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Copy", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onAllNodesWithText("A whole one.", substring = true).onLast().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitForIdle()
        // The visible Copy and the menu's Copy.
        assertEquals(2, compose.onAllNodesWithText("Copy", useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
