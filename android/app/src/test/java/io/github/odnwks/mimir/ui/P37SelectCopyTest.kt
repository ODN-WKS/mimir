package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.data.ChatEntry
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.IncognitoTask
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.MimirTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** The loupe over a selection has no window in these tests and fails when it closes: it does nothing here. */
@Implements(android.widget.Magnifier::class)
class NoMagnifier {
    @Implementation fun show(x: Float, y: Float) {}
    @Implementation fun show(x: Float, y: Float, atX: Float, atY: Float) {}
    @Implementation fun update() {}
    @Implementation fun dismiss() {}
}

/** P37 (owner-found 2026-11-26): words of any bubble can be selected by a long press and copied on their own. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxxhdpi", shadows = [NoMagnifier::class])
class P37SelectCopyTest {
    @get:Rule val compose = createComposeRule()

    /** Stands for the phone's floating selection bar: keeps what it was asked to offer. */
    private class Bar : TextContextMenuProvider {
        var data: TextContextMenuDataProvider? = null
        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            data = dataProvider
            try { awaitCancellation() } finally { data = null }
        }
        fun items(): List<TextContextMenuItem> = data?.data()?.components?.filterIsInstance<TextContextMenuItem>().orEmpty()
    }

    private val bar = Bar()
    private var clip: ClipboardManager? = null

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            clip = LocalClipboardManager.current
            CompositionLocalProvider(LocalReadAloud provides FakeReadAloud(), LocalTextContextMenuToolbarProvider provides bar) {
                MimirTheme(reduceMotion = true) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun holdWords(text: String) {
        compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).onLast().performTouchInput { longClick(center) }
        compose.waitForIdle()
    }

    private fun pick(label: String) {
        val item = bar.items().single { it.label == label }
        compose.runOnIdle { item.onClick.invoke(object : TextContextMenuSession { override fun close() {} }) }
        compose.waitForIdle()
    }

    private fun assertNoMessageMenu() {
        for (item in listOf(SHARE, SELECT_TEXT, "Read aloud")) assertTrue("the message menu stays shut ($item)", compose.onAllNodesWithText(item).fetchSemanticsNodes().isEmpty())
    }

    /** A long press selects words of [text]; Copy takes only them. */
    private fun assertCopiesPart(text: String) {
        compose.runOnIdle { clip!!.setText(androidx.compose.ui.text.AnnotatedString("")) }
        holdWords(text)
        assertTrue("Copy offered for $text: ${bar.items().map { it.label }}", bar.items().any { it.label == "Copy" })
        assertNoMessageMenu()
        pick("Copy")
        val got = clip!!.getText()?.text.orEmpty().trim()
        assertTrue("a part of the message was copied, got '$got'", got.isNotEmpty() && got.length < text.length && text.contains(got))
    }

    private val ask = "alpha bravo charlie delta echo"
    private val answer = "foxtrot golf hotel india juliett"
    private val step = "kilo lima mike november oscar"

    private fun showTask() {
        val task = Task(
            id = "t1", title = "Beds", project = "Garden planner", model = "Sonnet", age = "", steps = listOf(step),
            messages = listOf(TaskMessage("t1-0", false, ask), TaskMessage("t1-1", true, answer, "Play the reply")),
            question = null, permission = null, change = null, status = "Done", busy = false, running = false,
        )
        val base = ExampleData.awake()
        val station = FakeStation(base.copy(tasks = listOf(task)))
        show {
            val state by station.state.collectAsStateWithLifecycle()
            TaskScreen("t1", state, station, Navigator())
        }
    }

    @Test fun myMessageInATaskCopiesInPart() { showTask(); assertCopiesPart(ask) }

    @Test fun aReplyInATaskCopiesInPart() { showTask(); assertCopiesPart(answer) }

    @Test fun aStepCopiesInPart() {
        showTask()
        compose.onNodeWithText("Show").performClick(); compose.waitForIdle()
        assertCopiesPart(step)
    }

    @Test fun theSelectionBarsMoreOpensTheWholeMessageActions() {
        showTask()
        holdWords(ask)
        pick(MORE_ITEM)
        for (item in listOf(EDIT, COPY, SHARE, LISTEN_ITEM)) assertTrue(item, compose.onAllNodesWithText(item).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText(COPY).performClick(); compose.waitForIdle()
        assertEquals(ask, clip!!.getText()?.text)
    }

    @Test fun holdingTheBubblesEdgeStillOpensTheMessageMenu() {
        showTask()
        compose.onNodeWithTag(REPLY_BUBBLE_TAG).performTouchInput { longClick(Offset(4f, height / 2f)) }
        compose.waitForIdle()
        for (item in listOf(COPY, SELECT_TEXT, SHARE)) assertTrue(item, compose.onAllNodesWithText(item).fetchSemanticsNodes().isNotEmpty())
    }

    /** The keyboard stays as it did before: a tap on a bubble keeps it, a tap on the steps closes it. */
    @Test fun tapsKeepOrCloseTheKeyboardAsBefore() {
        showTask()
        val box = compose.onNode(hasSetTextAction())
        box.performClick(); compose.waitForIdle(); box.assertIsFocused()
        for (words in listOf(ask, answer)) {
            compose.onNodeWithText(words, useUnmergedTree = true).performTouchInput { click(center) }
            compose.waitForIdle(); box.assertIsFocused()
        }
        compose.onNodeWithText("Show").performClick(); compose.waitForIdle()
        box.performClick(); compose.waitForIdle()
        compose.onNodeWithText(step, useUnmergedTree = true).performTouchInput { click(center) }
        compose.waitForIdle(); box.assertIsNotFocused()
    }

    private fun showChat() {
        val data = ExampleData.awake().copy(incognito = null, chat = listOf(ChatEntry.Mine("m1", ask, null), ChatEntry.Reply("r1", listOf(answer), "Play")))
        val station = FakeStation(data)
        show {
            val state by station.state.collectAsStateWithLifecycle()
            ChatScreen(state, station, Navigator())
        }
    }

    @Test fun myMessageInTheMainChatCopiesInPart() { showChat(); assertCopiesPart(ask) }

    @Test fun aReplyInTheMainChatCopiesInPart() { showChat(); assertCopiesPart(answer) }

    /** The phone-model screens use the same two bubbles. */
    @Test fun thePhoneModelsBubblesCopyInPart() {
        show { Column { OwnMessage(ask); Reply(answer, dim = false) } }
        assertCopiesPart(ask)
        assertCopiesPart(answer)
    }

    @Test fun anAnswerStillBeingWrittenCopiesInPartAndHasNoMore() {
        show { Column { Reply(answer, dim = false, buttons = false) } }
        assertCopiesPart(answer)
        holdWords(answer)
        assertTrue(bar.items().none { it.label == MORE_ITEM })
    }

    @Test fun aWhisperTasksBubblesCopyInPart() {
        val base = ExampleData.awake()
        val station = FakeStation(base.copy(incognito = IncognitoTask("Beds", "Recipe box", "Sonnet 5.5", ask, answer, "Play the reply", id = "w1")))
        show {
            val state by station.state.collectAsStateWithLifecycle()
            IncognitoTaskScreen(state, station, Navigator())
        }
        assertCopiesPart(ask)
        assertCopiesPart(answer)
    }

    @Test fun aLinkInAReplyStillOpensOnATap() {
        var opened: String? = null
        show {
            CompositionLocalProvider(LocalOpenPage provides { url: String -> opened = url }) {
                Column { Reply("[the garden page](https://example.com/beds)", dim = false) }
            }
        }
        compose.onNodeWithText("the garden page", useUnmergedTree = true).performTouchInput { click(centerLeft + Offset(10f, 0f)) }
        compose.waitForIdle()
        assertEquals("https://example.com/beds", opened)
    }
}
