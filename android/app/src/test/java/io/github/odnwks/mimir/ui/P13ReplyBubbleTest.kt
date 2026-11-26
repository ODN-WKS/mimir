package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.data.ChatEntry
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P13 (owner-found 2026-11-25): every AI reply sits in its own grey bubble, so two replies in a row
 * read as two. Listen, Copy and Share are on screen under a finished reply, not under one being written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P13ReplyBubbleTest {
    @get:Rule val compose = createComposeRule()

    private fun bubbles(): List<Rect> =
        compose.onAllNodesWithTag("reply-bubble", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }

    private fun tops(text: String): List<Float> =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().map { it.boundsInRoot.top }

    private fun assertSeparate(b: List<Rect>) {
        assertEquals("two replies, two bubbles", 2, b.size)
        val gapDp = (b[1].top - b[0].bottom) / compose.density.density
        assertTrue("a gap of at least 8 dp between the bubbles (was $gapDp dp)", gapDp >= 8f)
    }

    @Test fun twoTaskRepliesInARowHaveTwoBubblesAndOnlyTheFinishedOneHasItsButtons() {
        val t = Task(
            id = "t1", title = "Fix the title", project = "Recipe box", model = "Sonnet", age = "", steps = emptyList(),
            messages = listOf(
                TaskMessage("t1-0", false, "Make the title bigger"),
                TaskMessage("t1-1", true, "Done: the title is bigger now.", "Play the reply"),
                // Still being written: no play label yet.
                TaskMessage("t1-2", true, "Now checking the colours"),
            ),
            question = null, permission = null, change = null, status = "Working", busy = true, running = true,
        )
        val base = ExampleData.awake()
        val station = FakeStation(base.copy(tasks = listOf(t)))
        compose.setContent {
            CompositionLocalProvider(LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) {
                    val state by station.state.collectAsStateWithLifecycle()
                    TaskScreen("t1", state, station, Navigator())
                }
            }
        }
        compose.waitForIdle()
        val b = bubbles()
        assertSeparate(b)
        // Listen, Copy and Share: once each, under the finished reply and above the one still being written.
        for (word in listOf(LISTEN, COPY, SHARE)) {
            val at = tops(word)
            assertEquals("$word once, under the finished reply only", 1, at.size)
            assertTrue("$word sits under the first bubble", at[0] >= b[0].bottom)
            assertTrue("$word sits above the reply being written", at[0] < b[1].top)
        }
    }

    @Test fun twoChatRepliesInARowHaveTwoBubblesEachWithItsButtons() {
        val data = ExampleData.awake().copy(
            incognito = null,
            chat = listOf(
                ChatEntry.Mine("m1", "How is the build?", null),
                ChatEntry.Reply("r1", listOf("The build passed."), "Play"),
                ChatEntry.Reply("r2", listOf("The tests passed too."), "Play"),
            ),
        )
        val station = FakeStation(data)
        compose.setContent {
            CompositionLocalProvider(LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) {
                    val state by station.state.collectAsStateWithLifecycle()
                    ChatScreen(state, station, Navigator())
                }
            }
        }
        compose.waitForIdle()
        val b = bubbles()
        assertSeparate(b)
        for (word in listOf(LISTEN, COPY, SHARE)) {
            val at = tops(word).sorted()
            assertEquals("$word under each finished reply", 2, at.size)
            assertTrue("$word under the first bubble, above the second", at[0] >= b[0].bottom && at[0] < b[1].top)
            assertTrue("$word under the second bubble", at[1] >= b[1].bottom)
        }
    }
}
