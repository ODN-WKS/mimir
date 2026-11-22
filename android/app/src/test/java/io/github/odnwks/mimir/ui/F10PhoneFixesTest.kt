package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F10: one Try again on a failed lookup, a camera-denied card that fits narrow screens, and no "pair another phone" offer. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class F10PhoneFixesTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val refused = "The camera isn't allowed for this app. Allow it in Android settings, or pick a photo instead."

    private fun noteAt(widthDp: Int, fontScale: Float) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MimirTheme(reduceMotion = true) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(widthDp.dp)) {
                        AttachNote(refused, "Open settings" to {}) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        val text = compose.onNodeWithText(refused).apply { assertIsDisplayed() }.getUnclippedBoundsInRoot()
        val fix = compose.onNodeWithText("Open settings").apply { assertIsDisplayed() }.getUnclippedBoundsInRoot()
        val dismiss = compose.onNodeWithText("Dismiss").apply { assertIsDisplayed() }.getUnclippedBoundsInRoot()
        assertTrue("text runs the full width: $text", text.width >= (widthDp - 40).dp)
        listOf(fix, dismiss).forEach { assertTrue("button inside the screen: $it", it.right <= widthDp.dp && it.left >= 0.dp) }
        // Never squeezed on top of each other: side by side, or one under the other.
        val apart = fix.right <= dismiss.left || dismiss.right <= fix.left || fix.bottom <= dismiss.top || dismiss.bottom <= fix.top
        assertTrue("buttons overlap: $fix $dismiss", apart)
        assertTrue("buttons are under the words", fix.top >= text.bottom && dismiss.top >= text.bottom)
    }

    @Test fun theCameraCardFitsAt360dp() = noteAt(360, 1f)
    @Test fun theCameraCardFitsAt339dpWithSmallerFont() = noteAt(339, 0.9f)

    @Test fun aFailedLookupShowsOneTryAgain() {
        val web = FakeWeb().also { it.install(); it.fail = true }
        Online.set(Online.Settings())
        val engine = testEngine(tmp.newFolder(), ready = true)
        engine.send("What's the weather in Leeds?")
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(engine.state.value.lookupFailed)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { AppRoot(FakeStation(LinkStation.empty("t").copy(paired = false)), Navigator()) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Answer without looking it up").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("Try again").fetchSemanticsNodes().size)
    }

    @Test fun thePcPageOffersNoWayToPairAnotherPhone() {
        assertTrue("pair another" !in SETTINGS_PC_LINE.lowercase())
    }
}
