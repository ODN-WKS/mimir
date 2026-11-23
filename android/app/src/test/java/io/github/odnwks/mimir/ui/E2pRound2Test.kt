package io.github.odnwks.mimir.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.data.NeedsKind
import io.github.odnwks.mimir.data.NeedsYouItem
import io.github.odnwks.mimir.data.isGoAheadOffText
import io.github.odnwks.mimir.data.isOutOfRoomNote
import io.github.odnwks.mimir.data.taskMessageOf
import io.github.odnwks.mimir.link.LEntry
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** E2P round 2: PC text matching old and new, hidden rows, paste, the order of "Connect your PC", the chips. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class E2pRound2Test {
    @get:Rule val compose = createComposeRule()

    @Test fun theOutOfRoomNoteIsRecognisedInTheOldAndTheNewWords() {
        assertTrue(isOutOfRoomNote("This free model ran out of room."))
        assertTrue(isOutOfRoomNote("This model ran out of room."))
        assertFalse(isOutOfRoomNote("Stopped by the PC owner"))
        assertNull(taskMessageOf("t", 0, LEntry.Note("This model ran out of room."), false))
        assertNull(taskMessageOf("t", 0, LEntry.Note("This free model ran out of room."), false))
    }

    @Test fun theGoAheadRefusalIsRecognisedInTheOldAndTheNewWords() {
        assertTrue(isGoAheadOffText("YOLO from the phone is off. Switch on \"Allow YOLO from my phone\" in the PC's Settings › Phone first."))
        assertTrue(isGoAheadOffText("Go ahead without asking from the phone is off. Switch on \"Let my phone turn on Go ahead without asking\" in the PC's Settings › Phones first."))
        assertFalse(isGoAheadOffText("Your PC is away: try again when it's back."))
    }

    @Test fun itWorksAndStillBrokenShowOnlyWhenThePcSentThoseAnswers() {
        val without = NeedsYouItem("f", NeedsKind.CheckFix, "Recipe box", "1 h", "Did it start?", null)
        assertTrue(askButtons(without, { }, { }).isEmpty())
        val with = without.copy(choices = listOf("It works", "Still broken"))
        assertEquals(listOf("It works", "Still broken"), askButtons(with, { }, { }).map { it.label })
    }

    @Test fun aPastedCodeLosesItsSpacesAndNothingIsNull() {
        assertNull(pastedCode(null))
        assertNull(pastedCode("  \n "))
        assertEquals("mimir1:abc:pc", pastedCode(" mimir1:abc\n:pc "))
    }

    @Test fun pasteWithAnEmptyClipboardSaysSoAndWithACodeFillsTheBox() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("", ""))
        val nav = Navigator().also { it.pastePairCode = true }
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { MimirTheme(reduceMotion = true) { PairScreen(station, nav) } }
        compose.waitForIdle()
        compose.onNodeWithText(NOTHING_TO_PASTE).assertDoesNotExistCompat()
        compose.onNodeWithText("Paste the code").performClick(); compose.waitForIdle()
        compose.onNodeWithText(NOTHING_TO_PASTE).assertIsDisplayed()
        cm.setPrimaryClip(ClipData.newPlainText("", "mimir1:abc:pc"))
        compose.onNodeWithText("Paste the code").performClick(); compose.waitForIdle()
        compose.onNodeWithText("mimir1:abc:pc").assertIsDisplayed()
        compose.onNodeWithText(NOTHING_TO_PASTE).assertDoesNotExistCompat()
    }

    private fun onlineStore(paired: Boolean) {
        compose.setContent {
            MimirTheme { Column(Modifier.verticalScroll(rememberScrollState())) { OnlineStore(canRun = false, ramBytes = 4_000_000_000L, nav = Navigator(), paired = paired) } }
        }
        compose.waitForIdle()
    }

    @Test fun connectYourPcComesFirstOnAWeakUnpairedPhone() {
        onlineStore(paired = false)
        val connect = compose.onNodeWithText(ONLINE_CONNECT_PC).getUnclippedBoundsInRoot().top
        val heading = compose.onNodeWithText(ONLINE_HEADING).getUnclippedBoundsInRoot().top
        assertTrue("Connect your PC is above the online models", connect < heading)
    }

    @Test fun connectYourPcIsNotOfferedOnceThePhoneIsPaired() {
        onlineStore(paired = true)
        compose.onNodeWithText(ONLINE_CONNECT_PC).assertDoesNotExistCompat()
    }

    @Test fun theWhoAnswersChipsRunPhonePcOnlineAndStayOnScreen() {
        val station = FakeStation(io.github.odnwks.mimir.data.ExampleData.awake())
        val nav = Navigator()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } }
        nav.open(io.github.odnwks.mimir.nav.Screen.Models); compose.waitForIdle()
        val phone = chip("On this phone")
        val pc = chip("Your PC")
        val online = chip("Online")
        // One row, in reading order: this phone, then your PC, then Online, all the same width.
        assertEquals(phone.top, pc.top); assertEquals(pc.top, online.top)
        assertTrue(phone.left < pc.left && pc.left < online.left)
        fun w(r: androidx.compose.ui.unit.DpRect) = r.right.value - r.left.value
        assertEquals(w(phone), w(pc), 1f); assertEquals(w(pc), w(online), 1f)
        for (b in listOf(phone, pc, online)) assertTrue("a chip is cut off", b.right.value <= 390f && b.left.value >= 0f)
    }

    /** The jump chip with this label (the group titles below use some of the same words). */
    private fun chip(label: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(label) and androidx.compose.ui.test.hasClickAction())[0].getUnclippedBoundsInRoot()

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = this.assertDoesNotExist()
}
