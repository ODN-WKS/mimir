package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.Check
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
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
 * P14 (owner, 2026-11-25): the project page's checks card is one slim line above Tasks; the full
 * list opens from the page's three dots ("Checks (7)") and from a tap on the line.
 * Pictures of the page are written only when P14_SHOT names a folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P14ProjectChecksTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val out = System.getenv("P14_SHOT")

    // The tag of the slim line (ProjectScreen's PROJECT_CHECKS_TAG), written out so this test builds on the commit before.
    private val line = "project-checks"
    private val seven = (1..7).map { Check("Check number $it", true) }
    private val passingNames = listOf("Messages arrive in order", "Audio calls on Wi-Fi", "Video calls", "Sending pictures")

    private fun open(project: String): Navigator {
        // The example "Mímir" project counts 7 passing checks; here it lists them too, as the owner's PC does.
        val st = FakeStation().also { f -> f.set { s -> s.copy(projects = s.projects.map { if (it.name == "Mímir") it.copy(checks = seven) else it }) } }
        val nav = Navigator()
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } } }
        compose.waitForIdle()
        compose.runOnIdle { nav.open(Screen.Project(project)) }
        settle()
        return nav
    }

    private fun settle() { compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle() }

    private fun shows(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun assertSheetListsAllSeven() {
        assertTrue("the list says how the checks stand", shows("All 7 checks passing"))
        seven.forEach { assertTrue("the list names ${it.name}", shows(it.name)) }
    }

    @Test fun allPassingIsOneSlimLineWithNothingBelowIt() {
        open("Mímir")
        val node = compose.onAllNodesWithTag(line, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("one slim checks line", 1, node.size)
        val heightDp = node[0].boundsInRoot.height / compose.density.density
        assertTrue("the line is at most 48 dp high (it is $heightDp)", heightDp <= 48f)
        assertTrue("it says how the checks stand", shows("All 7 checks passing"))
        seven.forEach { assertTrue("${it.name} is not listed on the page", !shows(it.name)) }
    }

    @Test fun failingChecksAreNamedOnThePage() {
        open("Budget-App")
        assertEquals(1, compose.onAllNodesWithTag(line, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue("it says how many fail", shows("1 of 5 checks failing"))
        val inLine = { t: String -> compose.onAllNodes(hasText(t, substring = true) and hasAnyAncestor(hasTestTag(line)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the failing check is named", inLine("Calls on cell signal: fails"))
        passingNames.forEach { assertTrue("passing $it is not named on the line", !inLine(it)) }
    }

    @Test fun theThreeDotsOpenTheFullList() {
        open("Mímir")
        compose.onNodeWithContentDescription("Options for Mímir").performClick(); settle()
        compose.onNodeWithText("Checks (7)").performClick(); settle()
        assertSheetListsAllSeven()
    }

    @Test fun tappingTheLineOpensTheSameList() {
        open("Mímir")
        compose.onNodeWithTag(line).performClick(); settle()
        assertSheetListsAllSeven()
    }

    private fun assertNothingCut(where: String) {
        val texts = compose.onAllNodes(hasText("", substring = true) and hasAnyAncestor(hasTestTag(line)), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the line has words", texts.isNotEmpty())
        texts.forEach { n ->
            val layouts = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val words = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            layouts.forEach { l ->
                assertTrue("\"$words\" is cut off on $where (${l.size.width} of ${l.layoutInput.constraints.maxWidth} px)",
                    (0 until l.lineCount).none { l.isLineEllipsized(it) } && l.size.width <= l.layoutInput.constraints.maxWidth)
            }
        }
    }

    @Config(qualifiers = "w320dp-h700dp-xhdpi", fontScale = 1.3f)
    @Test fun narrowAndLargeTextCutsNothing() {
        open("Mímir")
        assertNothingCut("Mímir")
        compose.onNodeWithTag(line).performClick(); settle()
        assertSheetListsAllSeven()
    }

    @Config(qualifiers = "w320dp-h700dp-xhdpi", fontScale = 1.3f)
    @Test fun narrowAndLargeTextCutsNothingWhenFailing() {
        open("Budget-App")
        assertNothingCut("Budget-App")
    }

    /** The before and after pictures (no checks here, so the same test takes both). */
    @Test fun pictures() {
        val o = out ?: return
        open("Mímir"); compose.onRoot().captureRoboImage("$o/p14_mimir_all_passing.png")
    }

    @Test fun picturesFailing() {
        val o = out ?: return
        open("Budget-App"); compose.onRoot().captureRoboImage("$o/p14_budget_failing.png")
    }

    @Test fun pictureOfTheSheet() {
        val o = out ?: return
        open("Mímir")
        compose.onNodeWithContentDescription("Options for Mímir").performClick(); settle()
        compose.onRoot().captureRoboImage("$o/p14_three_dots.png")
    }
}
