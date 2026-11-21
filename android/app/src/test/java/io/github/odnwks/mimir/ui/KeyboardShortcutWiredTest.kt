package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHU-15: every shortcut key combo from the help screen triggers the documented action.
 * Uses a test double for [ShortcutActions] to avoid any Android framework dependency.
 */
class KeyboardShortcutWiredTest {

    private class RecordingActions : ShortcutActions {
        val fired = mutableListOf<String>()
        override fun goToChat() { fired += "chat" }
        override fun goToStatus() { fired += "status" }
        override fun goToUpdates() { fired += "updates" }
        override fun goToSettings() { fired += "settings" }
        override fun askClaude() { fired += "ask" }
        override fun search() { fired += "search" }
        override fun nextNeedsYou() { fired += "nextNeedsYou" }
        override fun allowCard() { fired += "allow" }
        override fun dontAllowCard() { fired += "dontAllow" }
        override fun pauseClaude() { fired += "pause" }
        override fun openTalk() { fired += "talk" }
        override fun showKeyList() { fired += "keys" }
        override fun sendMessage() { fired += "send" }
    }

    private fun fire(cut: Shortcut, paired: Boolean = true, cardAsking: Boolean = false): List<String> {
        val a = RecordingActions()
        handleShortcut(cut, a, paired, cardAsking)
        return a.fired
    }

    @Test
    fun each_shortcut_triggers_its_documented_action() {
        assertEquals(listOf("chat"), fire(Shortcut.Chat))
        assertEquals(listOf("status"), fire(Shortcut.Status))
        assertEquals(listOf("updates"), fire(Shortcut.Updates))
        assertEquals(listOf("settings"), fire(Shortcut.Settings))
        assertEquals(listOf("ask"), fire(Shortcut.AskClaude))
        assertEquals(listOf("ask"), fire(Shortcut.MessageBox))
        assertEquals(listOf("search"), fire(Shortcut.Search))
        assertEquals(listOf("nextNeedsYou"), fire(Shortcut.NextNeedsYou))
        assertEquals(listOf("pause"), fire(Shortcut.Pause))
        assertEquals(listOf("talk"), fire(Shortcut.Talk))
        assertEquals(listOf("keys"), fire(Shortcut.Keys))
    }

    @Test
    fun allow_and_dont_allow_fire_only_when_card_is_asking() {
        assertEquals(listOf("allow"), fire(Shortcut.Allow, cardAsking = true))
        assertTrue("Allow with no card does nothing", fire(Shortcut.Allow, cardAsking = false).isEmpty())
        assertEquals(listOf("dontAllow"), fire(Shortcut.DontAllow, cardAsking = true))
        assertTrue("DontAllow with no card does nothing", fire(Shortcut.DontAllow, cardAsking = false).isEmpty())
    }

    @Test
    fun send_fires_when_paired() {
        assertEquals(listOf("send"), fire(Shortcut.Send, paired = true))
    }

    @Test
    fun shortcuts_blocked_before_pairing() {
        // Before pairing, only Chat / Status / Updates / Settings / Keys are allowed.
        assertTrue(fire(Shortcut.Chat, paired = false).isNotEmpty())
        assertTrue(fire(Shortcut.Settings, paired = false).isNotEmpty())
        assertTrue("AskClaude needs pairing", fire(Shortcut.AskClaude, paired = false).isEmpty())
        assertTrue("Search needs pairing", fire(Shortcut.Search, paired = false).isEmpty())
    }
}
