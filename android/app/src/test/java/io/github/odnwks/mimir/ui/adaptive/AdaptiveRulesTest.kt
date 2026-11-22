package io.github.odnwks.mimir.ui.adaptive

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The tablet layout's rules, without drawing anything. */
@RunWith(RobolectricTestRunner::class)
class AdaptiveRulesTest {
    // ---------- Window sizes (redesign Section 2) ----------

    @Test fun a_phone_keeps_the_phone_layout() {
        val s25 = WindowShape(339f, 753f)
        assertEquals(WidthClass.Compact, s25.widthClass)
        assertFalse(s25.rail)
        assertFalse(s25.dialogs)
        assertNull(s25.panes)
    }

    @Test fun the_tablet_upright_gets_the_rail_and_one_pane() {
        val t = WindowShape(800f, 1333f, 1.15f)
        assertEquals(WidthClass.Medium, t.widthClass)
        assertTrue(t.rail)
        assertTrue(t.dialogs)
        assertNull(t.panes)
    }

    @Test fun the_tablet_on_its_side_gets_two_panes_at_normal_and_115_percent() {
        // The side menu (330 dp) stays open on the left, so 115% text needs a little more width for two panes.
        for ((width, scale) in listOf(1333f to 1f, 1400f to 1.15f)) {
            val p = WindowShape(width, 800f, scale).panes
            assertNotNull(p)
            p!!
            assertTrue("left ${p.left}", p.left in 400f * scale..540f * scale)
            assertTrue("right ${p.right}", p.right >= 480f * scale)
            assertEquals(width - PaneRules.MENU - PaneRules.DIVIDER, p.left + p.right, 0.01f)
        }
    }

    @Test fun very_large_text_gets_one_wide_pane() {
        assertNull(WindowShape(1333f, 800f, 2f).panes)
        assertTrue(WindowShape(1333f, 800f, 2f).rail)
    }

    @Test fun the_switch_points_are_600_and_840() {
        assertFalse(WindowShape(599f, 900f).rail)
        assertTrue(WindowShape(600f, 900f).rail)
        assertNull(WindowShape(839f, 900f).panes)
        // 840 wide leaves 509 beside the menu: too little for 400 + 480, so still one pane (in a centred lane).
        assertNull(WindowShape(840f, 900f).panes)
        assertNotNull(WindowShape(1250f, 900f).panes)
        // The menu is a drawer on a phone and when a tablet is upright; always open on a tablet on its side.
        assertFalse(WindowShape(599f, 900f).permanentMenu)
        assertFalse(WindowShape(800f, 1333f).permanentMenu)
        assertTrue(WindowShape(1000f, 600f).permanentMenu)
        assertTrue(WindowShape(1333f, 800f).permanentMenu)
        assertFalse(WindowShape(412f, 915f).permanentMenu)
        // A phone turned sideways stays a phone.
        assertFalse(WindowShape(915f, 412f).permanentMenu)
        assertEquals(330f, WindowShape(1333f, 800f).menuWidth, 0f)
    }

    @Test fun the_keyboard_opening_never_brings_the_bottom_bar_back() {
        // The keyboard only makes the window shorter; the rail is decided by width alone.
        val typing = WindowShape(1333f, 380f, 1.15f)
        assertTrue(typing.rail)
        // Too short for two panes while typing in a short window: one pane, still with the rail.
        assertNull(typing.panes)
        assertTrue(WindowShape(800f, 700f).rail)
    }

    @Test fun a_phone_on_its_side_never_gets_two_panes() {
        assertNull(WindowShape(915f, 412f).panes)
    }

    // ---------- One device word (Section 6) ----------

    @Test fun the_device_word_is_chosen_by_the_shortest_side() {
        assertEquals(Device.Phone, Device.of(339))
        assertEquals(Device.Phone, Device.of(599))
        assertEquals(Device.Tablet, Device.of(600))
        assertEquals(Device.Tablet, Device.of(800))
    }

    @Test fun a_phone_keeps_its_words() {
        val text = "Unpair this phone… Phones. Phones that can reach it."
        assertEquals(text, Device.Phone.reword(text))
        assertEquals("Phones", Device.Phone.andPc)
        assertEquals("This phone is offline", Device.Phone.offline)
    }

    @Test fun a_tablet_says_tablet_for_itself_and_device_for_lists() {
        val t = Device.Tablet
        assertEquals("Phones", t.andPc)
        assertEquals("On this tablet", t.onThis)
        assertEquals("This tablet is offline", t.offline)
        assertEquals("Unpair this tablet…", t.unpair)
        assertEquals("Scan it with this tablet.", t.scanWith)
        assertEquals("Unpair this tablet?", t.reword("Unpair this phone?"))
        assertEquals("Devices that can reach this PC", t.reword("Phones that can reach it"))
        assertEquals("Lost a device?", t.reword("Lost a phone?"))
        assertEquals("Allow the camera in the tablet's settings, or paste the code instead.", t.reword("Allow the camera in the phone's settings, or paste the code instead."))
        assertEquals("Run a command on your PC", t.reword("Run a command on this PC"))
        assertTrue(t.modelNotAvailable.startsWith("Not available on this tablet."))
    }

    @Test fun the_pcs_own_button_is_quoted_exactly() {
        assertEquals(
            "On the PC, click \"Pair a phone\", then scan it with this tablet.",
            Device.Tablet.reword("On the PC, click \"Pair a phone\", then scan it with this phone."),
        )
    }

    // ---------- Keys (Section 7) ----------

    @Test fun enter_sends_and_shift_enter_is_a_new_line() {
        assertEquals(BoxKey.Send, messageBoxKey(Key.Enter, shift = false, down = true))
        assertEquals(BoxKey.Send, messageBoxKey(Key.NumPadEnter, shift = false, down = true))
        assertEquals(BoxKey.NewLine, messageBoxKey(Key.Enter, shift = true, down = true))
        assertEquals(BoxKey.None, messageBoxKey(Key.A, shift = false, down = true))
    }

    @Test fun tab_always_leaves_the_message_box() {
        assertEquals(BoxKey.Next, messageBoxKey(Key.Tab, shift = false, down = true))
        assertEquals(BoxKey.Previous, messageBoxKey(Key.Tab, shift = true, down = true))
    }

    @Test fun nothing_fires_without_ctrl() {
        // A bare Enter, a bare letter and Esc never answer a card.
        for (key in listOf(Key.Enter, Key.D, Key.Escape, Key.J, Key.One)) {
            assertNull(shortcutFor(key, ctrl = false, shift = false, alt = false, cardAsking = true))
        }
    }

    @Test fun allow_and_dont_allow_only_while_a_card_is_asking() {
        assertEquals(Shortcut.Allow, shortcutFor(Key.Enter, ctrl = true, shift = false, alt = false, cardAsking = true))
        assertEquals(Shortcut.DontAllow, shortcutFor(Key.D, ctrl = true, shift = false, alt = false, cardAsking = true))
        assertEquals(Shortcut.Send, shortcutFor(Key.Enter, ctrl = true, shift = false, alt = false, cardAsking = false))
        assertNull(shortcutFor(Key.D, ctrl = true, shift = false, alt = false, cardAsking = false))
    }

    @Test fun the_rail_keys_follow_the_rails_order() {
        val f = { k: Key -> shortcutFor(k, ctrl = true, shift = false, alt = false, cardAsking = false) }
        assertEquals(Shortcut.Chat, f(Key.One))
        assertEquals(Shortcut.Status, f(Key.Two))
        assertEquals(Shortcut.Updates, f(Key.Three))
        assertEquals(Shortcut.Settings, f(Key.Comma))
        assertEquals(Shortcut.AskClaude, f(Key.N))
        assertEquals(Shortcut.NextNeedsYou, f(Key.J))
        assertEquals(Shortcut.Keys, f(Key.Slash))
    }

    @Test fun every_listed_shortcut_has_its_own_keys_and_words() {
        val listed = Shortcut.listed
        assertEquals(listed.size, listed.map { it.keys }.toSet().size)
        assertTrue(listed.all { it.does.isNotBlank() })
        assertFalse(Shortcut.Send in listed)
        assertEquals(listed.size, shortcutGroup("Recipe box").items.size)
    }

    // ---------- The rail's spoken words ----------

    @Test fun the_rail_says_what_waits() {
        val base = RailItem("Status", io.github.odnwks.mimir.ui.Glyphs.Gauge, false, {})
        assertEquals("Status", base.spoken())
        assertEquals("Status, 1 thing needs you", base.copy(count = 1).spoken())
        assertEquals("Status, 4 things need you", base.copy(count = 4).spoken())
        assertEquals("Updates, new", base.copy(label = "Updates", dot = true).spoken())
    }
}
