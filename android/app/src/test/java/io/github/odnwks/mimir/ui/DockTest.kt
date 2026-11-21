package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.Permission
import io.github.odnwks.mimir.data.PermissionKind
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The decision dock in every shape it takes: both answers whole, in view, never scrolled. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class DockTest(private val name: String, private val kind: PermissionKind, private val compact: Boolean, private val tight: Boolean, private val font: Float, private val width: Int) {
    @get:Rule val compose = createComposeRule()

    @Test fun bothAnswersAreWholeAndWork() {
        if (width != 390) RuntimeEnvironment.setQualifiers("w${width}dp-h734dp-xhdpi")
        val p = Permission(
            "p", if (kind == PermissionKind.Command) "Allow Claude to run this command?" else "Allow Claude to edit remote.rs?", "remote.rs", 2, kind,
            command = "git tag -a v0.68 -m 'Version 0.68' && git push origin v0.68 && cargo publish --all-features --locked",
        )
        var answered = ""
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                MimirTheme {
                    Column(Modifier.fillMaxWidth().background(Palette.Ground).padding(vertical = 8.dp)) {
                        DecisionDock(p, 2, compact, tight, { answered = "allow" }, { answered = "no" }, {}, {}, also = if (tight) "There is also a question for you" else null)
                    }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        for (words in listOf("Don't allow", p.allowWords)) {
            val n = compose.onNodeWithText(words)
            n.assertIsDisplayed()
            n.assertHeightIsAtLeast(48.dp)
            val b = n.getUnclippedBoundsInRoot()
            assertTrue("$words is inside the screen", b.left >= root.left && b.right <= root.right && b.bottom <= root.bottom)
        }
        compose.onNodeWithText("Don't allow").performClick()
        assertEquals("no", answered)
        compose.onNodeWithText(p.allowWords).performClick()
        assertEquals("allow", answered)
        compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.outputDir")}/dock_$name.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            arrayOf("edit", PermissionKind.Edit, false, false, 1f, 390),
            arrayOf("command_with_question", PermissionKind.Command, false, true, 1f, 390),
            arrayOf("command_keyboard", PermissionKind.Command, true, false, 1f, 390),
            arrayOf("command_font200", PermissionKind.Command, false, true, 2f, 390),
            arrayOf("edit_font200_keyboard", PermissionKind.Edit, true, false, 2f, 390),
            arrayOf("command_s25", PermissionKind.Command, false, true, 0.9f, 339),
            arrayOf("command_s25_keyboard", PermissionKind.Command, true, false, 0.9f, 339),
            arrayOf("command_s25_font130", PermissionKind.Command, false, true, 1.3f, 339),
        )
    }
}
