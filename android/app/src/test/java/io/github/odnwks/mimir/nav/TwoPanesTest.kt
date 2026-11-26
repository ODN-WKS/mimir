package io.github.odnwks.mimir.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tablet's two panes: what opens on the right, Back, and turning the tablet keeping what is open. */
class TwoPanesTest {
    private fun wide() = Navigator().also { it.fitPanes(true) }

    @Test fun aTaskOpensOnTheRightAndTheListStays() {
        val nav = wide()
        nav.selectTab(Tab.Status)
        nav.open(Screen.Task("a"))
        assertEquals(listOf(Screen.Status), nav.stack.map { it.screen })
        assertEquals(listOf(Screen.Task("a")), nav.detail.map { it.screen })
        // Another task replaces it; its change sits on top.
        nav.open(Screen.Task("b"))
        nav.open(Screen.Change("b"))
        assertEquals(listOf(Screen.Task("b"), Screen.Change("b")), nav.detail.map { it.screen })
        // P24: Back closes the whole right pane at once, not one page of it.
        assertTrue(nav.back())
        assertTrue(nav.detail.isEmpty())
        assertEquals(Screen.Status, nav.current)
    }

    @Test fun settingsSectionsOpenBesideTheList() {
        val nav = wide()
        nav.open(Screen.Settings)
        nav.open(Screen.Help)
        nav.open(Screen.About)
        assertEquals(Screen.Settings, nav.current)
        assertEquals(listOf(Screen.Help, Screen.About), nav.detail.map { it.screen })
        nav.open(Screen.Voice)
        assertEquals(listOf(Screen.Voice), nav.detail.map { it.screen })
        // Away from Settings, its pages are full screens again.
        nav.selectTab(Tab.Chat)
        assertTrue(nav.detail.isEmpty())
    }

    @Test fun turningTheTabletKeepsTheOpenItem() {
        val nav = Navigator()
        nav.selectTab(Tab.Status)
        nav.open(Screen.Task("a"))
        nav.open(Screen.Change("a"))
        nav.fitPanes(true)
        assertEquals(listOf(Screen.Status), nav.stack.map { it.screen })
        assertEquals(listOf(Screen.Task("a"), Screen.Change("a")), nav.detail.map { it.screen })
        nav.fitPanes(false)
        assertEquals(listOf(Screen.Status, Screen.Task("a"), Screen.Change("a")), nav.stack.map { it.screen })
        assertTrue(nav.detail.isEmpty())
    }

    @Test fun onAPhoneNothingChanges() {
        val nav = Navigator()
        nav.open(Screen.Settings)
        nav.open(Screen.General)
        assertEquals(listOf(Screen.Chat, Screen.Settings, Screen.General), nav.stack.map { it.screen })
        assertTrue(nav.detail.isEmpty())
    }

    @Test fun askClaudeGoesToChatAndAsksForTheCursor() {
        val nav = wide()
        nav.selectTab(Tab.Updates)
        nav.ask()
        assertEquals(Screen.Chat, nav.current)
        assertEquals(1, nav.askTick)
        nav.ask()
        assertEquals(2, nav.askTick)
    }

    @Test fun backFromChatWithNothingOpenStillLeavesTheApp() {
        val nav = wide()
        assertFalse(nav.canGoBack)
        nav.open(Screen.NeedsYou)
        assertTrue(nav.canGoBack)
        assertTrue(nav.back())
        assertFalse(nav.back())
    }
}
