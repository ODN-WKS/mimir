package io.github.odnwks.mimir.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** P27: which tasks are chats (no "Fresh start" there), by the PC's folder. */
class LinkStationFreshStartTest {
    @Test fun theChatsFolderIsToldFromAProject() {
        assertTrue(isChatsFolder("/home/someone/.local/share/mimir/chats"))
        assertTrue(isChatsFolder("/home/someone/.local/share/mimir/chats/"))
        assertTrue(!isChatsFolder("/home/someone/Projects/chats"))
        assertTrue(!isChatsFolder("/home/someone/Projects/Recipe box"))
    }
}
