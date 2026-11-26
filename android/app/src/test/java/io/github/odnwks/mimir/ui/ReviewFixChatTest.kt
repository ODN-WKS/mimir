package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.ComposeTo
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review fixes for the one reply box in Chat: a Whisper in Chat keeps a way to start voice typing
 * (its three dots are hidden), and the model sheet in Chat can go back to the usual model, so an
 * everyday message is not turned into a task for good.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class ReviewFixChatTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()

    private fun show() {
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(FakeStation(), nav) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
    }

    @Test fun aWhisperInChatHasTheMicInTheBox() {
        nav.composeTo = ComposeTo(whisper = true)
        show()
        val composer = compose.onNodeWithTag(COMPOSER_TAG).fetchSemanticsNode().boundsInRoot
        val mics = compose.onAllNodes(hasContentDescription("Talk (voice typing)", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { it.boundsInRoot.overlaps(composer) }
        assertTrue("a Whisper in Chat has the mic in the box", mics.isNotEmpty())
    }

    @Test fun choosingTheUsualModelInChatGoesBackToAuto() {
        show()
        compose.onNodeWithContentDescription("Model and effort for this task: Auto · Default Effort. Change").performClick(); compose.waitForIdle()
        compose.onNode(hasText("Opus 5.5") and isSelectable()).performClick(); compose.waitForIdle()
        assertFalse("another model makes it a task", nav.composeTo.auto)
        compose.onNode(hasText("Auto") and isSelectable()).performClick(); compose.waitForIdle()
        assertEquals("the usual model empties the choice", "", nav.composeTo.model)
        assertTrue("an everyday message again", nav.composeTo.auto)
    }
}
