package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.PieceSink
import io.github.odnwks.mimir.standalone.Runner
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P25: the main chat and the phone-model screen show the task chat's parts in the same places. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class P25OneChatLayoutTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private val gate = CountDownLatch(1)

    /** A model that answers only once the check lets it. */
    private class Held(private val gate: CountDownLatch) : Runner {
        override fun open(path: String) = File(path).exists()
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            gate.await(10, TimeUnit.SECONDS)
            sink.onPiece("The new answer.".toByteArray())
            return """{"gen_tokens":3,"gen_ms":100}"""
        }
        override fun stop() {}
        override fun close() {}
    }

    @After fun letGo() = gate.countDown()

    private fun phoneChat(engine: Engine, paired: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { PhoneChat(engine, nav, paired = paired, pcOnline = paired) }
            }
        }
        compose.waitForIdle()
    }

    private fun answered(engine: Engine) {
        compose.waitUntil(10_000) { !engine.state.value.writing && engine.state.value.chat.turns.lastOrNull()?.user == false }
        compose.waitForIdle()
    }

    private fun timeLines() = compose.onAllNodes(hasTestTag(META_TAG), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun timeLinesWith(words: String) =
        compose.onAllNodes(hasTestTag(META_TAG) and hasAnyDescendant(hasText(words, substring = true)), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun shown(words: String) = compose.onAllNodes(hasText(words, substring = true)).fetchSemanticsNodes().size
    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private val long = (1..12).flatMap {
        listOf(Turn(true, "Question number $it", id = "q$it", at = 1_700_000_000_000L + it), Turn(false, "Answer number $it, with a few words.", id = "q$it.a", at = 1_700_000_000_100L + it, ran = "phone", modelWords = "Qwen 3.5 · This phone"))
    }

    @Test fun thePhonesAnswerShowsItsModelAndTimeAndSoDoesYourMessage() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        phoneChat(engine)
        engine.send("Tell me a short story."); answered(engine)
        // One under the message, one beside the answer's Listen pill, each naming the model and the effort.
        assertEquals(2, timeLines())
        val model = engine.state.value.chat.turns.last().modelWords!!.substringBefore(" · ")
        assertEquals(2, timeLinesWith("$model · Balanced · "))
    }

    @Test fun thePcsAnswerShowsItsModelAndTime() {
        val engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = FakePc() }
        phoneChat(engine, paired = true)
        engine.send("What is the capital of Norway?"); answered(engine)
        assertEquals("pc", engine.state.value.chat.turns.last().ran)
        assertEquals(2, timeLines())
        assertEquals(2, timeLinesWith("Qwen 3.6 · Balanced · "))
    }

    @Test fun theEffortStaysWithASavedChat() {
        val dir = tmp.newFolder()
        val engine = testEngine(dir, ready = true)
        phoneChat(engine)
        engine.send("Tell me a short story."); answered(engine)
        val saved = File(dir, "standalone.bin")
        compose.waitUntil(5_000) { saved.exists() && saved.readText().contains("\"effort\":\"medium\"") }
    }

    @Test fun whileAnAnswerComesTheLiveLineAndTheDotsShowThenGo() {
        val engine = testEngine(tmp.newFolder(), ready = true, runner = Held(gate))
        phoneChat(engine)
        engine.send("Tell me a short story.")
        compose.waitUntil(5_000) { engine.state.value.reading }
        compose.waitForIdle()
        assertEquals(1, tagged(THINKING_TAG))
        assertEquals(1, tagged("chat-live"))
        compose.onNodeWithText("Reading your message…").assertIsDisplayed()
        gate.countDown(); answered(engine)
        assertEquals(0, tagged(THINKING_TAG))
        assertEquals(0, tagged("chat-live"))
    }

    @Test fun scrolledUpANewAnswerWaitsBehindNewLines() {
        val engine = testEngine(tmp.newFolder(), ready = true, turns = long, runner = Held(gate))
        phoneChat(engine)
        engine.send("One more question")
        compose.waitUntil(5_000) { engine.state.value.reading }
        compose.waitForIdle()
        repeat(2) { compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }; compose.waitForIdle() }
        assertEquals(0, shown("New lines ↓"))
        gate.countDown(); answered(engine)
        compose.onNodeWithText("New lines ↓").assertIsDisplayed()
        assertEquals("the view stayed where it was read", 0, shown("The new answer."))
        compose.onNodeWithText("New lines ↓").performClick(); compose.waitForIdle()
        compose.onNodeWithText("The new answer.").assertIsDisplayed()
        assertEquals(0, shown("New lines ↓"))
    }

    private val two = listOf(
        Turn(true, "How do I grow basil?", id = "a", at = 1_700_000_000_000L), Turn(false, "Give it sun and water.", id = "a.a", at = 1_700_000_000_001L),
        Turn(true, "And mint?", id = "b", at = 1_700_000_000_002L), Turn(false, "Keep it in its own pot.", id = "b.a", at = 1_700_000_000_003L),
    )

    private fun findBasil(start: Screen?) {
        val engine = testEngine(tmp.newFolder(), ready = true, turns = two)
        val nav = if (start == null) Navigator() else Navigator(start)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { AppRoot(FakeStation(LinkStation.empty("t").copy(paired = false)), nav) }
            }
        }
        compose.waitForIdle()
        // The phone-model screen opens the same sheet from a tap on its title.
        if (start == null) compose.openChatOptions() else { compose.runOnIdle { nav.openSheet(chatOptions(engine.state.value)) }; compose.waitForIdle() }
        compose.onNodeWithText("Find in this chat").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Find in this chat").performTextInput("basil"); compose.waitForIdle()
        compose.onNodeWithText("1 message has \"basil\"").assertIsDisplayed()
        org.junit.Assert.assertTrue(shown("How do I grow basil?") >= 1)
        assertEquals(0, shown("Give it sun"))
        assertEquals(0, shown("Keep it in its own pot."))
        compose.onNodeWithContentDescription("Close find").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Keep it in its own pot.").assertIsDisplayed()
    }

    @Test fun findInChatFromTheMenuFindsAWordInAnEarlierMessage() = findBasil(null)

    @Test fun findInChatWorksOnThePhoneModelScreenToo() = findBasil(Screen.Standalone)

    @Test fun aChatSavedBeforeThisOpensWithAllItsMessagesAndTheirTimeLines() {
        val dir = tmp.newFolder()
        // As the app before this change saved it: no "effort" anywhere.
        File(dir, "standalone.bin").writeText(
            """{"model":"${io.github.odnwks.mimir.standalone.Catalog.default.id}","chats":[{"id":"c1","model":"${io.github.odnwks.mimir.standalone.Catalog.default.id}","at":1700000000000,"turns":[
            {"user":true,"text":"Old question","id":"a","at":1700000000000},
            {"user":false,"text":"Old answer","id":"a.a","at":1700000000001,"ran":"phone","model_words":"Qwen 3.5 · This phone"}]}]}""",
        )
        val engine = Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), WordsRunner(), supported = true, now = { 1_800_000_000_000L })
        assertEquals(listOf("Old question", "Old answer"), engine.state.value.chat.turns.map { it.text })
        phoneChat(engine)
        compose.onNodeWithText("Old question").assertIsDisplayed()
        compose.onNodeWithText("Old answer").assertIsDisplayed()
        assertEquals(2, timeLines())
        assertEquals(2, timeLinesWith("Qwen 3.5 · "))
        assertEquals(0, timeLinesWith("Balanced"))
    }
}
