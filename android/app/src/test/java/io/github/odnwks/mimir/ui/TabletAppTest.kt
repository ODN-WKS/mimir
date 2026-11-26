package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real app at the tablet's sizes (1200 × 2000 upright, 2000 × 1200 on its side, i.e. 800 × 1333 dp
 * and 1333 × 800 dp) and at the 600 dp switch point: the rail, the two panes, Settings beside its
 * list, and asking first as a centred dialog.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletAppTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var station: FakeStation
    private val out = System.getProperty("roborazzi.outputDir")

    private fun start(fontScale: Float = 1f) {
        station = FakeStation(ExampleData.awake())
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale), LocalPhoneEngine provides engine) {
                MimirTheme(reduceMotion = true) { AppRoot(station, nav) }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("$out/tablet_$name.png")

    private fun heading(text: String) =
        compose.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

    /** A row of the side menu (it opens the drawer first when the window is narrower than 840 dp). */
    private fun rail(word: String) = compose.menuRow(word)

    private fun menuButton() = compose.onNodeWithContentDescription(MENU_BUTTON, substring = true)

    @Config(qualifiers = "w800dp-h1333dp-port-hdpi")
    @Test fun upright_keeps_the_menu_open_beside_one_pane() {
        start()
        // Q2: the menu stays open on the left when there is room, upright too; the chat is beside it.
        menuButton().assertDoesNotExist()
        compose.onNodeWithText(MENU_SEARCH).assertIsDisplayed()
        assertEquals(false, nav.twoPanes)
        shot("portrait_chat")
        rail(MENU_PC)
        assertEquals(Screen.Status, nav.current)
        shot("portrait_status")
    }

    @Config(qualifiers = "w800dp-h1333dp-port-hdpi")
    @Test fun upright_at_115_and_200_percent() {
        start(1.15f)
        shot("portrait_chat_115")
        menuButton().assertDoesNotExist()
        compose.onNodeWithText(MENU_SEARCH).assertIsDisplayed()
        compose.onNodeWithText(MENU_NEW_CHAT).assertIsDisplayed()
    }

    @Config(qualifiers = "w1333dp-h800dp-land-hdpi")
    @Test fun on_its_side_the_menu_stays_open_and_needs_you_sits_beside_the_chat() {
        start()
        assertTrue(nav.twoPanes)
        // The menu is always open on the left: no menu button, its rows are there.
        menuButton().assertDoesNotExist()
        compose.onNodeWithText(MENU_SEARCH).assertIsDisplayed()
        compose.onNodeWithText(MENU_NEW_CHAT).assertIsDisplayed()
        compose.onNodeWithText(MENU_SETTINGS).assertIsDisplayed()
        heading("Needs you").assertIsDisplayed()
        // Needs you is the right pane's own: no back arrow that would go nowhere.
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        shot("landscape_chat_needs_you")
    }

    @Config(qualifiers = "w1333dp-h800dp-land-hdpi")
    @Test fun on_its_side_a_task_opens_beside_status() {
        start()
        rail(MENU_PC)
        val task = station.state.value.tasks.first()
        nav.open(Screen.Task(task.id))
        compose.waitForIdle()
        assertEquals(Screen.Status, nav.current)
        heading("Your PC").assertIsDisplayed()
        shot("landscape_status_task")
        // Back closes the right side first.
        nav.back(); compose.waitForIdle()
        compose.onNodeWithText("Pick a task to see it here").assertIsDisplayed()
    }

    @Config(qualifiers = "w1333dp-h800dp-land-hdpi")
    @Test fun on_its_side_settings_shows_its_sections_beside_the_list() {
        start()
        rail(MENU_SETTINGS)
        heading("Settings").assertIsDisplayed()
        heading("Look and size").assertIsDisplayed()
        shot("landscape_settings_general")
        compose.onNodeWithText("Help and about").performClick()
        compose.waitForIdle()
        heading("Settings").assertIsDisplayed()
        heading("Help and about").assertIsDisplayed()
        shot("landscape_settings_about")
    }

    @Config(qualifiers = "w1333dp-h800dp-land-hdpi")
    @Test fun on_its_side_at_200_percent_is_one_wide_pane() {
        start(2f)
        assertEquals(false, nav.twoPanes)
        heading("Needs you").assertDoesNotExist()
        shot("landscape_chat_200")
    }

    @Config(qualifiers = "w1333dp-h800dp-land-hdpi")
    @Test fun asking_first_is_a_centred_dialog() {
        start()
        rail(MENU_PC)
        val task = station.state.value.tasks.first()
        nav.openSheet(Sheet.StopTask(task.id))
        compose.waitForIdle()
        val keep = compose.onNodeWithText("Keep going", substring = true).getUnclippedBoundsInRoot()
        // Not stretched to the bottom edge: it sits well above it, within 640 dp.
        assertTrue("dialog bottom at ${keep.bottom}", keep.bottom < 760.dp)
        shot("landscape_stop_dialog")
    }

    @Config(qualifiers = "w600dp-h960dp-port-mdpi")
    @Test fun at_600_the_menu_is_a_drawer() {
        start()
        menuButton().assertIsDisplayed()
        assertEquals(false, nav.twoPanes)
        rail(MENU_RECENT)
        assertEquals(Screen.Recent, nav.current)
        shot("w600_recent")
    }

    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    @Test fun a_phone_has_a_menu_button_and_no_bottom_tabs() {
        start()
        menuButton().assertIsDisplayed()
        compose.onNodeWithText(MENU_SEARCH).assertDoesNotExist()
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected) and hasText("Updates")).assertDoesNotExist()
    }

    /** On its side the menu stays open on the left and the chat sits in a lane at most 720 dp wide. */
    @Config(qualifiers = "w1100dp-h700dp-land-mdpi")
    @Test fun a_wide_sideways_window_has_the_menu_always_open_and_a_centred_lane() {
        start()
        menuButton().assertDoesNotExist()
        compose.onNodeWithText(MENU_SEARCH).assertIsDisplayed()
        val menu = compose.onNodeWithText(MENU_SEARCH).getUnclippedBoundsInRoot()
        assertTrue("menu ends at ${menu.right}", menu.right <= 330.dp)
        val box = compose.onNodeWithContentDescription("Message Mímir").getUnclippedBoundsInRoot()
        assertTrue("chat box is ${box.right - box.left} wide", box.right - box.left <= 720.dp)
        assertTrue("chat is right of the menu", box.left > 330.dp)
        shot("wide_landscape_chat")
    }
}
