package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.PieceSink
import io.github.odnwks.mimir.standalone.Runner
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.ui.theme.MimirTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A model that writes a few words, then keeps going until it's stopped. */
class UntilStoppedRunner : Runner {
    @Volatile private var stopped = false
    override fun open(path: String) = File(path).exists()
    override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
        stopped = false
        sink.onPiece("Pasta, ".toByteArray())
        val end = System.currentTimeMillis() + 10_000
        while (!stopped && System.currentTimeMillis() < end) Thread.sleep(5)
        return """{"gen_tokens":1,"gen_ms":10}"""
    }
    override fun stop() { stopped = true }
    override fun close() {}
}

/** Break test 0.19, problem 4: after Stop, the topic sheet's Rename and Delete must still work. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w339dp-h740dp-xhdpi")
class TopicAfterStopTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()

    private fun stoppedChat(): Engine {
        val dir = tmp.newFolder()
        Catalog.reset()
        File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(1_800_000_000_000L) }
        val engine = Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), UntilStoppedRunner(), supported = true, now = { 1_800_000_000_000L })
        val unpaired = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        compose.waitForIdle()
        // Typed (the keyboard's up), sent, then stopped part-way.
        compose.onNodeWithContentDescription("Message $AI_NAME").performClick()
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("Give me three easy dinner ideas")
        compose.onNodeWithContentDescription("Send").performClick()
        val until = System.currentTimeMillis() + 5_000
        while (!(engine.state.value.writing && engine.state.value.partial.isNotEmpty()) && System.currentTimeMillis() < until) { Thread.sleep(20); compose.mainClock.advanceTimeBy(16) }
        check(engine.state.value.writing) { "not writing: ${engine.state.value}" }
        compose.onNodeWithContentDescription("Stop the reply").performClick()
        val until2 = System.currentTimeMillis() + 5_000
        while (engine.state.value.writing && System.currentTimeMillis() < until2) { Thread.sleep(20); compose.mainClock.advanceTimeBy(16) }
        compose.waitForIdle()
        return engine
    }

    private fun openTopic() {
        compose.openChatOptions()
        compose.waitForIdle()
    }

    private var e: Engine? = null
    private fun engine() = e!!

    @Test fun renameWorksAfterStopAndAgainAfterThat() {
        e = stoppedChat()
        openTopic()
        // The sheet put the keyboard away (it would otherwise slide the sheet down under the finger).
        compose.onNodeWithContentDescription("Message $AI_NAME").assertIsNotFocused()
        compose.onNodeWithText(CHAT_RENAME).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Chat name").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        // The same choice a second time still counts.
        openTopic()
        compose.onNodeWithText(CHAT_RENAME).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Chat name").assertIsDisplayed()
    }

    @Test fun deleteAsksAfterStop() {
        e = stoppedChat()
        openTopic()
        compose.onNodeWithText(CHAT_DELETE).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
