package io.github.odnwks.mimir.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHW-01: a paired phone that can't run a model still keeps its chats, so History shows the same chats as the chat screen. */
class HistoryRuleTest {
    @Test fun aPairedPhoneThatCannotRunAModelStillListsItsChats() {
        assertTrue(keepsChatsHere(standaloneOk = false, paired = true))
        assertTrue(keepsChatsHere(standaloneOk = true, paired = false))
        assertFalse(keepsChatsHere(standaloneOk = false, paired = false))
    }
}
