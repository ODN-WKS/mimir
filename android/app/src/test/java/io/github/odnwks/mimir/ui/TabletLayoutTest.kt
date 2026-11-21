package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** F5: on a tablet (1200x2000 upright, 2000x1200 on its side) the side menu stays open beside the chat. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletLayoutTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val out = System.getenv("F5_SHOT")

    private fun open(screen: Screen? = null) {
        val engine = testEngine(tmp.newFolder(), ready = true, turns = listOf(
            Turn(true, "What can I cook with leeks?", id = "m1", at = 1000),
            Turn(false, "Leek and potato soup is a good start. Slice the leeks, soften them in butter, add potatoes and stock.", id = "m1.a", at = 1001),
        ))
        val nav = Navigator()
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { AppRoot(FakeStation(LinkStation.empty("test").copy(paired = false)), nav) }
            }
        }
        compose.waitForIdle()
        if (screen != null) { nav.open(screen); compose.waitForIdle() }
    }

    private fun shot(name: String) { out?.let { compose.onRoot().captureRoboImage("$it/$name.png") } }

    @Test @Config(qualifiers = "w600dp-h1000dp-xhdpi")
    fun upright_tablet_tucks_the_menu_away() {
        open()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onAllNodesWithText("What can I cook with leeks?").onLast().assertIsDisplayed()
        shot("tablet_portrait_chat")
    }

    @Test @Config(qualifiers = "w1000dp-h600dp-xhdpi")
    fun tablet_on_its_side_keeps_the_menu_open() {
        open()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        shot("tablet_landscape_chat")
    }

    @Test @Config(qualifiers = "w600dp-h1000dp-xhdpi")
    fun settings_sit_beside_the_menu() {
        open(Screen.Settings)
        compose.onAllNodesWithText("Settings").onFirst().assertExists()
        shot("tablet_portrait_settings")
    }

    @Test @Config(qualifiers = "w1000dp-h600dp-xhdpi")
    fun settings_on_its_side() { open(Screen.Settings); shot("tablet_landscape_settings") }

    @Test @Config(qualifiers = "w339dp-h740dp-xhdpi")
    fun phone_still_hides_the_menu() {
        open()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        shot("phone_chat")
    }
}
