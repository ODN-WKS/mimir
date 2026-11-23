package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.ui.PAIR_CODE_KEYBOARD
import io.github.odnwks.mimir.ui.cleanPairCode
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A task's row on its project page says what the task screen says. */
class U1dRowStateTest {
    private fun task(status: String, busy: Boolean = false, question: Boolean = false, text: String = "All done.") = Task(
        id = "t", title = "T", project = "Garden planner", model = "Sonnet", age = "",
        steps = emptyList(),
        messages = listOf(TaskMessage("m1", fromClaude = true, text = text)),
        question = if (question) Question("q1", "Which one?", "", "") else null,
        permission = null, change = null, status = status, busy = busy,
    )

    private fun row(t: Task) = Mapping.withTaskWords(ProjectTask(t.id, t.title, "Done", Chip.Done), t.stateWords)

    @Test fun needsYouRowIsNotDone() {
        val t = task("Done", question = true)
        assertEquals("Needs you", t.stateWords)
        assertEquals(Chip.NeedsYou, row(t).chip)
        assertEquals("Needs you", row(t).meta)
    }

    @Test fun everyStateRowMatchesTheTaskScreen() {
        listOf(
            task("Needs you") to Chip.NeedsYou,
            task("Done", text = "Which one should I use?") to Chip.WaitingForYou,
            task("Working", busy = true) to Chip.Running,
            task("Done") to Chip.Done,
            task("Stopped") to Chip.Stopped,
        ).forEach { (t, chip) ->
            assertEquals(chip, row(t).chip)
            assertEquals(t.stateWords, row(t).meta)
            assertEquals(t.stateWords, row(t).chip.label)
        }
    }

    @Test fun aLiveTaskRowIsLiveNotDone() {
        assertEquals(Chip.Live, Chip.ofWords("Live"))
        assertEquals("Live", Chip.Live.label)
    }

    @Test fun pairingCodeKeyboardDoesNotCorrect() {
        assertFalse(PAIR_CODE_KEYBOARD.autoCorrectEnabled ?: true)
        assertEquals(KeyboardCapitalization.None, PAIR_CODE_KEYBOARD.capitalization)
    }

    @Test fun pastedCodeWithSpacesWorks() {
        assertEquals("abc-kitchen-pc", cleanPairCode("  abc-kitchen-pc \n"))
    }
}
