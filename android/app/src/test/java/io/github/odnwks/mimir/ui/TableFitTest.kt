package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tables share the phone's width and wrap their cells; only a many-column table scrolls, and it says so. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h900dp-xhdpi")
class TableFitTest {
    @get:Rule val compose = createComposeRule()

    private val long = listOf(
        "Choose a spot, collect materials and plan the beds for the season",
        "Compost and mulch for healthy soil all through the year",
    )
    private val two = "| Step | What to do |\n|---|---|\n" + long.mapIndexed { i, t -> "| Step ${i + 1} | $t |" }.joinToString("\n")

    private fun show(md: String, widthDp: Int, scale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, scale)) {
                MimirTheme { MarkdownText(md, Type.Body, Palette.Text, Modifier.width(widthDp.dp)) }
            }
        }
    }

    private fun assertAllWordsShown(widthDp: Int) {
        for (t in long + listOf("Step 1", "What to do")) {
            val n = compose.onNodeWithText(t)
            n.assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            n.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            assertTrue("clipped: $t", results.none { it.hasVisualOverflow })
            assertTrue("past the edge: $t", n.getBoundsInRoot().right <= widthDp.dp)
        }
        compose.onNodeWithTag("md-table").getBoundsInRoot().let { assertTrue(it.right <= widthDp.dp) }
    }

    @Test fun twoColumnsFitAt339() { show(two, 339, 1.3f); assertAllWordsShown(339) }
    @Test fun twoColumnsFitAt360() { show(two, 360, 1.3f); assertAllWordsShown(360) }
    @Test fun twoColumnsFitAt360NormalText() { show(two, 360, 1.0f); assertAllWordsShown(360) }

    @Test fun aSevenColumnTableScrollsAndSaysSo() {
        val head = (1..7).joinToString(" | ", "| ", " |") { "Column$it" }
        val md = head + "\n" + (1..7).joinToString("|", "|", "|") { "---" } + "\n" + (1..7).joinToString(" | ", "| ", " |") { "Longword$it" }
        show(md, 339, 1.3f)
        compose.onNodeWithTag("md-table-hint").assertIsDisplayed()
    }

    @Test fun aTwoColumnTableNeedsNoScrollHint() {
        show(two, 360, 1.0f)
        assertTrue(compose.onAllNodesWithTagCount("md-table-hint") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
