package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The top bar's who-answers label is never cut, with "⋯" showing, at 360 dp and the S8's larger text. */
@RunWith(RobolectricTestRunner::class)
class R7TopBarTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun check(word: String, paired: Boolean) {
        val engine = testEngine(tmp.newFolder(), ready = true, turns = listOf(Turn(true, "Hello there"), Turn(false, "Hi.")))
        val station = if (paired) FakeStation().also { it.set { s -> s.copy(pcTalks = true, pc = s.pc.copy(name = "Your PC")) } } else FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, Navigator()) } } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(CHAT_MENU).assertIsDisplayed()
        val node = compose.onNodeWithText(word)
        node.assertIsDisplayed()
        // (Robolectric measures every letter as one pixel, so the width is checked with the layout's own sums below.)
        // Centred on the screen while there is room.
        val b = node.fetchSemanticsNode().boundsInRoot
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue("centred: ${b.center.x} of $width", kotlin.math.abs(b.center.x - width / 2) < width * 0.12f)
    }

    @Test fun theLabelGetsEverythingBetweenTheMenuButtonAndTheRightGroup() {
        // 360 dp bar (344 after padding), menu button 48, right group "⋯" 44 + pencil 48: 204 dp for "This phone" (about 130 dp at 1.15).
        assertEquals(204, topBarMidMax(344, 48, 92))
        // Centred when it fits; never under a button when it doesn't.
        assertEquals(100, topBarMidX(344, 48, 92, 144))
        assertEquals(48, topBarMidX(344, 48, 92, 204))
        assertEquals(48, topBarMidX(344, 48, 92, 250))
    }

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 1.15f) fun thisPhoneIsNotCutAtLargeText() = check("This phone", paired = false)
    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 1.15f) fun yourPcIsNotCutAtLargeText() = check("Your PC", paired = true)
    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 1.1f) fun yourPcIsNotCutAtTheS8sSetting() = check("Your PC", paired = true)
}
