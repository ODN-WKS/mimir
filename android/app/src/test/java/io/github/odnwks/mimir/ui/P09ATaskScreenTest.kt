package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Question
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class P09ATaskScreenTest {
    @Test fun p09Item1AttachedPathsBecomeNames() {
        val (words, names) = AttachedNames.split("Look at these\n\nAttached: /home/x/task-files/t-1/1-plan.pdf, /home/x/task-files/t-1/2-photo.png")
        assertEquals("Look at these", words)
        assertEquals(listOf("plan.pdf", "photo.png"), names)
        assertEquals("no tail" to emptyList<String>(), AttachedNames.split("no tail"))
    }

    @Test fun p09Item3DeleteTaskCallAndStation() {
        val st = FakeStation()
        var result: String? = "unset"
        st.deleteTask("nope") { result = it }
        assertEquals(null, result)
        st.deleteRefusal = "It is working."
        st.deleteTask("nope") { result = it }
        assertEquals("It is working.", result)
    }

    @Test fun p09Item5BackFromATaskThenHomeInTwoSteps() {
        val nav = Navigator()
        nav.open(Screen.Settings)
        nav.open(Screen.Project("p"))
        nav.open(Screen.Task("t"))
        assertTrue(nav.back())
        assertEquals(Screen.Project("p"), nav.current)
        assertTrue(nav.back())
        assertEquals(Screen.Chat, nav.current)
    }

    @Test fun p09Item10StopModes() {
        val confirm = StopGuard(StopMode.Confirm)
        assertEquals(StopAction.Arm, confirm.tap(0))
        assertEquals(StopAction.Fire, confirm.tap(1000))
        assertEquals(StopAction.Arm, confirm.tap(10_000))
        assertEquals(StopAction.Arm, confirm.tap(10_000 + STOP_CONFIRM_MS + 1))
        assertEquals(StopAction.Hint, StopGuard(StopMode.Hold).tap(0))
        assertEquals(StopAction.Arm, StopGuard(StopMode.Hold).tap(0, accessible = true))
        assertEquals(StopAction.Fire, StopGuard(StopMode.Off).tap(0))
        assertEquals(StopMode.Confirm, StopMode.of(null))
    }

    @Test fun p09Item22QuestionIsOneQuotedLine() {
        val q = Question("q1", "**Which** colour\nshall I use?", "", "")
        assertEquals("Which colour shall I use?", questionQuote(q, null))
        assertEquals("Last line?", questionQuote(Question("q2", "", "", ""), "Intro\n\nLast line?"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P09ATaskRowsTest {
    @get:Rule val compose = createComposeRule()

    private fun rowNodes() = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions))
        .filter(androidx.compose.ui.test.hasText("Messages arrive in order"))
    private fun rows() = rowNodes().fetchSemanticsNodes().size

    @Test fun p09Item4ArchivedAndDeletedTasksLeaveTheProjectRows() {
        val station = FakeStation()
        val nav = Navigator()
        compose.setContent { MimirTheme { AppRoot(station, nav) } }
        val before = run { compose.waitForIdle(); rows() }
        nav.open(Screen.Project("Budget-App")); compose.waitForIdle()
        val onPage = rows()
        assertTrue("the project page lists the task", onPage > before)
        val row = rowNodes().onFirst()
        val archive = row.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label == "Archive" }
        compose.runOnUiThread { archive.action() }
        compose.waitForIdle()
        assertEquals(before, rows())
        compose.onNodeWithContentDescription("Undo: Archived Messages arrive in order").performClick()
        compose.waitForIdle()
        assertEquals(onPage, rows())
    }
}
