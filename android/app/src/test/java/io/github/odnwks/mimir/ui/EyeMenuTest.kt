package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The eye's menu is compact (owner, 2026-11-19: "less cluttered"): one short line per row, never
 * wrapped, at 100% and 150% text size on the S25's width; what each row is shows only on a long press.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w339dp-h740dp-xxhdpi")
class EyeMenuTest {
    @get:Rule val compose = createComposeRule()

    private fun open(fontScale: Float, off: String? = null) {
        compose.setContent {
            val d = LocalDensity.current
            // The rows as the menu lays them out: as wide as the widest row, on the S25's width.
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MimirTheme {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                            .widthIn(max = 280.dp),
                    ) {
                        EyeMenuRows(false, false, {}, {}, {}, {}, phoneChat = PhoneChatRow(false, off, {}, {}))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun lines(text: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    private fun oneLineEach(fontScale: Float, off: String? = null) {
        open(fontScale, off)
        compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.outputDir")}/eyemenu_rows_$fontScale${if (off != null) "_off" else ""}.png")
        for (row in listOfNotNull(EYE_CHATS, EYE_NEW_CHAT, EYE_NEW_WHISPER, EYE_NEW_WEB_WHISPER, EYE_PHONE_CHAT, off)) {
            val l = lines(row)
            assertEquals("$row at ${fontScale}x", 1, l.lineCount)
            assertFalse("$row is cut off at ${fontScale}x: box ${l.size}, text ${l.multiParagraph.width} x ${l.multiParagraph.height}", l.hasVisualOverflow)
        }
        // No description lines on the rows: they're for a long press.
        for (about in listOf(EYE_CHATS_ABOUT, EYE_NEW_CHAT_ABOUT, EYE_NEW_WHISPER_ABOUT, EYE_NEW_WEB_WHISPER_ABOUT, EYE_PHONE_CHAT_ABOUT)) {
            compose.onNodeWithText(about, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun everyRowIsOneLineAtNormalSize() = oneLineEach(1f)

    @Test fun everyRowIsOneLineAtOneAndAHalfTimesTheTextSize() = oneLineEach(1.5f)

    // Paired, "Phone-only chat" is greyed with one short line why (and a way to a model) when it can't.
    @Test fun theGreyedPhoneRowsReasonIsOneLine() {
        oneLineEach(1.5f, EYE_PHONE_NEEDS_MODEL)
        compose.onNodeWithText(EYE_PHONE_CANT, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun cantRunReasonIsOneLine() = oneLineEach(1.5f, EYE_PHONE_CANT)
}
