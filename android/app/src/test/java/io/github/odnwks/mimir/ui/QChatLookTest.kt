package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.online.Source
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chat the owner sees every day: no repeats under an answer, the mic stays, search results in sight, honest step counts. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class QChatLookTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()

    private val turns = listOf(
        Turn(true, "Who won the football match?", id = "a", at = 1_700_000_000_000L),
        Turn(
            false, "The home side won [1].", id = "a.a", at = 1_700_000_000_001L, ran = "phone", modelWords = "Qwen 3.5 4B SOMPOA heresy · This phone", effort = "medium",
            sources = listOf(Source("espn.com", "https://espn.com/", "football", via = "your PC: Brave")),
        ),
    )

    private fun chat(engine: Engine) {
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { PhoneChat(engine, nav) }
            }
        }
        compose.waitForIdle()
    }

    private fun count(m: androidx.compose.ui.test.SemanticsMatcher) = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun theModelIsNamedOnceAndShort() {
        chat(testEngine(tmp.newFolder(), ready = true, turns = turns))
        assertEquals(1, count(hasText("Qwen", substring = true) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(META_TAG))))
        assertEquals(0, count(hasTestTag("answer-author")))
        assertEquals(0, count(hasText("SOMPOA", substring = true)))
        assertEquals(1, count(hasText("This phone · Qwen 3.5 4B · Balanced", substring = true)))
    }

    @Test fun citedSourcesAreNumberedAndTheSearchRouteIsInTheSameBox() {
        chat(testEngine(tmp.newFolder(), ready = true, turns = turns))
        compose.onNodeWithText("[1]").assertIsDisplayed()
        compose.onNodeWithText("espn.com").assertIsDisplayed()
        assertEquals(1, count(hasTestTag(SOURCES_TAG) and hasAnyDescendant(hasText("via your PC: Brave"))))
    }

    @Test fun theMicStaysInTheBoxOnceAChatStarts() {
        chat(testEngine(tmp.newFolder(), ready = true, turns = turns))
        compose.onNodeWithContentDescription("Talk instead of typing").assertIsDisplayed()
    }

    @Test fun thinkFirstTakesNoRowOfItsOwn() {
        val engine = testEngine(tmp.newFolder(), ready = true, turns = turns)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { StandaloneScreen(engine, nav) }
            }
        }
        compose.waitForIdle()
        assertEquals(0, count(hasText("Think first")))
        val think = compose.onNodeWithContentDescription("Think first").fetchSemanticsNode().boundsInRoot
        val box = compose.onNodeWithContentDescription("Message $AI_NAME").fetchSemanticsNode().boundsInRoot
        assertTrue("beside the message box: $think / $box", think.right <= box.left && think.top < box.bottom && think.bottom > box.top)
        compose.onNodeWithContentDescription("Think first").performClick(); compose.waitForIdle()
        assertTrue(engine.state.value.think)
    }

    @Test fun searchingTheMenuPutsResultsRightUnderTheBox() {
        val engine = testEngine(tmp.newFolder(), ready = true, turns = listOf(Turn(true, "When does the farmers market open"), Turn(false, "Saturday at eight.")))
        engine.newChat()
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(FakeStation(ExampleData.awake()), nav) } } }
        compose.waitForIdle()
        compose.openMenu()
        compose.onNodeWithContentDescription(MENU_SEARCH, substring = true).performTextReplacement("farmers"); compose.waitForIdle()
        for (fixed in listOf(MENU_RECENT, "a chat that's never kept", MENU_USAGE)) assertEquals(fixed, 0, compose.onAllNodes(hasText(fixed)).fetchSemanticsNodes().size)
        val found = compose.onNodeWithText("Saturday at eight.", substring = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("results above New chat", found.bottom <= compose.onNodeWithText(MENU_NEW_CHAT).fetchSemanticsNode().boundsInRoot.top)
    }

    private fun task(steps: List<String>, busy: Boolean) = Task(
        id = "t1", title = "Tidy", project = "Garden planner", model = "Opus 5.5", age = "", steps = steps,
        messages = listOf(TaskMessage("t1-0", false, "Please tidy the beds")),
        question = null, permission = null, change = null, status = if (busy) "Working" else "Done", busy = busy, running = busy,
    )

    private fun showTask(t: Task) {
        val station = FakeStation(ExampleData.awake().copy(tasks = listOf(t)))
        compose.setContent {
            CompositionLocalProvider(LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { val s by remember { mutableStateOf(station.state.value) }; TaskScreen("t1", s, station, Navigator()) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun aWorkingTaskSaysNothingFailedAndItsStepsSitUnderTheMessage() {
        showTask(task(listOf("✕ Read a file", "✕ Run a test", "… Look around"), busy = true))
        assertEquals(0, count(hasText("not done", substring = true)))
        val steps = compose.onNodeWithText("3 steps so far").fetchSemanticsNode().boundsInRoot
        val mine = compose.onNodeWithText("Please tidy the beds").fetchSemanticsNode().boundsInRoot
        assertTrue("steps under the message: $steps / $mine", steps.top >= mine.bottom)
    }

    @Test fun aFinishedTaskStillSaysWhatWasNotDone() {
        showTask(task(listOf("✓ Read a file", "✕ Run a test"), busy = false))
        compose.onNodeWithText("1 step done, 1 not done").assertIsDisplayed()
    }
}
