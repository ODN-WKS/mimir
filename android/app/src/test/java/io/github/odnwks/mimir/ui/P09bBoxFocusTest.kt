package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.09 review findings 3 and 5: the keyboard rises only when it should, and any box that needs typing takes it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class P09bBoxFocusTest {
    @get:Rule val compose = createComposeRule()

    private fun prefs(on: Boolean) = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSharedPreferences(PHONE_CHAT_PREFS, 0).edit().putBoolean(KEYBOARD_AUTO, on).commit()

    private fun composer(tick: Int, ok: Boolean) {
        compose.setContent {
            MimirTheme {
                val voice = VoiceInput(androidx.compose.ui.platform.LocalContext.current, station = null, phoneSpeech = { null to null })
                Box(Modifier) { MimirComposer("", {}, waiting = false, writing = false, voice = voice, onSend = {}, onStop = {}, focusTick = tick, focusOk = ok) }
            }
        }
        compose.waitForIdle()
    }

    private fun focused(label: String) = runCatching { compose.onNodeWithContentDescription(label).assertIsFocused() }.isSuccess

    @Test fun aSheetOrMenuOrDialogOrWhisperKeepsTheKeyboardAway() {
        prefs(true)
        composer(1, ok = false)
        assertFalse(focused("Message Mímir"))
    }

    @Test fun aBoxThatNeedsTypingTakesTheCursor() {
        prefs(true)
        compose.setContent { MimirTheme { MessageBox("", {}, "Chat name", "Chat name", Modifier, autoFocus = true) } }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        compose.onNodeWithContentDescription("Chat name").assertIsFocused()
    }

    @Test fun withTheSettingOffTheBoxIsLeftAlone() {
        prefs(false)
        compose.setContent { MimirTheme { MessageBox("", {}, "Chat name", "Chat name", Modifier, autoFocus = true) } }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        assertFalse(focused("Chat name"))
    }
}
