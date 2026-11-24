package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Question
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
