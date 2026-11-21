package io.github.odnwks.mimir.ui

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The moves at normal animation speed and at the owner's S25 setting (0.5×): each plays in its
 * time scaled by the setting, and lands where it should, with nothing left half-way.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class AnimationScaleTest(private val scale: Float) {
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = scale })
    private val station = FakeStation()
    private val nav = Navigator()

    @Test fun aTappedTabGlidesThereInItsTime() {
        compose.setContent { MimirTheme { AppRoot(station, nav) } }
        compose.waitForIdle()
        compose.openMenu()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(MENU_PC).performClick()
        // Half-way through the glide, Status is on its way in, not there yet and not jumped.
        compose.mainClock.advanceTimeBy((150 * scale).toLong())
        val title = compose.onNode(hasText("Your PC") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val mid = title.getUnclippedBoundsInRoot().left
        assertTrue("on its way at ${scale}x ($mid)", mid > 52.dp)
        // Done in 300 ms × the setting (plus a frame or two).
        compose.mainClock.advanceTimeBy((150 * scale).toLong() + 50)
        assertEquals(52.dp, title.getUnclippedBoundsInRoot().left)
        assertEquals(Tab.Status, nav.selectedTab)
    }

    @Test fun theAmberBarGrowsInItsTime() {
        val all = station.state.value.needsYou
        station.set { it.copy(needsYou = emptyList()) }
        compose.setContent { MimirTheme { AppRoot(station, nav) } }
        compose.waitForIdle()
        val floor = compose.onNodeWithContentDescription("Message Mímir").getUnclippedBoundsInRoot().top
        compose.mainClock.autoAdvance = false
        station.set { it.copy(needsYou = all) }
        compose.mainClock.advanceTimeBy((100 * scale).toLong())
        val bar = compose.onNodeWithText("4 things need you", useUnmergedTree = true)
        val mid = bar.getUnclippedBoundsInRoot().top
        compose.mainClock.advanceTimeBy((100 * scale).toLong() + 50)
        val end = bar.getUnclippedBoundsInRoot().top
        assertTrue("grew part way at ${scale}x ($floor, $mid, $end)", mid < floor && mid > end)
        // And no further after its time: it had finished.
        compose.mainClock.advanceTimeBy(500)
        assertEquals(end, bar.getUnclippedBoundsInRoot().top)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x")
        fun scales(): List<Array<Any>> = listOf(arrayOf<Any>(1f), arrayOf<Any>(0.5f))
    }
}
