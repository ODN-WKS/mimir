package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.NeedsKind
import io.github.odnwks.mimir.data.NeedsYouItem
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A request from a Claude session Mímir doesn't run: answered on the card, each button sends its number. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class OutsideAskTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyAnswerButAlwaysAllowIsOnTheCard() {
        val item = NeedsYouItem(
            "perm-pc:1", NeedsKind.Permission, "General", "now",
            "Write /home/me/General/hello.txt\nFrom the session \"Say hello\"", null,
            listOf("Allow", "Don't allow", "Always allow", "Answer on the PC"), serious = true,
        )
        var chosen = -1
        compose.setContent {
            MimirTheme {
                Column(Modifier.fillMaxWidth().background(Palette.Ground).padding(16.dp)) {
                    NeedsCard(item, Modifier, open = {}, answer = {}, choose = { chosen = it })
                }
            }
        }
        compose.waitForIdle()
        // Always allow writes a lasting rule, so it is given only on the PC.
        compose.onNodeWithText("Always allow").assertDoesNotExist()
        item.choices.forEachIndexed { i, words ->
            if (i == 2) return@forEachIndexed
            val n = compose.onNodeWithText(words)
            n.assertIsDisplayed()
            n.assertHeightIsAtLeast(48.dp)
            n.performClick()
            assertEquals(words, i, chosen)
        }
        compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.outputDir")}/needs_outside_ask.png")
    }
}
