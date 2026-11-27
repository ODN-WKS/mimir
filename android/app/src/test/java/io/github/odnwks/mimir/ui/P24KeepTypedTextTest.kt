package io.github.odnwks.mimir.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P24 fix round 1: Back goes home in one press, and words typed in a task's reply box or a
 * project's "start a task" box are still there when it is opened again, until they are sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P24KeepTypedTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private val station = FakeStation(ExampleData.awake())
    private val task = ExampleData.TASK_ALLOW
    private val project by lazy { station.state.value.projects.first().name }

    private fun start() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } } }
        compose.waitForIdle()
    }

    private fun open(screen: Screen) { compose.runOnUiThread { nav.open(screen) }; compose.waitForIdle() }

    private fun backHome() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf(Screen.Chat), nav.stack.map { it.screen })
    }

    /** P43: Back from a task is where it was opened from (here the project page); then home. */
    private fun backOut() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun type(box: String, words: String) {
        compose.onNodeWithContentDescription(box).performTextInput(words)
        compose.waitForIdle()
    }

    private fun send() {
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
    }

    private fun assertBox(box: String, words: String) =
        assertEquals(words, compose.onNodeWithContentDescription(box).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

    @Test fun aTaskReplySurvivesItsChangeAndBack() {
        start()
        open(Screen.Task(task))
        type("Reply to this task", "use the second option")
        compose.onNodeWithText("See the whole change").performClick()
        compose.waitForIdle()
        assertEquals(Screen.Change(task), nav.current)
        backHome()
        open(Screen.Task(task))
        assertBox("Reply to this task", "use the second option")
    }

    @Test fun aProjectBoxSurvivesOpeningOneOfItsTasksAndBack() {
        start()
        open(Screen.Project(project))
        type("New task in $project", "water the beds")
        open(Screen.Task(task))
        // The task's own box is its own: the project's words don't show there.
        assertBox("Reply to this task", "")
        backOut()
        assertEquals(Screen.Project(project), nav.current)
        assertBox("New task in $project", "water the beds")
    }

    @Test fun sendingAReplyClearsItsKeptWords() {
        start()
        open(Screen.Task(task))
        type("Reply to this task", "mint")
        backHome()
        open(Screen.Task(task))
        assertBox("Reply to this task", "mint")
        send()
        assertEquals("mint", station.replied.last())
        backHome()
        open(Screen.Task(task))
        assertBox("Reply to this task", "")
    }

    @Test fun startingATaskClearsTheProjectsKeptWords() {
        start()
        open(Screen.Project(project))
        type("New task in $project", "water the beds")
        backHome()
        open(Screen.Project(project))
        assertBox("New task in $project", "water the beds")
        send()
        backOut()
        assertEquals(Screen.Project(project), nav.current)
        assertBox("New task in $project", "")
    }
}
