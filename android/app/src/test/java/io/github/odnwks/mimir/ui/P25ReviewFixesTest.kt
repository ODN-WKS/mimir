package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Effort
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.PieceSink
import io.github.odnwks.mimir.standalone.Runner
import io.github.odnwks.mimir.standalone.Saved
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P25-F1: the review findings on the shared chat layout. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class P25ReviewFixesTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val gate = Semaphore(0)

    /** A model that answers one message each time the check lets it. */
    private class Held(private val gate: Semaphore) : Runner {
        override fun open(path: String) = File(path).exists()
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            gate.tryAcquire(10, TimeUnit.SECONDS)
            sink.onPiece("The new answer.".toByteArray())
            return """{"gen_tokens":3,"gen_ms":100}"""
        }
        override fun stop() {}
        override fun close() {}
    }

    @After fun letGo() = gate.release(10)

    private fun long(name: String) = (1..12).flatMap {
        listOf(
            Turn(true, "$name question $it", id = "$name-q$it", at = 1_700_000_000_000L + it),
            Turn(false, "$name answer $it, with a few words.", id = "$name-q$it.a", at = 1_700_000_000_100L + it, ran = "phone", modelWords = "Qwen 3.5 · This phone"),
        )
    }

    private fun twoChats(): Engine {
        val dir = tmp.newFolder()
        testEngine(dir, ready = true)
        val store = Store(File(dir, "standalone.bin"), PlainCodec)
        store.save(Saved(Catalog.default.id, listOf(LocalChat("c1", Catalog.default.id, long("Basil")), LocalChat("c2", Catalog.default.id, long("Mint")))))
        return Engine(dir, store, WordsRunner(), supported = true, now = { 1_800_000_000_000L })
    }

    private fun phoneChat(engine: Engine, nav: Navigator = Navigator()) {
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { PhoneChat(engine, nav, paired = false, pcOnline = false) }
            }
        }
        compose.waitForIdle()
    }

    private fun app(engine: Engine, nav: Navigator) {
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { AppRoot(FakeStation(LinkStation.empty("t").copy(paired = false)), nav) }
            }
        }
        compose.waitForIdle()
    }

    private fun shown(words: String) = compose.onAllNodes(hasText(words, substring = true)).fetchSemanticsNodes().size
    private fun scrollUp() = repeat(2) { compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }; compose.waitForIdle() }

    private fun answered(engine: Engine) {
        compose.waitUntil(10_000) { !engine.state.value.writing && engine.state.value.chat.turns.lastOrNull()?.user == false }
        compose.waitForIdle()
    }

    private fun opensAtNewestAfterScrollingUpInAnother(show: (Engine) -> Unit) {
        val engine = twoChats()
        show(engine)
        assertEquals("c1", engine.state.value.chat.id)
        scrollUp()
        assertEquals(0, shown("Basil answer 12,"))
        compose.runOnIdle { engine.open("c2") }
        compose.waitForIdle()
        compose.onNodeWithText("Mint answer 12, with a few words.").assertIsDisplayed()
        assertEquals(0, shown("New lines ↓"))
    }

    @Test fun anotherChatOpensAtItsNewestWithoutNewLines() = opensAtNewestAfterScrollingUpInAnother { phoneChat(it) }

    @Test fun anotherChatOpensAtItsNewestOnThePhoneModelScreenToo() = opensAtNewestAfterScrollingUpInAnother { app(it, Navigator(Screen.Standalone)) }

    @Test fun comingBackFromAnotherScreenShowsNoNewLinesWhenNothingCame() {
        val engine = twoChats()
        val nav = Navigator()
        app(engine, nav)
        scrollUp()
        compose.runOnIdle { nav.open(Screen.Settings) }; compose.waitForIdle()
        assertEquals(0, shown("Basil question"))
        compose.runOnIdle { nav.closeScreen() }; compose.waitForIdle()
        assertEquals("still where it was read", 0, shown("Basil answer 12,"))
        assertEquals(0, shown("New lines ↓"))
    }

    @Test fun tryAgainCountsItsOwnTime() {
        val engine = testEngine(tmp.newFolder(), ready = true, runner = Held(gate))
        phoneChat(engine)
        engine.send("Tell me a short story.")
        compose.waitUntil(5_000) { engine.state.value.reading }
        compose.waitForIdle()
        // As if the first attempt began half an hour ago.
        val secs = Class.forName("io.github.odnwks.mimir.ui.WritingSince").getDeclaredField("secs").apply { isAccessible = true }
        secs.setLong(null, secs.getLong(null) - 1_800)
        gate.release(); answered(engine)
        compose.runOnIdle { engine.tryAgain() }
        compose.waitUntil(5_000) { engine.state.value.reading }
        compose.waitForIdle()
        compose.onNodeWithText("Reading your message…").assertIsDisplayed()
        assertEquals(0, shown("30 min"))
    }

    @Test fun theAnswerKeepsTheEffortItWasWrittenWith() {
        val engine = testEngine(tmp.newFolder(), ready = true, runner = Held(gate))
        phoneChat(engine)
        engine.send("Tell me a short story.")
        compose.waitUntil(5_000) { engine.state.value.reading }
        compose.runOnIdle { engine.setEffort(Effort.Thorough) }
        gate.release(); answered(engine)
        assertEquals(Effort.Balanced.wire, engine.state.value.chat.turns.last().effort)
    }

    // P29: holding a chat gives its short menu and leaves the open chat alone; Find is under the
    // chat's own menu once it is opened.
    @Test fun holdingAChatInTheSideMenuOffersFindInThisChat() {
        val engine = twoChats()
        app(engine, Navigator())
        compose.menuRowHold("Mint question 1", substring = true)
        compose.onNodeWithText(ROW_DELETE).assertExists()
        assertEquals("c1", engine.state.value.chat.id)
        compose.onNodeWithText(ROW_DELETE).performScrollTo()
        compose.runOnIdle { }
        compose.onNodeWithContentDescription("Close").performClick(); compose.waitForIdle()
        compose.onAllNodesWithText("Mint question 1", substring = true).onLast().performClick(); compose.waitForIdle()
        assertEquals("c2", engine.state.value.chat.id)
        compose.openChatOptions()
        compose.onNodeWithText(FIND_IN_CHAT).performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription(FIND_IN_CHAT).performTextInput("answer 12"); compose.waitForIdle()
        compose.onNodeWithText("1 message has \"answer 12\"").assertIsDisplayed()
    }
}
