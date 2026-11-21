package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.ChatAsk
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A menu request (New chat, Whisper) is taken only by the screen that is shown after the switch. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ChatAskTargetTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val turns = listOf(Turn(user = true, text = "Hello there"), Turn(user = false, text = "Hi."))

    @Test fun aScreenThatIsNotShownLeavesTheRequestAlone() {
        val engine = testEngine(tmp.newFolder(), ready = true, turns = turns)
        val nav = Navigator() // the Chat screen is shown, not the phone's own screen
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { StandaloneScreen(engine, nav) } } }
        compose.waitForIdle()
        nav.askChat(ChatAsk.NewChat)
        compose.waitForIdle()
        assertNotNull("the request waits for the screen that is shown", nav.chatAsk)
        assertEquals(2, engine.state.value.chat.turns.size)
    }

    @Test fun theShownChatScreenTakesTheRequestFromTheMenu() {
        val engine = testEngine(tmp.newFolder(), ready = true, turns = turns)
        val nav = Navigator(Screen.Standalone)
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, nav) } } }
        compose.waitForIdle()
        nav.selectTab(Tab.Chat)
        nav.askChat(ChatAsk.NewChat)
        compose.waitForIdle()
        assertNull(nav.chatAsk)
        assertEquals(0, engine.state.value.chat.turns.size)
    }
}
