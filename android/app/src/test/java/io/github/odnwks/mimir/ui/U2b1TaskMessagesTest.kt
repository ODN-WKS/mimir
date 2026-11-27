package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** U2b-1: a task's messages have the chat's Copy, Listen, Share, Try again and Edit. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class U2b1TaskMessagesTest {
    @get:Rule val compose = createComposeRule()

    private fun task(status: String = "Done", busy: Boolean = false) = Task(
        id = "t1", title = "Fix the title", project = "Recipe box", model = "Sonnet", age = "", steps = emptyList(),
        messages = listOf(
            TaskMessage("t1-0", false, "Make the title bigger"),
            TaskMessage("t1-1", true, "Done: the title is **bigger** now.", "Play the reply"),
        ),
        question = null, permission = null, change = null, status = status, busy = busy, running = busy,
    )

    private fun open(t: Task, voice: FakeReadAloud = FakeReadAloud(), pcOnline: Boolean = true): FakeStation {
        val base = ExampleData.awake()
        val station = FakeStation(base.copy(tasks = listOf(t), pc = base.pc.copy(online = pcOnline)))
        compose.setContent {
            CompositionLocalProvider(LocalReadAloud provides voice) {
                MimirTheme(reduceMotion = true) {
                    val state by station.state.collectAsStateWithLifecycle()
                    TaskScreen("t1", state, station, Navigator())
                }
            }
        }
        compose.waitForIdle()
        return station
    }

    /** P37: a long press on the words selects them; the message menu opens from the bubble's edge. */
    private fun TouchInjectionScope.bubbleEdge() = Offset(4f, height / 2f)

    @Test fun listenUsesThePcVoiceWhileThePcIsThere() {
        val station = open(task())
        compose.holdAnswerAndPick("the title is bigger now", "Listen")
        assertEquals(listOf("t1-1"), station.played)
    }

    @Test fun aReplysHoldMenuHasCopyListenAndShare() {
        val voice = FakeReadAloud()
        open(task(), voice, pcOnline = false)
        // P15: no Copy button under the reply; the long-press menu holds Copy, Share and Listen.
        compose.onNodeWithContentDescription("Copy this answer").assertDoesNotExist()
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onAllNodesWithText("the title is bigger now", substring = true).onLast().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitForIdle()
        for (i in listOf("Copy", "Share", "Listen")) compose.onAllNodesWithText(i).onLast().assertIsDisplayed()
        compose.onAllNodesWithText("Listen").onLast().performClick()
        // The markdown marks are gone from what is read.
        assertEquals(listOf("Done: the title is bigger now."), voice.read)
    }

    @Test fun tryAgainSendsTheLastMessageAgain() {
        val station = open(task())
        compose.holdAnswerAndPick("the title is bigger now", "Try again")
        assertEquals(listOf("Make the title bigger"), station.replied)
    }

    @Test fun tryAgainIsHiddenWhileTheTaskWorks() {
        open(task("Working", busy = true))
        compose.onAllNodesWithContentDescription("Ask again: try this answer again").assertCountEquals(0)
    }

    @Test fun editSendsTheEditedWordsForThatMessage() {
        val station = open(task())
        compose.onAllNodesWithText("Make the title bigger")[0].performTouchInput { longClick(bubbleEdge()) }
        compose.onNodeWithText("Edit").performClick()
        // It says plainly that later replies are replaced.
        compose.onNodeWithTag("edit-note").assertIsDisplayed()
        compose.onNodeWithTag("edit-box").performTextReplacement("Make the title much bigger")
        compose.onNodeWithContentDescription("Send the edited message").performClick()
        assertEquals(listOf(Triple("t1", "t1-0", "Make the title much bigger")), station.edited)
    }

    @Test fun editIsHiddenWhileTheTaskWorks() {
        open(task("Working", busy = true))
        compose.onAllNodesWithText("Make the title bigger")[0].performTouchInput { longClick(bubbleEdge()) }
        compose.onAllNodesWithText("Edit").assertCountEquals(0)
    }

    @Test fun editGoesOutAsUndoToThatEntryThenSend() {
        // The wire shapes the PC reads, and where a message sits in the PC's list.
        assertEquals(JSONObject("""{"Undo":{"task":"t1","entry":0}}""").toString(), JSONObject(Wire.undo("t1", 0)).toString())
        assertEquals(0, Wire.entryIndex("t1", "t1-0"))
        assertEquals(4, Wire.entryIndex("t1", "t1-4"))
        assertNull(Wire.entryIndex("t1", "t1-live"))
        assertNull(Wire.entryIndex("t1", "s-123"))
    }

    @Test fun aRefusedEditOpensTheBoxAgainWithTheWordsAndTheReason() {
        val base = ExampleData.awake()
        val station = FakeStation(base.copy(tasks = listOf(task()), pc = base.pc))
        station.editRefusal = "There is no snapshot from before that message."
        compose.setContent {
            CompositionLocalProvider(LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) {
                    val state by station.state.collectAsStateWithLifecycle()
                    TaskScreen("t1", state, station, Navigator())
                }
            }
        }
        compose.onAllNodesWithText("Make the title bigger")[0].performTouchInput { longClick(bubbleEdge()) }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("edit-box").performTextReplacement("Kept words")
        compose.onNodeWithContentDescription("Send the edited message").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("There is no snapshot from before that message.").assertIsDisplayed()
        compose.onNodeWithText("Kept words").assertIsDisplayed()
        assertEquals(emptyList<String>(), station.replied)
    }

    @Test fun tryAgainIsHiddenWhileAMessageWaitsAndGoesAfterOneTap() {
        val waiting = task().copy(messages = task().messages + TaskMessage("s-1", false, "A newer one", waiting = true))
        open(waiting)
        compose.onAllNodesWithContentDescription("Ask again: try this answer again").assertCountEquals(0)
    }

    @Test fun tryAgainCanBeTappedOnlyOnce() {
        val station = open(task())
        compose.holdAnswerAndPick("the title is bigger now", "Try again")
        compose.onAllNodesWithText("the title is bigger now", substring = true).onLast().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
        assertEquals(1, station.replied.size)
    }
}
