package io.github.odnwks.mimir.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * P11b: times under every message, the receipt circles on the owner's own, the model and effort beside the
 * time, a switch with its reason, the "Not read yet" line and the thinking dots. Pictures go to P11B_SHOT when set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P11bTimesTest {
    @get:Rule val compose = createComposeRule()
    private val out = System.getenv("P11B_SHOT")

    private val now = System.currentTimeMillis()
    private val opus = "claude-opus-5-5"
    private val sonnet = "claude-sonnet-5-5"

    private fun conversation(status: String = "Done", busy: Boolean = false, steps: List<String> = emptyList(), extra: List<TaskMessage> = emptyList()) = Task(
        id = "t1", title = "Timestamps", project = "Mímir", model = "Opus 5.5", age = "", steps = steps,
        messages = listOf(
            TaskMessage("t1-0", false, "Add tiny timestamps", atMs = now - 600_000, receipt = MessageReceipt.Read, model = opus),
            TaskMessage("t1-1", true, "Done: every message shows its time.", "Play the reply", atMs = now - 540_000, model = opus),
            TaskMessage("t1-2", false, "And read receipts please", atMs = now - 300_000, receipt = MessageReceipt.Delivered, model = opus),
            TaskMessage("t1-3", true, "Added to P11.", "Play the reply", atMs = now - 240_000, model = sonnet, effort = "medium",
                switched = "usage limit", switchedWhy = "Claude's usage limit ran out, so the task carried on on a model on your PC."),
            TaskMessage("t1-4", true, "Checked again.", "Play the reply", atMs = now - 200_000, model = opus, effort = "high", switched = REASON_NOT_REPORTED),
            TaskMessage("t1-5", false, "ok, show me a mock-up", atMs = now - 10_000, receipt = MessageReceipt.Sent, model = sonnet, effort = "medium"),
        ) + extra,
        question = null, permission = null, change = null, status = status, busy = busy, running = busy,
    )

    private fun show(t: Task, reduce: Boolean = true): (Task) -> Unit {
        val station = FakeStation(ExampleData.awake().copy(tasks = listOf(t)))
        var state by mutableStateOf(station.state.value)
        compose.setContent {
            CompositionLocalProvider(LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = reduce) { TaskScreen("t1", state, station, Navigator()) }
            }
        }
        compose.waitForIdle()
        return { next -> state = state.copy(tasks = listOf(next)); compose.waitForIdle() }
    }

    private fun rootWidth() = compose.onRoot().fetchSemanticsNode().boundsInRoot.width

    /** Nothing of the small print is cut off: inside the screen, and no text runs past its own box. */
    private fun assertNothingCut(tag: String) {
        val w = rootWidth()
        val nodes = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("some $tag shown", nodes.isNotEmpty())
        for (n in nodes) {
            assertTrue("$tag inside the screen (${n.boundsInRoot} in $w)", n.boundsInRoot.left >= 0f && n.boundsInRoot.right <= w + 0.5f)
        }
    }

    private fun assertNoTextOverflow() {
        val texts = compose.onAllNodesWithTag(META_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        for (n in texts) for (c in n.children) {
            val get = c.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
            val r = mutableListOf<TextLayoutResult>()
            get.action?.invoke(r)
            r.firstOrNull()?.let { assertFalse("the small print is not cut: ${c.config.getOrNull(SemanticsProperties.Text)}", it.hasVisualOverflow) }
        }
    }

    @Test fun eachMarkShowsUnderItsOwnMessage() {
        show(conversation())
        for (r in MessageReceipt.entries) {
            val mark = compose.onNodeWithTag(receiptTag(r), useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(receiptWords(r), mark.config[SemanticsProperties.ContentDescription].single())
            val sizeDp = mark.boundsInRoot.width / compose.density.density
            assertEquals("about 8 dp radius", 16f, sizeDp, 0.5f)
            // Right-aligned at the lane's right edge, under the blue bubble.
            assertTrue("the $r circle sits at the right", mark.boundsInRoot.right > rootWidth() * 0.85f)
        }
    }

    @Test fun repliesShowTheModelAndEffortBesideTheTime() {
        show(conversation())
        val reply = compose.onNodeWithText("Opus 5.5 · Default Effort · ${messageTime(now - 540_000)}", useUnmergedTree = true).fetchSemanticsNode()
        val listen = compose.onAllNodesWithText(LISTEN, useUnmergedTree = true).fetchSemanticsNodes().minBy { it.boundsInRoot.top }
        assertTrue("under the reply, by its Listen row", reply.boundsInRoot.top >= listen.boundsInRoot.top - 1f)
        // The owner's messages show what they went to, with no "to" in front.
        compose.onNodeWithText("Opus 5.5 · Default Effort · ${messageTime(now - 600_000)}", useUnmergedTree = true).assertExistsQuietly()
        compose.onNodeWithText("Sonnet 5.5 · Medium · ${messageTime(now - 10_000)}", useUnmergedTree = true).assertExistsQuietly()
    }

    @Test fun aSwitchSaysFromWhatAndWhyAndATapGivesTheFullReason() {
        show(conversation())
        compose.onNodeWithText("$SWITCH_MARK Sonnet 5.5 · Medium · ${messageTime(now - 240_000)}", useUnmergedTree = true).assertExistsQuietly()
        compose.onNodeWithText("Switched from Opus 5.5 · Default Effort: usage limit. Tap for more.").performClick()
        compose.onNodeWithText("Claude's usage limit ran out, so the task carried on on a model on your PC.").assertExistsQuietly()
    }

    @Test fun anUnknownReasonSaysReasonNotReportedNeverAGuess() {
        show(conversation())
        compose.onNodeWithText("Switched from Sonnet 5.5 · Medium: reason not reported. Tap for more.").performClick()
        compose.onNodeWithText(NOT_REPORTED_SENTENCE).assertExistsQuietly()
    }

    @Test fun anOldTaskShowsNoTimeRatherThanAWrongOne() {
        val old = conversation().let { t -> t.copy(messages = t.messages.map { TaskMessage(it.id, it.fromClaude, it.text, it.playLabel) }) }
        show(old)
        assertEquals(0, compose.onAllNodesWithTag(META_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        for (r in MessageReceipt.entries) assertEquals(0, compose.onAllNodesWithTag(receiptTag(r), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun aMinuteUnreadWhileWorkingSaysWhy() {
        val waiting = TaskMessage("t1-6", false, "are you there?", atMs = now - 61_000, receipt = MessageReceipt.Delivered, model = opus)
        val t = conversation(status = "Working", busy = true, steps = listOf("… Running the tests"), extra = listOf(waiting))
        show(t.copy(messages = t.messages.map { if (it.id == "t1-2") it.copy(receipt = MessageReceipt.Read) else it }))
        compose.onNodeWithText("Not read yet: Claude is busy with a step").assertExistsQuietly()
        // The message sent 10 s ago (Sent) doesn't say it yet; one already Read never does.
        assertEquals(1, compose.onAllNodesWithTag(NOT_READ_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun theThinkingBubbleShowsThenGoesWhenTheAnswerBegins() {
        val asked = conversation(status = "Working", busy = true)
        val update = show(asked)
        val dots = compose.onNodeWithTag(THINKING_TAG).fetchSemanticsNode()
        val last = compose.onNodeWithText("ok, show me a mock-up").fetchSemanticsNode()
        assertTrue("right under the owner's last message", dots.boundsInRoot.top > last.boundsInRoot.bottom)
        assertTrue("on the AI's side (left)", dots.boundsInRoot.left < rootWidth() / 4)
        update(asked.copy(messages = asked.messages + TaskMessage("t1-live", true, "Here it is")))
        assertEquals("the answer takes its place", 0, compose.onAllNodesWithTag(THINKING_TAG).fetchSemanticsNodes().size)
    }

    @Test fun noThinkingBubbleWhenTheTaskIsQuiet() {
        show(conversation())
        assertEquals(0, compose.onAllNodesWithTag(THINKING_TAG).fetchSemanticsNodes().size)
    }

    /** The highest bright pixel in the left third of the bubble (the first dot), and how bright that third is. */
    private fun firstDot(node: SemanticsNodeInteraction): Pair<Int, Long> {
        val b: Bitmap = node.captureToImage().asAndroidBitmap()
        var top = Int.MAX_VALUE; var sum = 0L
        for (y in 0 until b.height) for (x in 0 until b.width / 3) {
            val p = b.getPixel(x, y)
            val lum = ((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)
            if (lum > 3 * 60) { top = minOf(top, y); sum += lum }
        }
        return top to sum
    }

    private fun dots(reduce: Boolean) {
        compose.mainClock.autoAdvance = false
        show(conversation(status = "Working", busy = true), reduce)
        val node = compose.onNodeWithTag(THINKING_TAG)
        // The first dot's peak (a fifth of the way in), then resting (two thirds in).
        compose.mainClock.advanceTimeBy((MetaLook.DOTS_MS * 0.2).toLong())
        val up = firstDot(node)
        compose.mainClock.advanceTimeBy((MetaLook.DOTS_MS * 0.47).toLong())
        val rest = firstDot(node)
        if (!reduce) assertTrue("the dot rises (top $up vs $rest)", up.first + 3 <= rest.first)
        else {
            assertEquals("reduce motion: the dot stays put", rest.first.toFloat(), up.first.toFloat(), 1f)
            assertTrue("reduce motion: it fades instead (${up.second} vs ${rest.second})", up.second > rest.second)
        }
    }

    @Test fun theDotsRiseAndFall() = dots(reduce = false)
    @Test fun withReduceMotionTheDotsFadeInstead() = dots(reduce = true)

    @Test fun theDotsMoveInSmallStepsAtSixtyFramesASecond() {
        val frame = 1000f / 60f / MetaLook.DOTS_MS
        for (i in 0..2) {
            var t = 0f; var moved = false
            while (t < 1f) {
                val step = kotlin.math.abs(dotLift(t + frame, i) - dotLift(t, i))
                assertTrue("no jump between frames (dot $i at $t: $step)", step < 0.15f)
                if (dotLift(t, i) > 0.9f) moved = true
                t += frame
            }
            assertTrue("dot $i rises each cycle", moved)
        }
    }

    @Test fun timesReadLikeMessenger() {
        val zone = ZoneId.of("UTC")
        fun ms(d: LocalDateTime) = d.atZone(zone).toInstant().toEpochMilli()
        val nowAt = ms(LocalDateTime.of(2026, 11, 25, 18, 0))
        assertEquals("5:41 PM", messageTime(ms(LocalDateTime.of(2026, 11, 25, 17, 41)), nowAt, zone))
        assertEquals("12:05 AM", messageTime(ms(LocalDateTime.of(2026, 11, 25, 0, 5)), nowAt, zone))
        assertEquals("Tue 5:41 PM", messageTime(ms(LocalDateTime.of(2026, 11, 24, 17, 41)), nowAt, zone))
        assertEquals("Thu 9:03 AM", messageTime(ms(LocalDateTime.of(2026, 11, 19, 9, 3)), nowAt, zone))
        assertEquals("Nov 18, 5:41 PM", messageTime(ms(LocalDateTime.of(2026, 11, 18, 17, 41)), nowAt, zone))
        assertEquals("Dec 30, 2025, 5:41 PM", messageTime(ms(LocalDateTime.of(2025, 12, 30, 17, 41)), nowAt, zone))
        // A PC clock a little ahead of the phone's still reads as today.
        assertEquals("6:01 PM", messageTime(ms(LocalDateTime.of(2026, 11, 25, 18, 1)), nowAt, zone))
    }

    @Test fun modelsAndEffortsInWords() {
        assertEquals("Opus 5.5 · Default Effort", modelEffortWords("claude-opus-5-5", null))
        assertEquals("Sonnet 5.5 · Extra high", modelEffortWords("claude-sonnet-5-5", "xhigh"))
        assertEquals("Qwen 3.6 coding · High", modelEffortWords("qwen3.6:35b-a3b-coding", "high"))
        assertEquals(null, modelEffortWords(null, "high"))
    }

    private fun sizes(scale: String) {
        show(conversation(status = "Working", busy = true))
        assertNothingCut(META_TAG)
        assertNothingCut(THINKING_TAG)
        assertNoTextOverflow()
        out?.let { compose.onRoot().captureRoboImage("$it/p11b_${scale}.png") }
    }

    @Test fun atTextSize1() = sizes("390_1.0")
    @Config(fontScale = 1.3f) @Test fun atTextSize13() = sizes("390_1.3")
    @Config(qualifiers = "w320dp-h700dp-xhdpi") @Test fun at320() = sizes("320_1.0")
    @Config(qualifiers = "w320dp-h700dp-xhdpi", fontScale = 1.3f) @Test fun at320TextSize13() = sizes("320_1.3")
}

private fun SemanticsNodeInteraction.assertExistsQuietly() { fetchSemanticsNode() }
