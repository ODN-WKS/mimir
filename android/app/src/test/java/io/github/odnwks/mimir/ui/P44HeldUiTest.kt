package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.PcTalk
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P44: what the chat screen says about who answers, and about a held message. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class P44HeldUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val inner = FakePc()
    private val pc = object : PcTalk by inner { override fun chatsEngine() = "paid" }

    @Before fun setUp() { FakeWeb().install(); Online.set(Online.Settings()) }

    private fun show(engine: Engine) {
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { PhoneChat(engine, Navigator(), paired = true, pcOnline = true, claudeAnswers = true, claudeOffered = true) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun withOnlineChosenAnEarlierPhoneAnswerDoesNotMakeTheBarSayThisPhone() {
        val engine = testEngine(tmp.newFolder(), ready = true, turns = listOf(Turn(true, "Hi", id = "u1", at = 1), Turn(false, "Hello", id = "u1.a", at = 2, ran = "phone"))).also { it.pc = pc; it.open("c1") }
        show(engine)
        compose.onNodeWithContentDescription(whoLabel("Online")).assertExists()
    }

    @Test fun aHeldMessageSaysWhyAndGoesFromItsButton() {
        val engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }
        assertTrue(engine.ask("Keep this at home", saw = "pc"))
        show(engine)
        compose.onNodeWithText("Not sent: Online (Anthropic) answers now, not your PC.").assertExists()
        assertTrue(inner.sent.isEmpty())
        compose.onNodeWithText(SEND_HELD).performClick()
        compose.waitUntil(5_000) { inner.sent.size == 1 }
    }

    /** verify-p44 finding 5: a PC too old to answer chats never gets the message, so it doesn't say "in a moment". */
    @Test fun aPcThatCannotAnswerChatsIsSaidUnderAWaitingMessage() {
        val old = object : PcTalk by inner {
            override fun canTalk() = false
            override fun cannotChat() = true
        }
        val engine = testEngine(tmp.newFolder(), ready = false).also { it.pc = old }
        assertTrue(engine.ask("Hello", saw = "pc"))
        show(engine)
        compose.onNodeWithText(QUEUED_NO_CHAT).assertExists()
        compose.onNodeWithText(QUEUED_SOON).assertDoesNotExist()
    }
}
