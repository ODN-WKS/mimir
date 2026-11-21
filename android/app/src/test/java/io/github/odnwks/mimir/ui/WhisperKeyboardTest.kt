package io.github.odnwks.mimir.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B6 / PHU-09: Whisper input boxes suppress autocorrect and keyboard learning.
 * [whisperKeyboard] returns [KeyboardOptions] with autoCorrectEnabled = false in a Whisper,
 * and the default (autoCorrectEnabled = true) outside one.
 */
@RunWith(RobolectricTestRunner::class)
class WhisperKeyboardTest {

    @Test
    fun whisperKeyboardTurnsOffAutoCorrect() {
        val opts = whisperKeyboard(on = true)
        // PHU-09: Whisper inputs must not let the keyboard learn or correct.
        assertEquals(
            "autoCorrectEnabled should be false in Whisper mode",
            false,
            opts.autoCorrectEnabled,
        )
    }

    @Test
    fun normalKeyboardKeepsAutoCorrect() {
        val opts = whisperKeyboard(on = false)
        // Outside a Whisper the default applies: true (or the system default, never false).
        assertNotEquals(
            "autoCorrectEnabled should not be false outside Whisper mode",
            false,
            opts.autoCorrectEnabled,
        )
    }
}
