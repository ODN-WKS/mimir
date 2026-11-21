package io.github.odnwks.mimir.labs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.LabsScreen
import io.github.odnwks.mimir.ui.LastChecks
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Labs drawn whole (a tall screen) at the S25's 339 dp and text at 0.9, so the report preview is in the picture. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w339dp-h1700dp-xhdpi")
class LabsShotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wholePage() {
        LastChecks.lines = listOf(
            CheckLine("Your PC", CheckLine.Result.Good, "Reached in 84 ms"),
            CheckLine("Ingrid's voice", CheckLine.Result.Problem, "Didn't load", "Close other apps and try again"),
            CheckLine("Looking things up", CheckLine.Result.Skipped, "Turned off, so not checked"),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 0.9f)) {
                MimirTheme { LabsScreen(Navigator()) }
            }
        }
        compose.waitForIdle()
        val file = "${System.getProperty("roborazzi.outputDir")}/Labs_whole_s25.png"
        compose.onRoot().captureRoboImage(file)
        assertTrue(java.io.File(file).length() > 0)
        LastChecks.lines = null
    }
}
