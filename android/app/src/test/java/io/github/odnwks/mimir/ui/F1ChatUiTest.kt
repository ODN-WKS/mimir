package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
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

/** QA1 fixes (F1) seen on the phone chat's screen: rename, per-chat rules and numbers, the side menu's Whisper, the mic. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class F1ChatUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var engine: Engine

    private fun chatOpen(turns: List<Turn> = listOf(Turn(true, "Give me three easy dinner ideas"), Turn(false, "Pasta with tomatoes."))) {
        engine = testEngine(tmp.newFolder(), ready = true, turns = turns)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { AppRoot(FakeStation(LinkStation.empty("t").copy(paired = false)), nav) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun renameOpensADialogWithTheTitleAndSavesIt() {
        chatOpen()
        compose.openChatOptions()
        compose.onNodeWithText(CHAT_RENAME).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Rename this chat").assertIsDisplayed()
        compose.onNodeWithText("Give me three easy dinner ideas".let { "Three easy dinner ideas" }).assertIsDisplayed()
        compose.onNodeWithText("Done").performClick(); compose.waitForIdle()
        assertEquals("Three easy dinner ideas", engine.state.value.chat.name)
        assertTrue(engine.state.value.chat.pendingName)
    }

    @Test fun theChatsOwnRulesAreKeptWithThatChat() {
        chatOpen()
        compose.openChatOptions()
        compose.onNodeWithText(CHAT_RULES).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Rules for this chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rules for this chat").performTextInput("Answer in French.")
        compose.onNodeWithText("Save").performClick(); compose.waitForIdle()
        assertEquals("Answer in French.", engine.state.value.chat.rules)
        assertEquals(Screen.Chat, nav.current)
    }

    @Test fun whatWasSentShowsThisChatsNumbersOnASmallSheet() {
        chatOpen()
        compose.openChatOptions()
        compose.onNodeWithText(CHAT_SENT).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Nothing from this chat has left this phone.").assertIsDisplayed()
        assertEquals(Screen.Chat, nav.current)
    }

    @Test fun theSideMenusWhisperStartsOneOnAPhoneThatCanAnswer() {
        chatOpen(turns = emptyList())
        nav.askChat(ChatAsk.StartWhisper); compose.waitForIdle()
        assertTrue(engine.state.value.whisper)
    }

    @Test fun pairedWithAFreePcModelTheWhisperWaitsForItsFirstWordsAndStartsOnThePc() {
        engine = testEngine(tmp.newFolder(), ready = false, supported = false)
        val started = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) {
                MimirTheme(reduceMotion = true) {
                    PhoneChat(engine, nav, paired = true, pcOnline = true, pcWhisperFree = true, startPcWhisper = { started += it; true })
                }
            }
        }
        compose.waitForIdle()
        nav.askChat(ChatAsk.StartWhisper); compose.waitForIdle()
        // The Whisper's own words and box; the phone itself is untouched.
        compose.onNodeWithText(WHISPER_CARD_TITLE).assertIsDisplayed()
        assertTrue(!engine.state.value.whisper)
        compose.onNodeWithContentDescription("Whisper to $AI_NAME").performTextInput("What is a good name for a cat")
        compose.onNodeWithContentDescription("Send").performClick(); compose.waitForIdle()
        assertEquals(listOf("What is a good name for a cat"), started)
        assertEquals(Screen.IncognitoTask, nav.current)
    }

    @Test fun aPhoneThatCantRunAModelAndHasNoPcSaysSoInsteadOfDoingNothing() {
        engine = testEngine(tmp.newFolder(), ready = false, supported = false)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) {
                MimirTheme(reduceMotion = true) { PhoneChat(engine, nav, paired = true, pcOnline = true, pcWhisperFree = false) }
            }
        }
        compose.waitForIdle()
        nav.askChat(ChatAsk.StartWhisper); compose.waitForIdle()
        compose.onNodeWithText(WHISPER_NOWHERE).assertIsDisplayed()
    }
}
