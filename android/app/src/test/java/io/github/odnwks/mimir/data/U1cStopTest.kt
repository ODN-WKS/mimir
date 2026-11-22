package io.github.odnwks.mimir.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stop shows only while something of the task runs on the PC. */
class U1cStopTest {
    private fun task(status: String = "Done", busy: Boolean = false, running: Boolean = false, question: Boolean = false, permission: Boolean = false) = Task(
        id = "t", title = "T", project = "Garden planner", model = "Sonnet", age = "",
        steps = emptyList(),
        messages = listOf(TaskMessage("m1", fromClaude = true, text = "Which one?")),
        question = if (question) Question("q1", "Which one?", "", "") else null,
        permission = if (permission) Permission("p1", "Run a command", "", 0) else null,
        change = null, status = status, busy = busy, running = running,
    )

    @Test fun aQuestionWithNothingRunningHasNoStop() {
        val t = task(status = "Needs you", question = true, running = false)
        assertEquals("Needs you", t.stateWords)
        assertFalse(t.showsStop)
    }

    @Test fun aQuestionAliveSessionIsWaitingOnHasStop() {
        assertTrue(task(status = "Needs you", question = true, running = true).showsStop)
    }

    @Test fun aPermissionPromptHasStop() {
        assertTrue(task(status = "Needs you", permission = true, running = true).showsStop)
    }

    @Test fun workingHasStopEvenWhenOnlyBackgroundWorkRuns() {
        assertTrue(task(status = "Working", busy = true, running = true).showsStop)
        assertTrue(task(status = "Done", busy = true, running = true).showsStop)
    }

    @Test fun doneHasNoStop() {
        assertFalse(task(status = "Done", running = false).showsStop)
    }
}
