package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import io.github.odnwks.mimir.data.Mapping
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.link.LModels
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Net
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.ChatDrafts
import io.github.odnwks.mimir.standalone.Draft
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.adaptive.WindowShape
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Q2: the phone fixes from the 0.04 test round (QA6). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class Q2PhoneFixesTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    // 1. One "Try again" on a failed lookup: the button, never the words; and the real cause.
    @Test fun aFailedLookupNoteDoesNotTellTheModelToSayTryAgain() {
        val note = Engine.lookupFailedNote("look up the weather for \"London\"", "this phone has no signal")
        assertFalse(note, "try again" in note.lowercase())
        assertTrue(note, "this phone has no signal" in note)
        assertTrue(note, "not the place" in note)
        assertFalse(Engine.LATE, "try again" in Engine.LATE.lowercase())
    }

    @Test fun thePairedFailedLookupAnswerHasNoTryAgainWords() {
        val said = Net.sorry(Net.Failed("the site is out of reach just now"))
        assertFalse(said, "try again" in said.lowercase())
        assertTrue(said, "out of reach" in said)
    }

    // 2. Pictures: trust the PC; no Settings pointer; refreshed after a switch note.
    @Test fun theModelsStateCarriesWhatThePcSaysAboutPictures() {
        val m = LModels(emptyList(), null, switchToFree = false, why = "", seesPictures = true)
        assertEquals(true, Mapping.models(m, "auto").seesPictures)
        assertEquals(false, Mapping.models(m.copy(seesPictures = false), "auto").seesPictures)
        assertNull(Mapping.models(null, "auto").seesPictures)
    }

    @Test fun noPictureWordsPointToSettings() {
        assertFalse(PC_CANNOT_SEE, "Settings" in PC_CANNOT_SEE)
    }

    @Test fun anAnswerWithASwitchNoteRefreshesWhatThePcSees() {
        FakeWeb().install()
        Online.set(Online.Settings())
        val pc = FakePc()
        pc.answer = { r, l ->
            l.done(LChatTurn("${r.turnId}.a", false, "A red square.", ran = "pc", at = r.turns.last().at + 1, switchNote = "Answered by another model because your usual model can't look at pictures."), null)
        }
        val engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }
        engine.send("What is in this picture?")
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue("asked the PC again what it sees", pc.sightRefreshed.get() >= 1)
    }

    // 3. A tablet held upright keeps the menu open.
    @Test fun theMenuStaysOpenOnATabletHeldUpright() {
        assertTrue(WindowShape(800f, 1333f, 1.15f).permanentMenu)
        assertTrue(WindowShape(720f, 1000f).permanentMenu)
        assertFalse(WindowShape(719f, 1000f).permanentMenu)
        assertFalse(WindowShape(412f, 915f).permanentMenu)
        assertFalse(WindowShape(915f, 412f).permanentMenu)
        assertTrue(WindowShape(1333f, 800f).permanentMenu)
    }

    // 4. Each chat keeps its own draft.
    @Test fun eachChatKeepsItsOwnDraft() {
        val d = ChatDrafts()
        val a = Draft("words for A", emptyList(), emptyList())
        val none = d.swap("a", "b", a)
        assertEquals(Draft("", emptyList(), emptyList()), none)
        val back = d.swap("b", "a", Draft("words for B", emptyList(), emptyList()))
        assertEquals(a, back)
        assertEquals("words for B", d.swap("a", "b", back).text)
    }

    // 5. The mic card lays out like the camera card.
    @Test fun theMicRefusedCardHasFullWidthTextAndButtonsBelow() {
        compose.setContent {
            val dd = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(dd.density, 1f)) {
                MimirTheme(reduceMotion = true) { Box(androidx.compose.ui.Modifier.width(360.dp)) { MicRefusedCard {} } }
            }
        }
        compose.waitForIdle()
        val text = compose.onNodeWithText(MIC_REFUSED).apply { assertIsDisplayed() }.getUnclippedBoundsInRoot()
        val fix = compose.onNodeWithText("Open settings").apply { assertIsDisplayed() }.getUnclippedBoundsInRoot()
        assertTrue("text runs the full width: $text", text.width >= 320.dp)
        assertTrue("buttons under the words", fix.top >= text.bottom)
    }

    // 6. One plain line after a failed update check.
    @Test fun aFailedUpdateCheckShowsOneLineThatMatches() {
        val away = updateDetail(fallback = true, UpdateResult.Couldnt, paired = true)!!
        assertFalse(away, "so your PC answered" in away)
        assertTrue(away, "GitHub" in away && "PC" in away)
        val alone = updateDetail(fallback = true, UpdateResult.Couldnt, paired = false)!!
        assertFalse(alone, "PC is away" in alone)
        assertEquals("GitHub couldn't be reached, so your PC answered. Check again later.", updateDetail(true, UpdateResult.Ready("0.05"), paired = true))
        assertNull(updateDetail(true, UpdateResult.Newest("0.04"), paired = true))
    }
}
