package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.ChatEntry
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.MessageReceipt
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P15: pictures of the small Listen pill row (the owner's mock-up 6, option A) on the task screen, the chat
 * screen and Mímir's own chat. They are saved only when P15_SHOT names a folder; the pill count is checked always.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P15ListenPillShotsTest {
    @get:Rule val compose = createComposeRule()

    private val out = System.getenv("P15_SHOT")
    private val now = System.currentTimeMillis()
    private val opus = "claude-opus-5-5"
    private val sonnet = "claude-sonnet-5-5"

    /** The mock-up's conversation, then a reply on a switched model (its amber line is small print too). */
    private fun task() = Task(
        id = "t1", title = "Timestamps", project = "Mímir", model = "Opus 5.5", age = "", steps = emptyList(),
        messages = listOf(
            TaskMessage("t1-0", true, "The change is saved on its own branch.", "Play the reply", atMs = now - 600_000, model = opus),
            TaskMessage("t1-1", false, "it looks good, add timestamps", atMs = now - 420_000, receipt = MessageReceipt.Read, model = opus),
            TaskMessage("t1-2", true, "Added. Each reply shows its model and time.", "Play the reply", atMs = now - 360_000, model = opus),
            TaskMessage("t1-3", false, "and the receipts?", atMs = now - 300_000, receipt = MessageReceipt.Delivered, model = opus),
            TaskMessage(
                "t1-4", true, "The three circles are under your own messages.", "Play the reply", atMs = now - 240_000, model = sonnet, effort = "medium",
                switched = "usage limit", switchedWhy = "Claude's usage limit ran out, so the task carried on on another model.",
            ),
        ),
        question = null, permission = null, change = null, status = "Done", busy = false, running = false,
    )

    private fun show(pills: Int, name: String, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalReadAloud provides FakeReadAloud()) { MimirTheme(reduceMotion = true) { content() } }
        }
        compose.waitForIdle()
        assertEquals("one Listen pill per finished reply", pills, compose.onAllNodesWithTag(LISTEN_PILL_TAG).fetchSemanticsNodes().size)
        out?.let { compose.onRoot().captureRoboImage("$it/p15_$name.png") }
    }

    private fun taskScreen(name: String) {
        val station = FakeStation(ExampleData.awake().copy(tasks = listOf(task())))
        show(3, name) {
            val state by station.state.collectAsStateWithLifecycle()
            TaskScreen("t1", state, station, Navigator())
        }
    }

    @Test fun theTaskScreenAt390() = taskScreen("task_390x844_1.0")
    @Config(fontScale = 1.3f) @Test fun theTaskScreenAt390TextSize13() = taskScreen("task_390x844_1.3")
    @Config(qualifiers = "w320dp-h700dp-xhdpi") @Test fun theTaskScreenAt320() = taskScreen("task_320_1.0")
    @Config(qualifiers = "w320dp-h700dp-xhdpi", fontScale = 1.3f) @Test fun theTaskScreenAt320TextSize13() = taskScreen("task_320_1.3")

    @Test fun theChatScreenAt390() {
        val station = FakeStation(
            ExampleData.awake().copy(
                incognito = null,
                chat = listOf(
                    ChatEntry.Mine("m1", "How is the build?", null),
                    ChatEntry.Reply("r1", listOf("The build passed."), "Play"),
                    ChatEntry.Reply("r2", listOf("The tests passed too."), "Play"),
                ),
            ),
        )
        show(2, "chat_390x844_1.0") {
            val state by station.state.collectAsStateWithLifecycle()
            ChatScreen(state, station, Navigator())
        }
    }

    private fun mimirChat(name: String) = show(2, name) {
        Column(Modifier.fillMaxSize().background(Palette.Ground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Reply("The change is saved on its own branch.", dim = false, author = "This phone · Qwen 3.5")
            Reply("Added. Each reply shows its model and time.", dim = false)
            Reply("Half an ans", dim = false, buttons = false)
        }
    }

    @Test fun mimirsChatAt390() = mimirChat("mimir_390x844_1.0")
    @Config(qualifiers = "w320dp-h700dp-xhdpi", fontScale = 1.3f) @Test fun mimirsChatAt320TextSize13() = mimirChat("mimir_320_1.3")
}
