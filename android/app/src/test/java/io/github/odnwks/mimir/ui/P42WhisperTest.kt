package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.nav.ChatAsk
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P42 (QA16 Q20, Q21): a Whisper on the PC opens empty, keeps nothing, and says so when it can't start. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P42WhisperTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var engine: Engine
    private val saved = listOf(Turn(true, "Give me three easy dinner ideas"), Turn(false, "Pasta with tomatoes."))
    private val started = mutableListOf<String>()

    private fun pcWhisper(pcOnline: Boolean = true, projects: Boolean = true, turns: List<Turn> = saved) {
        engine = testEngine(tmp.newFolder(), ready = false, turns = turns, supported = false)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) {
                MimirTheme(reduceMotion = true) {
                    PhoneChat(engine, nav, paired = true, pcOnline = pcOnline, pcWhisperFree = true, pcWhisperProjects = projects, startPcWhisper = { started += it; true })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun aWhisperOpenedFromASavedChatShowsNoneOfItsMessages_andEndLeavesTheChatAsItWas() {
        pcWhisper()
        compose.onNodeWithText("Give me three easy dinner ideas").assertIsDisplayed()
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("half a thought")
        nav.askChat(ChatAsk.StartWhisper); compose.waitForIdle()
        compose.onNodeWithText(WHISPER_CARD_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Give me three easy dinner ideas").assertDoesNotExist()
        compose.onNodeWithText("Pasta with tomatoes.").assertDoesNotExist()
        // The chat's own draft isn't in the Whisper's box, and the Whisper's words never go into the chat's.
        compose.onNodeWithText("half a thought").assertDoesNotExist()
        compose.onNodeWithContentDescription("Whisper to $AI_NAME").performTextInput("a private thing")
        compose.waitForIdle()
        assertEquals("half a thought", engine.drafts.get(engine.state.value.chat.id).text)
        compose.onNodeWithContentDescription(END_WHISPER_BUTTON).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Give me three easy dinner ideas").assertIsDisplayed()
        compose.onNodeWithText("half a thought").assertIsDisplayed()
        compose.onNodeWithText("a private thing").assertDoesNotExist()
        assertEquals(saved.map { it.text }, engine.state.value.chat.turns.map { it.text })
        assertEquals("half a thought", engine.drafts.get(engine.state.value.chat.id).text)
        assertTrue(started.isEmpty())
    }

    @Test fun aWhisperFromASavedChatSendsOnlyItsOwnWords() {
        pcWhisper()
        nav.askChat(ChatAsk.StartWhisper); compose.waitForIdle()
        compose.onNodeWithContentDescription("Whisper to $AI_NAME").performTextInput("Name three kinds of figs")
        compose.onNodeWithContentDescription("Send").performClick(); compose.waitForIdle()
        assertEquals(listOf("Name three kinds of figs"), started)
        assertEquals(Screen.IncognitoTask, nav.current)
        assertEquals(saved.map { it.text }, engine.state.value.chat.turns.map { it.text })
    }

    @Test fun withNoProjectOnThePcTheWhisperSaysWhatItNeedsAndOffersNoSend() {
        pcWhisper(projects = false, turns = emptyList())
        nav.askChat(ChatAsk.StartWhisper); compose.waitForIdle()
        compose.onNodeWithText(WHISPER_NEEDS_PROJECT).assertIsDisplayed()
        compose.onNodeWithText(WHISPER_MAKE_PROJECT).assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
        compose.onNodeWithContentDescription("Whisper to $AI_NAME").assertDoesNotExist()
    }

    @Test fun withThePcAwayTheWhisperSaysSoAndOffersNoSend() {
        pcWhisper(pcOnline = false, turns = emptyList())
        nav.askChat(ChatAsk.StartWhisper); compose.waitForIdle()
        compose.onNodeWithText(WHISPER_PC_AWAY).assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
    }

    @Test fun aWhisperThatCouldNotStartSaysSoAndKeepsTheWords() {
        engine = testEngine(tmp.newFolder(), ready = false, supported = false)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) {
                MimirTheme(reduceMotion = true) {
                    PhoneChat(engine, nav, paired = true, pcOnline = true, pcWhisperFree = true, pcWhisperProjects = true, startPcWhisper = { false })
                }
            }
        }
        compose.waitForIdle()
        nav.askChat(ChatAsk.StartWhisper); compose.waitForIdle()
        compose.onNodeWithContentDescription("Whisper to $AI_NAME").performTextInput("Name three kinds of figs")
        compose.onNodeWithContentDescription("Send").performClick(); compose.waitForIdle()
        compose.onNodeWithText(WHISPER_COULD_NOT_START).assertIsDisplayed()
        compose.onNodeWithText("Name three kinds of figs").assertIsDisplayed()
    }
}
