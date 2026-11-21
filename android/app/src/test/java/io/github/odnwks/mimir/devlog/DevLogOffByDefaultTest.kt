package io.github.odnwks.mimir.devlog

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * PHD-07, PHW-13: developer log and "Include my normal chats" are OFF by default
 * on a fresh install.
 */
class DevLogOffByDefaultTest {

    @Test
    fun `developer log preference is false by default`() {
        // The constant that SharedPreferences uses as its default value must be false.
        assertFalse(
            "DEV_LOG_DEFAULT must be false (PHD-07)",
            DevLogRunner.DEV_LOG_DEFAULT,
        )
    }

    @Test
    fun `include my normal chats preference is false by default`() {
        assertFalse(
            "CHATS_DEFAULT must be false (PHW-13)",
            DevLogRunner.CHATS_DEFAULT,
        )
    }
}
