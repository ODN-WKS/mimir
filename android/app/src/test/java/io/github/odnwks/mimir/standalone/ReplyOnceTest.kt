package io.github.odnwks.mimir.standalone

import org.junit.Assert.assertEquals
import org.junit.Test

/** A reply shows once: the live answer and the synced copy of the same turn are one entry. */
class ReplyOnceTest {
    @Test fun syncedCopyAndLiveAnswerAreOneEntry() {
        val ask = Turn(true, "What is 2 plus 2", id = "t1", at = 1)
        val synced = Turn(false, "2 plus 2 equals 4.", id = "t1.a", at = 2, ran = "pc", seq = 7)
        val live = Turn(false, "2 plus 2 equals 4.", id = "t1.a", at = 2, ran = "pc", seq = 7)
        val turns = ChatSync.withAnswer(listOf(ask, synced), live)
        assertEquals(listOf("t1", "t1.a"), turns.map { it.id })
    }

    @Test fun liveAnswerAloneIsAdded() {
        val ask = Turn(true, "Hi", id = "t2", at = 1)
        assertEquals(2, ChatSync.withAnswer(listOf(ask), Turn(false, "Hello", id = "t2.a", at = 2)).size)
    }
}
