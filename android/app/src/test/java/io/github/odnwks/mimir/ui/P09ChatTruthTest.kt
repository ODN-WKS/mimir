package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.odnwks.mimir.data.Usage
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.09 items 26 (who answered, who answers now), 11 (use left) and 23 (keyboard by itself). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class P09ChatTruthTest {
    @get:Rule val compose = createComposeRule()

    private fun answer(ran: String?, words: String?) = Turn(false, "hi", ran = ran, modelWords = words)

    // Item 26: every route names who wrote the answer.
    @Test fun phoneAnswerNamesThePhoneAndItsModel() = assertEquals("This phone · Qwen 3.5 4B", answerAuthor(answer("phone", "Qwen 3.5 4B · This phone"), "Home PC"))
    @Test fun pcAnswerNamesYourPcAndItsModel() = assertEquals("Your PC · Qwen 3.6", answerAuthor(answer("pc", "Qwen 3.6"), "Home PC"))
    @Test fun pcAnswerWithOnlyItsNameStillSaysYourPc() = assertEquals("Your PC", answerAuthor(answer("pc", "Home PC"), "Home PC"))
    @Test fun pcAnswerKeptWithTheModelAndThePcNameSaysTheModelOnce() = assertEquals("Your PC · Qwen 3.6", answerAuthor(answer("pc", "Qwen 3.6 · Home PC"), "Home PC"))
    @Test fun claudeAnswerNamesClaudeAndItsModel() = assertEquals("Claude · Sonnet 4.6", answerAuthor(answer("paid", "claude-sonnet-4-6-20250929"), "Home PC"))
    @Test fun oldAnswerNamesNobody() = assertNull(answerAuthor(answer(null, null), "Home PC"))

    @Test fun phoneTakesOverWhenThePcIsAwayAndSaysWhy() {
        val w = whoNow(paired = true, usePc = true, phoneCan = true, pcOnline = false, lastRan = "pc", lastNote = null, whisper = false, online = false)
        assertTrue(w.phoneTookOver); assertTrue(w.why!!.contains("PC"))
    }
    @Test fun phoneTakesOverWhenThePcsModelIsAwayUsingItsOwnWords() {
        val w = whoNow(true, true, true, pcOnline = true, lastRan = "phone", lastNote = "Answered by this phone because your PC's model isn't running", whisper = false, online = false)
        assertTrue(w.phoneTookOver); assertEquals("Answered by this phone because your PC's model isn't running", w.why)
    }
    @Test fun pcStaysTheAnswererWhileItAnswers() = assertFalse(whoNow(true, true, true, true, "pc", null, false, false).phoneTookOver)
    @Test fun autoPickIsNeverShownForAnotherModel() = assertEquals("Your PC's model", pcModelWord(null))

    // Item 11 (phone part): use left, or a plain "not read yet".
    @Test fun useLeftShowsTheShareLeft() {
        assertEquals("Claude use left this week: 38% · resets in 3 days", usageLeftWords(Usage("62%", 62, "resets in 3 days")))
        assertEquals("38%", usageLeftShort(Usage("62%", 62, "")))
    }
    @Test fun useLeftSaysNotReadYetWithoutAReading() {
        assertEquals("Claude use this week: not read yet", usageLeftWords(Usage("—", 0, "")))
        assertEquals("not read yet", usageLeftShort(Usage("—", 0, "")))
    }

    // Item 23: the box takes the cursor when asked, and not when the setting is off.
    private fun box(tick: Int) {
        compose.setContent {
            MimirTheme {
                val voice = VoiceInput(androidx.compose.ui.platform.LocalContext.current, station = null, phoneSpeech = { null to null })
                Box(Modifier) { MimirComposer("", {}, waiting = false, writing = false, voice = voice, onSend = {}, onStop = {}, focusTick = tick) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun newChatPutsTheCursorInTheBox() {
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences(PHONE_CHAT_PREFS, 0).edit().remove(KEYBOARD_AUTO).commit()
        box(1)
        compose.onNodeWithContentDescription("Message Mímir").assertIsFocused()
    }
    @Test fun withTheSettingOffTheBoxIsLeftAlone() {
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences(PHONE_CHAT_PREFS, 0).edit().putBoolean(KEYBOARD_AUTO, false).commit()
        box(1)
        val focused = runCatching { compose.onNodeWithContentDescription("Message Mímir").assertIsFocused() }.isSuccess
        assertFalse(focused)
    }
}
