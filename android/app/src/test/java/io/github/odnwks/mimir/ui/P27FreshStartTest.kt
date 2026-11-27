package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** P27: "Fresh start" in a task's menu, and the divider it leaves in the chat. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class P27FreshStartTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val id = ExampleData.TASK_ALLOW
    private val about = "Starts a new session in this chat from the hand-off. Saves usage on long chats."
    private val divider = "Fresh start at 22:41 · continues from the hand-off"

    private fun quiet(t: Task) = t.copy(busy = false, stopped = false, status = "Done", permission = null, question = null)

    private fun station(mut: (Task) -> Task = { it }) = FakeStation().also { st ->
        st.set { s -> s.copy(pcSessions = null, tasks = s.tasks.map { if (it.id == id) mut(quiet(it)) else it }) }
    }

    private fun show(st: FakeStation, menu: Boolean = true) {
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle(); nav.open(Screen.Task(id)); compose.waitForIdle()
        if (menu) { nav.openSheet(Sheet.TaskOptions(id)); compose.waitForIdle() }
    }

    private fun settle() { ShadowLooper.idleMainLooper(); compose.waitForIdle() }

    private fun noRow() {
        compose.onNodeWithText("Fresh start").assertDoesNotExist()
        compose.onNodeWithText(about).assertDoesNotExist()
    }

    @Test fun theMenuHasFreshStartWithItsLine_andATapAsksOnce() {
        val st = station()
        show(st)
        compose.onNodeWithText("Fresh start").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(about, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Fresh start").performClick(); settle()
        assertEquals(listOf(id), st.freshStarts)
        // The sheet closed.
        compose.onNodeWithText("Fresh start").assertDoesNotExist()
    }

    @Test fun anOlderPcHasNoRow() {
        val st = station().also { it.freshOk = false }
        show(st)
        noRow()
    }

    @Test fun aWhisperHasNoRow() {
        show(station { it.copy(whisper = true) })
        noRow()
    }

    @Test fun aChatHasNoRow() {
        show(station { it.copy(fromChats = true) })
        noRow()
    }

    @Test fun aTaskGoingOnElsewhereHasNoRow() {
        show(station { it.copy(liveElsewhere = true) })
        noRow()
    }

    @Test fun aTaskNotYetOnThePcHasNoRow() {
        show(station { it.copy(waitingToStart = true) })
        noRow()
    }

    @Test fun aRefusalSaysWhy() {
        val st = station().also { it.freshRefusal = "Waiting for you: 3 fresh starts in a row." }
        show(st)
        compose.onNodeWithText("Fresh start").performScrollTo().performClick(); settle()
        compose.onNodeWithText("Waiting for you: 3 fresh starts in a row.").assertIsDisplayed()
    }

    @Test fun whileItWorksItSaysWhenItHappens() {
        val st = station { it.copy(busy = true, status = "Working") }
        show(st)
        compose.onNodeWithText("Fresh start").performScrollTo().performClick(); settle()
        assertEquals(listOf(id), st.freshStarts)
        compose.onNodeWithText("It starts fresh when this answer is done.").assertIsDisplayed()
    }

    @Test fun theDividerShowsInTheChat_andOtherNotesStayPlain() {
        val st = station {
            it.copy(messages = it.messages + TaskMessage("n1", false, divider, note = true, atMs = 1_800_000_000_000L) +
                TaskMessage("n2", false, "A helper finished", note = true))
        }
        show(st, menu = false)
        compose.onNodeWithTag(FRESH_DIVIDER_TAG, useUnmergedTree = true).assertExists()
        assertEquals(1, compose.onAllNodesWithTag(FRESH_DIVIDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        // Its own words, with the time said once.
        compose.onNodeWithText(divider).assertExists()
        compose.onNodeWithText("A helper finished").assertExists()
    }

    @Test fun onlyThePcsDividerIsADivider() {
        assertTrue(isFreshStartNote(divider))
        assertTrue(isFreshStartNote("Fresh start at 07:05 · continues from the hand-off"))
        assertFalse(isFreshStartNote("Waiting for you: 3 fresh starts in a row."))
        assertFalse(isFreshStartNote("Fresh start at some point"))
    }

    @Test fun thePhoneSaysItKnowsFreshStart_andAsksForTheSameChat() {
        assertTrue("fresh_start" in Wire.FEATURES)
        val asked = JSONObject(Wire.freshStart("t")).getJSONObject("StartFresh")
        assertEquals("t", asked.getString("task"))
        assertTrue(asked.getBoolean("same_chat"))
        // The older "Start fresh with a summary" still makes a new task.
        assertFalse(JSONObject(Wire.startFresh("t")).getJSONObject("StartFresh").has("same_chat"))
    }
}
