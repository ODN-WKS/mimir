package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P10: the compact message box (one row, mic and Send drawn small, touched at 48 dp). That box is
 * Mímir's chat box; a task's reply box is the owner's 2026-11-25 layout (words, then +, model and
 * effort, and a small Send in one row), whose Send is still touched at 48 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P10ComposerTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()

    private fun showChat() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        val st = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        }
        compose.waitForIdle()
    }

    private fun showTask() {
        val st = FakeStation()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle(); nav.open(Screen.Task(ExampleData.TASK_ALLOW)); compose.waitForIdle()
    }

    private fun dp(px: Float) = px / compose.density.density

    private fun touchedAt48(what: String, node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        val b = node.fetchSemanticsNode().boundsInRoot
        assertTrue("$what is ${dp(b.width)} x ${dp(b.height)} dp", dp(b.width) >= 47.9f && dp(b.height) >= 47.9f)
    }

    // P12: the standard box (the task's) has two rows, the words and then +, the chip and Send, so the
    // one-row limit of 68 dp became the two rows' 48 dp each plus the edges.
    @Test fun theEmptyChatBoxIsAtMost100DpHigh() {
        showChat()
        val h = dp(compose.onNodeWithTag(COMPOSER_TAG).fetchSemanticsNode().boundsInRoot.height)
        assertTrue("composer is $h dp high", h <= 100f)
    }

    @Test fun theChatBoxsMicAndAttachAreTouchedAt48DpOrMore() {
        showChat()
        touchedAt48("Attach", compose.onNodeWithContentDescription("Attach"))
        touchedAt48("mic", compose.onNode(hasContentDescription("Talk", substring = true)))
    }

    @Test fun aTasksSendIsTouchedAt48DpOrMore() {
        showTask()
        touchedAt48("Send", compose.onNodeWithContentDescription("Send"))
    }
}
