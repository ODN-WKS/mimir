package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.standalone.Skill
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P46 fix round 2, "found while hunting" B: a PC Whisper's message carries its skill inside the words, shown as
 * "Skill: <name>". Editing it starts from the person's own words (never the shown "Skill:" line), and the edited
 * message goes with the same skill.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P46F2WhisperEditTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editingAMessageWithASkillKeepsTheSkill() {
        val sent = Skill.withWords("check this", Skill.Trace)
        val task = Task(
            id = "t1", title = "Whisper", project = "", model = "Qwen", age = "", steps = emptyList(),
            messages = listOf(TaskMessage("t1-0", false, sent), TaskMessage("t1-1", true, "Here is what the sources say.")),
            question = null, permission = null, change = null, status = "Done", busy = false, running = false,
        )
        val station = FakeStation(ExampleData.awake().copy(tasks = listOf(task)))
        compose.setContent {
            CompositionLocalProvider(LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) {
                    val state by station.state.collectAsStateWithLifecycle()
                    TaskScreen("t1", state, station, Navigator())
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Skill: Trace a claim", substring = true).performSemanticsAction(SemanticsActions.OnLongClick); compose.waitForIdle()
        compose.onNodeWithText(EDIT).performClick(); compose.waitForIdle()
        val box = compose.onNodeWithTag("edit-box")
        assertEquals("the edit box holds the person's own words", "check this", box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        box.performTextReplacement("check this claim")
        compose.onNodeWithContentDescription("Send the edited message").performClick(); compose.waitForIdle()
        assertEquals("the edited message keeps its skill", Skill.withWords("check this claim", Skill.Trace), station.edited.last().third)
    }
}
