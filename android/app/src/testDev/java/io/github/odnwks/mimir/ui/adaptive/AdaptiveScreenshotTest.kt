package io.github.odnwks.mimir.ui.adaptive

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tablet layout's building blocks drawn at the tablet's sizes (no device): upright 800 × 1333 dp,
 * on its side 1333 × 800 dp, and the 600 dp switch point; text at 100%, 115% (as the tablet is set) and 200%.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("roborazzi.outputDir")

    private fun show(fontScale: Float, device: Device, picked: Int? = null, dialog: Boolean = false, menu: Boolean = false, start: Int = 0, keys: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            val c = LocalConfiguration.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalDevice provides device) {
                MimirTheme(reduceMotion = true) {
                    AdaptiveGallery(WindowShape(c.screenWidthDp.toFloat(), c.screenHeightDp.toFloat(), fontScale), showKeys = keys, start = start, picked = picked, dialog = dialog, menu = menu)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("$out/adaptive_$name.png")

    private val paneTitles get() = compose.onAllNodes(SemanticsMatcher("has a pane title") { it.config.getOrNull(SemanticsProperties.PaneTitle) != null })
        .fetchSemanticsNodes().map { it.config[SemanticsProperties.PaneTitle] }

    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    @Test fun a_phone_has_the_bottom_bar_and_no_rail() {
        show(1f, Device.Phone)
        compose.onNodeWithContentDescription("Ask").assertDoesNotExist()
        compose.onNodeWithText("This phone is offline · Compact · one pane").assertExists()
        assertEquals(listOf("Chat"), paneTitles)
        shot("phone_390")
    }

    @Config(qualifiers = "w600dp-h960dp-port-hdpi")
    @Test fun at_600_the_rail_appears_with_one_pane() {
        show(1f, Device.Tablet)
        compose.onNodeWithContentDescription("Ask").assertExists()
        compose.onNodeWithContentDescription("Status, 4 things need you").assertExists()
        compose.onNodeWithContentDescription("Updates, new").assertExists()
        assertEquals(listOf("Chat"), paneTitles)
        shot("w600")
    }

    @Config(qualifiers = "w800dp-h1333dp-port-hdpi")
    @Test fun the_tablet_upright() {
        show(1.15f, Device.Tablet)
        compose.onNodeWithText("This tablet is offline · Medium · one pane").assertExists()
        // Reading text stays in a 640 dp column; the rail is 88 dp wide.
        val text = compose.onNodeWithText("This tablet is offline · Medium · one pane").getUnclippedBoundsInRoot()
        assertTrue("column starts at ${text.left}", text.left >= 88.dp + 16.dp)
        val rail = compose.onNodeWithContentDescription("Settings").getUnclippedBoundsInRoot()
        assertTrue("rail item ends at ${rail.right}", rail.right <= 88.dp)
        shot("portrait_115")
    }

    @Config(qualifiers = "w800dp-h1333dp-port-hdpi")
    @Test fun the_tablet_upright_at_200_percent() {
        show(2f, Device.Tablet)
        compose.onNodeWithContentDescription("Status, 4 things need you").assertExists()
        shot("portrait_200")
    }

    @Config(qualifiers = "w1333dp-h800dp-land-hdpi")
    @Test fun the_tablet_on_its_side_has_two_named_panes() {
        // The side menu (330 dp) now stays open on the left, so two panes need normal text size at this width.
        show(1f, Device.Tablet, keys = true)
        assertEquals(listOf("Chat", "Needs you"), paneTitles)
        compose.onNodeWithText("Nothing picked: what a beginner sees without doing anything.").assertExists()
        compose.onNodeWithContentDescription("Chat").assertIsSelected()
        shot("landscape_115")
    }

    @Config(qualifiers = "w1333dp-h800dp-land-hdpi")
    @Test fun picking_on_the_left_opens_on_the_right_and_the_list_stays() {
        show(1f, Device.Tablet)
        compose.onAllNodesWithText("The plan is ready")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf("Chat", "Task: The plan is ready"), paneTitles)
        compose.onNodeWithText("Picked on the left; it opened here and the list stayed put.").assertExists()
        // The two answers are the same size, side by side.
        val no = compose.onNodeWithText("Don't allow").getUnclippedBoundsInRoot()
        val yes = compose.onNodeWithText("Allow").getUnclippedBoundsInRoot()
        assertEquals(no.top, yes.top)
        assertTrue(yes.left > no.right)
        shot("landscape_task_open")
    }

    @Config(qualifiers = "w1333dp-h800dp-land-hdpi")
    @Test fun the_tablet_on_its_side_at_200_percent_is_one_wide_pane() {
        show(2f, Device.Tablet)
        assertEquals(listOf("Chat"), paneTitles)
        shot("landscape_200")
    }

    @Config(qualifiers = "w1333dp-h800dp-land-hdpi")
    @Test fun asking_first_is_a_dialog_with_the_tablets_word() {
        show(1.15f, Device.Tablet, dialog = true)
        compose.onNodeWithText("Unpair this tablet?").assertExists()
        // The safe choice is last, and the same size as the other.
        val risky = compose.onNodeWithText("Unpair").getUnclippedBoundsInRoot()
        val safe = compose.onNodeWithText("Keep paired").getUnclippedBoundsInRoot()
        assertTrue(safe.left > risky.right)
        compose.onNodeWithText("Keep paired").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Unpair this tablet?").assertDoesNotExist()
    }

    @Config(qualifiers = "w800dp-h1333dp-port-hdpi")
    @Test fun the_rail_moves_between_sections() {
        show(1f, Device.Tablet)
        compose.onNodeWithContentDescription("Status, 4 things need you").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Status, 4 things need you").assertIsSelected()
        assertEquals(listOf("Status"), paneTitles)
    }
}
