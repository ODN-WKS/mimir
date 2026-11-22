package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.TurnPicture
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

/** R30: a picture goes to the PC when the PC answers; the phone's own model is not asked about. */
@RunWith(RobolectricTestRunner::class)
class R30PicturesTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val pc = FakePc()
    private val shot = TurnPicture.of("Screenshot", byteArrayOf(1, 2, 3, 4))

    private fun engine(ready: Boolean = false): Engine = testEngine(tmp.newFolder(), ready = ready).also { it.pc = pc }

    private fun settle(e: Engine) {
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (e.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(e.state.value.writing)
    }

    @Test fun aPcThatSeesGetsThePictureWithTheMessageEvenWithNoModelOnThePhone() {
        pc.seesPictures = true
        val e = engine(ready = false)
        assertEquals(true, e.pcPictures())
        e.send("What does this show?", pictures = listOf(shot)); settle(e)
        val asked = pc.sent.single().turns.single { it.id == pc.sent.single().turnId }
        assertEquals(1, asked.pictures.size)
        assertEquals("image/jpeg", asked.pictures[0].mediaType)
        assertEquals(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)), asked.pictures[0].base64)
        // The text form the chat keeps says only that there was a picture.
        assertEquals("[picture]\nWhat does this show?", asked.text)
        // The next message sends the old picture as words only.
        pc.words = listOf("A screenshot.")
        e.send("And the second line?"); settle(e)
        val second = pc.sent.last()
        assertTrue(second.turns.all { it.pictures.isEmpty() })
        assertEquals("[picture]\nWhat does this show?", second.turns.first().text)
    }

    @Test fun anOlderPcOrOneThatCantSeeNeverGetsPicturesOnTheWire() {
        pc.seesPictures = null
        val e = engine(ready = true)
        assertNull(e.pcPictures())
        e.send("What is this?", pictures = listOf(shot)); settle(e)
        assertTrue(pc.sent.single().turns.all { it.pictures.isEmpty() })
        pc.seesPictures = false
        assertEquals(false, e.pcPictures())
    }

    private fun show(e: Engine) {
        val station = FakeStation().also { it.set { s -> s.copy(pcTalks = true, pc = s.pc.copy(name = "Your PC")) } }
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides e) { MimirTheme { AppRoot(station, Navigator()) } } }
        compose.waitForIdle()
    }

    @Test fun whenThePcCantLookAtPicturesThePlusSaysSoAndNeverOffersAPhoneModel() {
        pc.seesPictures = false
        show(engine(ready = true))
        compose.onNodeWithContentDescription("Attach").assertIsDisplayed().performClick()
        compose.onNodeWithText("None of your PC's models can look at pictures right now. Send words only.").assertIsDisplayed()
        compose.onNodeWithText("Photo or video").assertDoesNotExist()
        compose.onNodeWithText("Take a photo").assertDoesNotExist()
    }

    @Test fun whenThePcSeesThePlusOffersPhotosWithNoPhoneModelNeeded() {
        pc.seesPictures = true
        show(engine(ready = false))
        compose.onNodeWithContentDescription("Attach").assertIsDisplayed().performClick()
        compose.onNodeWithText("Photo or video").assertIsDisplayed()
        compose.onNodeWithText("Take a photo").assertIsDisplayed()
    }
}
