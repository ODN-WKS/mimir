package io.github.odnwks.mimir.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P24 fix round 3: words typed and not sent on the phone-model chat screen are still there after
 * Back, each chat with its own; sending forgets them, and a Whisper's words go when it ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P24KeepPhoneChatWordsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private val station = FakeStation(ExampleData.awake())
    private lateinit var engine: Engine
    private val box = "Message $AI_NAME"

    private fun start() {
        engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } } }
        compose.waitForIdle()
    }

    private fun open(screen: Screen) { compose.runOnUiThread { nav.open(screen) }; compose.waitForIdle() }

    private fun backHome() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf(Screen.Chat), nav.stack.map { it.screen })
    }

    private fun onUi(act: () -> Unit) { compose.runOnUiThread(act); compose.waitForIdle() }

    private fun type(words: String) {
        compose.onNodeWithContentDescription(box).performTextInput(words)
        compose.waitForIdle()
    }

    private fun assertBox(words: String) =
        assertEquals(words, compose.onNodeWithContentDescription(box).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

    private fun kept(chat: String, whisper: Boolean = false): String {
        val words by nav.chatDraft(chat, whisper)
        return words
    }

    private fun send() {
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5_000) { !engine.state.value.writing }
        compose.waitForIdle()
    }

    @Test fun wordsSurviveOpeningAPageAndBack() {
        start()
        open(Screen.Standalone)
        type("water the beds")
        open(Screen.StandaloneModels)
        backHome()
        open(Screen.Standalone)
        assertBox("water the beds")
    }

    @Test fun sendingForgetsTheKeptWords() {
        start()
        open(Screen.Standalone)
        type("mint")
        backHome()
        open(Screen.Standalone)
        assertBox("mint")
        send()
        assertEquals("mint", engine.state.value.chat.turns.first().text)
        backHome()
        open(Screen.Standalone)
        assertBox("")
    }

    @Test fun eachChatKeepsItsOwnWords() {
        start()
        open(Screen.Standalone)
        type("first question")
        send()
        val first = engine.state.value.chat.id
        type("basil")
        onUi { engine.newChat() }
        assertBox("")
        type("thyme")
        val second = engine.state.value.chat.id
        assertEquals("thyme", kept(second))
        assertEquals("basil", kept(first))
        backHome()
        onUi { engine.open(first) }
        open(Screen.Standalone)
        assertBox("basil")
    }

    @Test fun aWhispersWordsSurviveBackWhileItLasts() {
        start()
        open(Screen.Standalone)
        onUi { engine.startWhisper() }
        type("quiet words")
        open(Screen.Chats)
        backHome()
        open(Screen.Standalone)
        assertBox("quiet words")
    }

    @Test fun aWhispersWordsGoWhenItEndsEvenFromAnotherScreen() {
        start()
        open(Screen.Standalone)
        onUi { engine.startWhisper() }
        val whisper = engine.state.value.chat.id
        type("quiet words")
        backHome()
        assertEquals("quiet words", kept(whisper, whisper = true))
        onUi { engine.endWhisper() }
        assertEquals("", kept(whisper, whisper = true))
        open(Screen.Standalone)
        assertBox("")
    }

    @Test fun leavingAWhisperForANewChatForgetsItsWords() {
        start()
        open(Screen.Standalone)
        onUi { engine.startWhisper() }
        val whisper = engine.state.value.chat.id
        type("quiet words")
        onUi { engine.newChat() }
        assertEquals("", kept(whisper, whisper = true))
        assertBox("")
        // Nothing of it was written to the kept drafts on the phone.
        assertEquals(emptyMap<String, String>(), engine.drafts.texts())
    }
}
