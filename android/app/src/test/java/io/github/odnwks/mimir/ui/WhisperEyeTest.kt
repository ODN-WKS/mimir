package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Break test 0.19, problem 6: a Whisper must never start by itself. The eye now opens a menu
 * (Chats, New chat, New Whisper; owner, 2026-11-19): a tap alone never starts a Whisper, nothing
 * else shares the bar beside it, and it stays where it is with a conversation going.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w339dp-h740dp-xhdpi")
class WhisperEyeTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theEyeStaysPutAndATapAloneNeverStartsAWhisper() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        val unpaired = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, Navigator()) } }
        }
        compose.waitForIdle()
        val eye = { compose.onNodeWithContentDescription(whoLabel("This phone")).fetchSemanticsNode().boundsInRoot }
        val empty = eye()
        // A tap opens "Who should answer?", and nothing more.
        compose.onNodeWithContentDescription(whoLabel("This phone")).performClick()
        compose.waitForIdle()
        assertFalse(engine.state.value.whisper)
        compose.onNodeWithText(WHO_TITLE).assertExists()
        compose.onNodeWithText(WHO_PHONE_ABOUT).assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        // With a chat going, the who-answers label is where it was.
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("Give me three easy dinner ideas")
        compose.onNodeWithContentDescription("Send").performClick()
        val until = System.currentTimeMillis() + 5_000
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        compose.waitForIdle()
        assertEquals(empty, eye())
        assertFalse(engine.state.value.whisper)
    }
}
