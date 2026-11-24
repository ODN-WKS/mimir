package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P10: the compact reply box (one row, mic and Send drawn small, touched at 48 dp). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P10ComposerTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()

    private fun show() {
        val st = FakeStation()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle(); nav.open(Screen.Task(ExampleData.TASK_ALLOW)); compose.waitForIdle()
    }

    private fun dp(px: Float) = px / compose.density.density

    @Test fun theEmptyBoxIsAtMost68DpHigh() {
        show()
        val h = dp(compose.onNodeWithTag(COMPOSER_TAG).fetchSemanticsNode().boundsInRoot.height)
        assertTrue("composer is $h dp high", h <= 68f)
    }

    @Test fun micAndSendAreTouchedAt48DpOrMore() {
        show()
        for (d in listOf("Send", "Attach")) {
            val b = compose.onNodeWithContentDescription(d).fetchSemanticsNode().boundsInRoot
            assertTrue("$d is ${dp(b.width)} x ${dp(b.height)} dp", dp(b.width) >= 47.9f && dp(b.height) >= 47.9f)
        }
        val mic = compose.onNode(androidx.compose.ui.test.hasContentDescription("Talk", substring = true)).fetchSemanticsNode().boundsInRoot
        assertTrue("mic is ${dp(mic.width)} x ${dp(mic.height)} dp", dp(mic.width) >= 47.9f && dp(mic.height) >= 47.9f)
    }
}
