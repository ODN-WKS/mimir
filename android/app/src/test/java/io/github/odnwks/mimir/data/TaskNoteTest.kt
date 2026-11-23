package io.github.odnwks.mimir.data

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.odnwks.mimir.link.LEntry
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.AppRoot
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A note from the PC shows in the task, as one small line. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class TaskNoteTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aNoteBecomesANoteMessage() {
        val m = taskMessageOf("t", 3, LEntry.Note("Followed from the terminal"), false)!!
        assertTrue(m.note)
        assertEquals("Followed from the terminal", m.text)
        assertEquals(false, m.fromClaude)
    }

    @Test fun theOutOfRoomNoteIsNotAMessage() {
        assertNull(taskMessageOf("t", 0, LEntry.Note("This free model ran out of room."), false))
        assertNull(taskMessageOf("t", 0, LEntry.Note(""), false))
    }

    @Test fun theTaskScreenShowsTheNote() {
        val task = ExampleData.awake().tasks.first().let {
            it.copy(messages = it.messages + TaskMessage("n1", false, "Stopped by the PC owner", note = true))
        }
        val base = ExampleData.awake()
        val st = FakeStation(base.copy(tasks = base.tasks.map { if (it.id == task.id) task else it }))
        val nav = Navigator()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        nav.open(Screen.Task(task.id)); compose.waitForIdle()
        compose.onNodeWithText("Stopped by the PC owner").assertIsDisplayed()
    }
}
