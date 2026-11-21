package io.github.odnwks.mimir.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHW-15: "Waiting for your PC…" must appear within 5 s of a mid-answer Wi-Fi drop. */
class WifiDropMessageTest {

    @Test
    fun waiting_message_shown_immediately_after_wifi_drop_during_answer() {
        var t = 0L
        val state = WifiDropState(clock = { t })
        state.answerStarted()
        t = 1_000
        state.wifiDropped()
        assertTrue(
            "must show waiting message right after drop",
            state.showWaitingMessage,
        )
    }

    @Test
    fun waiting_message_shown_up_to_5_s_after_drop() {
        var t = 0L
        val state = WifiDropState(clock = { t })
        state.answerStarted()
        t = 100
        state.wifiDropped()
        t = 100 + WifiDropState.DROP_WINDOW_MS
        assertTrue("must show waiting message at the 5 s boundary", state.showWaitingMessage)
    }

    @Test
    fun no_message_when_drop_happens_without_active_answer() {
        var t = 0L
        val state = WifiDropState(clock = { t })
        // No answerStarted() call
        state.wifiDropped()
        assertFalse("must not show when not answering", state.showWaitingMessage)
    }

    @Test
    fun message_clears_when_answer_arrives() {
        var t = 0L
        val state = WifiDropState(clock = { t })
        state.answerStarted()
        state.wifiDropped()
        state.answerReceived()
        assertFalse("message must clear once answer arrives", state.showWaitingMessage)
    }
}
