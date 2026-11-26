package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.data.ChatEntry
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.MessageReceipt
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
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
 * P15 (owner, 2026-11-25, mock-up 6 option A): no Copy or Share under any message (they stay in the hold menu);
 * under each finished reply one short row: a small "Listen" pill, then the tiny model, effort and time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P15ListenPillTest {
    @get:Rule val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val opus = "claude-opus-5-5"
    private val first = "The change is saved on its own branch."
    private val second = "Added. Each reply shows its model and time."

    private fun task(writing: Boolean) = Task(
        id = "t1", title = "Timestamps", project = "Mímir", model = "Opus 5.5", age = "", steps = emptyList(),
        messages = listOf(
            TaskMessage("t1-0", false, "it looks good, add timestamps", atMs = now - 600_000, receipt = MessageReceipt.Read, model = opus),
            TaskMessage("t1-1", true, first, "Play the reply", atMs = now - 540_000, model = opus),
            TaskMessage("t1-2", true, second, "Play the reply", atMs = now - 480_000, model = opus),
        ) + if (writing) listOf(TaskMessage("t1-3", true, "Now checking the colours")) else emptyList(),
        question = null, permission = null, change = null,
        status = if (writing) "Working" else "Done", busy = writing, running = writing,
    )

    private fun showTask(t: Task) {
        val station = FakeStation(ExampleData.awake().copy(tasks = listOf(t)))
        compose.setContent {
            CompositionLocalProvider(LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) {
                    val state by station.state.collectAsStateWithLifecycle()
                    TaskScreen("t1", state, station, Navigator())
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showChat() {
        val data = ExampleData.awake().copy(
            incognito = null,
            chat = listOf(
                ChatEntry.Mine("m1", "How is the build?", null),
                ChatEntry.Reply("r1", listOf(first), "Play"),
                ChatEntry.Reply("r2", listOf(second), "Play"),
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
    }

    /** Mímir's own chat: two whole answers and one still being written. */
    private fun showMimir() {
        compose.setContent {
            CompositionLocalProvider(LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) {
                    Column { Reply(first, dim = false); Reply(second, dim = false); Reply("Half an ans", dim = false, buttons = false) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun dp(px: Float) = px / compose.density.density
    private fun pills(): List<SemanticsNode> =
        compose.onAllNodesWithTag("listen-pill").fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }

    private fun assertNoCopyOrShareUnderReplies(where: String) {
        for (word in listOf(COPY, SHARE)) assertEquals("no $word button on the $where", 0, compose.onAllNodesWithText(word).fetchSemanticsNodes().size)
        for (d in listOf("Copy this answer", "Share this answer")) assertEquals("no '$d' on the $where", 0, compose.onAllNodesWithContentDescription(d).fetchSemanticsNodes().size)
    }

    private fun assertHoldMenuHasCopyAndShare(reply: String) {
        compose.onAllNodesWithText(reply, substring = true).onLast().performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        for (word in listOf(COPY, SHARE)) assertTrue("$word in the hold menu", compose.onAllNodesWithText(word).fetchSemanticsNodes().isNotEmpty())
    }

    /** One Listen pill per finished reply: [n] of them, each "Listen" once, about 26 dp high with a 48 dp touch area. */
    private fun assertPills(n: Int) {
        val p = pills()
        assertEquals("one Listen pill per finished reply", n, p.size)
        assertEquals("Listen shows once per finished reply", n, compose.onAllNodesWithText(LISTEN).fetchSemanticsNodes().size)
        for (pill in p) {
            val h = dp(pill.boundsInRoot.height)
            assertTrue("the pill is about 26 dp high (was $h dp)", h in 22f..30f)
            val touch = dp(pill.touchBoundsInRoot.height)
            assertTrue("the pill has a 48 dp touch area (was $touch dp)", touch >= 47.5f)
        }
    }

    /** The meta lines (the tiny model, effort and time) with their text layouts. */
    private fun metaTexts(): List<Pair<Rect, TextLayoutResult>> =
        compose.onAllNodesWithTag(META_TAG, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
            n.children.mapNotNull { c ->
                val get = c.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return@mapNotNull null
                val r = mutableListOf<TextLayoutResult>()
                get.action?.invoke(r)
                r.firstOrNull()?.let { c.boundsInRoot to it }
            }
        }

    private fun assertMetaTinyAndWhole() {
        val w = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val metas = metaTexts()
        assertTrue("some small print shown", metas.isNotEmpty())
        for ((at, layout) in metas) {
            assertEquals("the small print is 11 sp", 11f, layout.layoutInput.style.fontSize.value, 0.01f)
            assertFalse("the small print is not cut: ${layout.layoutInput.text}", layout.hasVisualOverflow)
            assertTrue("the small print is inside the screen ($at in $w)", at.left >= 0f && at.right <= w + 0.5f)
        }
        for (pill in pills()) assertTrue("the pill is inside the screen", pill.boundsInRoot.left >= 0f && pill.boundsInRoot.right <= w + 0.5f)
    }

    // ---- No Copy or Share under any message; the hold menu keeps them ----

    @Test fun theTaskScreenHasNoCopyOrShareUnderAReplyButTheHoldMenuDoes() {
        showTask(task(writing = false))
        assertNoCopyOrShareUnderReplies("task screen")
        assertHoldMenuHasCopyAndShare(second)
    }

    @Test fun theChatScreenHasNoCopyOrShareUnderAReplyButTheHoldMenuDoes() {
        showChat()
        assertNoCopyOrShareUnderReplies("chat screen")
        assertHoldMenuHasCopyAndShare(second)
    }

    @Test fun mimirsChatHasNoCopyOrShareUnderAReplyButTheHoldMenuDoes() {
        showMimir()
        assertNoCopyOrShareUnderReplies("Mímir chat")
        assertHoldMenuHasCopyAndShare(second)
    }

    // ---- One Listen pill per finished reply ----

    @Test fun oneListenPillPerFinishedTaskReplyNoneWhileWriting() {
        showTask(task(writing = true))
        assertPills(2)
        val bubbles = compose.onAllNodesWithTag(REPLY_BUBBLE_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }
        assertEquals(3, bubbles.size)
        val p = pills()
        // Each pill right under its own bubble, none under the reply being written.
        assertTrue(p[0].boundsInRoot.top >= bubbles[0].bottom && p[0].boundsInRoot.bottom <= bubbles[1].top)
        assertTrue(p[1].boundsInRoot.top >= bubbles[1].bottom && p[1].boundsInRoot.bottom <= bubbles[2].top)
    }

    @Test fun oneListenPillPerChatReply() {
        showChat()
        assertPills(2)
    }

    @Test fun oneListenPillPerFinishedMimirAnswerNoneWhileWriting() {
        showMimir()
        assertPills(2)
    }

    // ---- The pill and the small print share one row ----

    @Test fun thePillAndTheSmallPrintShareOneRowAt390() {
        showTask(task(writing = false))
        assertPills(2)
        val metas = metaTexts().map { it.first }
        for (pill in pills()) {
            val b = pill.boundsInRoot
            val beside = metas.filter { it.top < b.bottom && it.bottom > b.top && it.left >= b.right }
            assertEquals("the model, effort and time sit beside the pill, in its row", 1, beside.size)
            assertEquals("the small print is one line beside the pill", 1, metaTexts().first { it.first == beside[0] }.second.lineCount)
        }
        assertMetaTinyAndWhole()
        // The row is short: the pill sits snug under its bubble (mock-up: about 5 dp).
        val bubbles = compose.onAllNodesWithTag(REPLY_BUBBLE_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }
        val gap = dp(pills()[0].boundsInRoot.top - bubbles[0].bottom)
        assertTrue("the pill sits close under the bubble (was $gap dp)", gap <= 8f)
    }

    @Config(qualifiers = "w320dp-h700dp-xhdpi", fontScale = 1.3f)
    @Test fun at320AndTextSize13NothingIsCut() {
        showTask(task(writing = false))
        assertPills(2)
        assertMetaTinyAndWhole()
        for (pill in pills()) {
            assertEquals(LISTEN, pill.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
        }
    }
}
