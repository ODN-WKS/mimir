package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.online.Source
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.Prompt
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Answers are honest about where they come from, and an answer replaced is never lost. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class TruthAnswersTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var engine: Engine
    private lateinit var dir: File
    private val nav = Navigator()

    @After fun tearDown() { Online.set(Online.Settings()) }

    private fun two() = listOf(
        Turn(true, "First question", id = "m1", at = 1000), Turn(false, "First answer", id = "m1.a", at = 1001, ran = "phone"),
        Turn(true, "When does the market open", id = "m2", at = 2000), Turn(false, "The market opens at eight", id = "m2.a", at = 2001, ran = "phone"),
    )

    private fun make(turns: List<Turn>) {
        dir = tmp.newFolder()
        engine = testEngine(dir, ready = true, turns = turns)
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { AppRoot(FakeStation(LinkStation.empty("test").copy(paired = false)), nav) }
            }
        }
        compose.waitForIdle()
    }

    private fun done() {
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    private fun old(id: String) = engine.state.value.chat.old[id].orEmpty().map { it.text }

    @Test fun tryAgainKeepsTheAnswerItReplacesAlsoAfterARestart() {
        make(two())
        assertTrue(engine.tryAgain())
        done()
        assertEquals(listOf("The market opens at eight"), old("m2.a"))
        assertEquals(4, engine.state.value.chat.turns.size)
        val saved = Store(File(dir, "standalone.bin"), PlainCodec).load()!!.chats.first { it.id == "c1" }
        assertEquals(listOf("The market opens at eight"), saved.old["m2.a"]!!.map { it.text })
        assertEquals("When does the market open", saved.old["m2.a"]!!.single().asked)
    }

    @Test fun lookItUpKeepsTheAnswerAndAsksAsANewVersion() {
        Online.set(Online.Settings(searchAccepted = true))
        io.github.odnwks.mimir.online.FakeWeb().install()
        make(two())
        engine.lookItUp()
        done()
        assertEquals(listOf("The market opens at eight"), old("m2.a"))
        // A PC that keeps each message's reply never hands the old one back.
        assertEquals(2, engine.state.value.chat.turns[2].version)
    }

    @Test fun aLookItUpThatFailedStillSearchesWhenSentAgain() {
        Online.set(Online.Settings(searchAccepted = true))
        val web = io.github.odnwks.mimir.online.FakeWeb().also { it.install() }
        io.github.odnwks.mimir.online.Search.forget()
        var broken = true
        val words = WordsRunner()
        dir = tmp.newFolder()
        testEngine(dir, ready = true, turns = two())
        engine = Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), object : io.github.odnwks.mimir.standalone.Runner by words {
            // The first try gets no answer at all, as when the link to the PC drops.
            override fun open(path: String) = !broken && words.open(path)
        }, supported = true, now = { 1_800_000_000_000L })
        engine.open("c1")
        engine.lookItUp()
        done()
        assertTrue(engine.state.value.chat.turns.last().user)
        val before = web.asked.size
        broken = false
        io.github.odnwks.mimir.online.Search.forget()
        io.github.odnwks.mimir.online.Net.forget()
        engine.retry()
        done()
        assertTrue("sent again, it searches again", web.asked.drop(before).any { "duckduckgo" in it })
        assertEquals(listOf("The market opens at eight"), old("m2.a"))
    }

    /** An engine whose model gives no answer at all while [broken], as when the link to the PC drops. */
    private var broken = false
    private fun makeBreakable() {
        val words = WordsRunner()
        dir = tmp.newFolder()
        testEngine(dir, ready = true, turns = two())
        engine = Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), object : io.github.odnwks.mimir.standalone.Runner by words {
            override fun open(path: String) = !broken && words.open(path)
            // A model already loaded gives no answer either.
            override fun reply(prompt: String, maxTokens: Int, sink: io.github.odnwks.mimir.standalone.PieceSink, pictures: List<ByteArray>): String =
                if (broken) error("gone") else words.reply(prompt, maxTokens, sink, pictures)
            override fun reply(prompt: String, maxTokens: Int, sink: io.github.odnwks.mimir.standalone.PieceSink): String =
                if (broken) error("gone") else words.reply(prompt, maxTokens, sink)
        }, supported = true, now = { 1_800_000_000_000L })
        engine.open("c1")
    }

    private fun web(): io.github.odnwks.mimir.online.FakeWeb {
        Online.set(Online.Settings(searchAccepted = true))
        val web = io.github.odnwks.mimir.online.FakeWeb().also { it.install() }
        io.github.odnwks.mimir.online.Search.forget()
        io.github.odnwks.mimir.online.Net.forget()
        return web
    }

    @Test fun anEditAfterAFailedLookItUpNeverSearchesTheOldWords() {
        val web = web()
        makeBreakable()
        broken = true
        engine.lookItUp()
        done()
        val before = web.asked.size
        broken = false
        io.github.odnwks.mimir.online.Search.forget()
        io.github.odnwks.mimir.online.Net.forget()
        engine.editMessage("m2", "Say hello")!!
        done()
        assertEquals(emptyList<String>(), web.asked.drop(before).filter { "market" in it })
    }

    @Test fun undoingAnEditForgetsALookItUpAskedForTheNewWords() {
        val web = web()
        makeBreakable()
        val cut = engine.editMessage("m2", "Tell me about zebras please")!!
        done()
        broken = true
        engine.lookItUp()
        done()
        engine.undoEdit(cut)
        // The old message and its answer are back: asked again, it must not search the words that were undone.
        broken = false
        io.github.odnwks.mimir.online.Search.forget()
        io.github.odnwks.mimir.online.Net.forget()
        val before = web.asked.size
        assertTrue(engine.tryAgain())
        done()
        assertEquals(emptyList<String>(), web.asked.drop(before).filter { "zebras" in it })
    }

    private fun waitingReAsk(words: String) {
        dir = tmp.newFolder()
        testEngine(dir, ready = true)
        val q = two()[2].copy(text = words, version = 2, wentOnce = true)
        val c = io.github.odnwks.mimir.standalone.LocalChat(
            "c1", Catalog.default.id, two().take(2), queued = listOf(q),
            old = mapOf("m2.a" to listOf(two()[3].copy(text = "An older one", asked = "When does the market open"), two()[3].copy(asked = "When does the market open"))),
        )
        Store(File(dir, "standalone.bin"), PlainCodec).save(io.github.odnwks.mimir.standalone.Saved(Catalog.default.id, listOf(c)))
        engine = Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), WordsRunner(), supported = true, now = { 1_800_000_000_000L })
        engine.open("c1")
    }

    @Test fun cancellingAWaitingReAskPutsTheQuestionAndItsAnswerBack() {
        for (words in listOf("When does the market open", "When does the market open, reworded")) {
            waitingReAsk(words)
            engine.cancelQueued("m2")
            val chat = engine.state.value.chat
            assertTrue(chat.queued.isEmpty())
            assertEquals(listOf("First question", "First answer", "When does the market open", "The market opens at eight"), chat.turns.map { it.text })
            assertEquals(listOf("An older one"), old("m2.a"))
            val saved = Store(File(dir, "standalone.bin"), PlainCodec).load()!!.chats.first { it.id == "c1" }
            assertEquals(4, saved.turns.size)
            assertTrue(saved.queued.isEmpty())
        }
    }

    @Test fun anEditKeepsTheOldAnswerAndUndoLeavesNoCopy() {
        make(two())
        val cut = engine.editMessage("m1", "First question, better")!!
        done()
        assertEquals(listOf("First answer"), old("m1.a"))
        assertEquals("First question", engine.state.value.chat.old["m1.a"]!!.single().asked)
        engine.undoEdit(cut)
        assertTrue(engine.state.value.chat.old.isEmpty())
    }

    @Test fun anAnswerFromMemorySaysSoAndOffersLookItUpBesideListen() {
        make(two())
        show()
        nav.selectTab(io.github.odnwks.mimir.nav.Tab.Chat)
        compose.waitForIdle()
        compose.onAllNodesWithText(FROM_MEMORY).assertCountEquals(2)
        // Only the newest answer offers the lookup.
        compose.onAllNodesWithContentDescription("Look it up: search the web for this answer").assertCountEquals(1)
    }

    @Test fun thePcsOwnReportIsNotCalledMemory() {
        make(listOf(Turn(true, "What's running?", id = "s", at = 1), Turn(false, "Nothing is running.", id = "s.a", at = 2, ran = "pc", modelWords = "Your PC")))
        show()
        compose.onAllNodesWithText(FROM_MEMORY).assertCountEquals(0)
    }

    @Test fun aLookedUpAnswerIsNotMarkedAsMemory() {
        make(two().dropLast(1) + Turn(false, "The market opens at eight", id = "m2.a", at = 2001, ran = "phone", sources = listOf(Source("example.org", "https://example.org", "market"))))
        show()
        compose.onAllNodesWithText(FROM_MEMORY).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Look it up: search the web for this answer").assertCountEquals(0)
    }

    @Test fun earlierAnswersAreAStepAwayUnderTheNewOne() {
        make(two())
        engine.tryAgain()
        done()
        show()
        compose.onNodeWithText("2 of 2").assertIsDisplayed()
        compose.onNodeWithContentDescription(EARLIER_STEP).performClick()
        compose.onNodeWithText("1 of 2").assertIsDisplayed()
        compose.onNodeWithText("The market opens at eight").assertIsDisplayed()
        compose.onNodeWithContentDescription(NEWER_STEP).performClick()
        compose.onNodeWithText("2 of 2").assertIsDisplayed()
    }

    @Test fun withNoNewAnswerYetTheOldOneStaysUnderTheQuestion() {
        // As after a "Look it up" that failed, or the app closed while it was being written.
        dir = tmp.newFolder()
        val c = io.github.odnwks.mimir.standalone.LocalChat("c1", Catalog.default.id, two().dropLast(1), old = mapOf("m2.a" to listOf(two().last())))
        engine = testEngine(dir, ready = true)
        Store(File(dir, "standalone.bin"), PlainCodec).save(io.github.odnwks.mimir.standalone.Saved(Catalog.default.id, listOf(c)))
        engine = Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), WordsRunner(), supported = true, now = { 1_800_000_000_000L })
        show()
        compose.onNodeWithText("The market opens at eight").assertIsDisplayed()
        compose.onNodeWithText(EARLIER_ANSWER).assertIsDisplayed()
    }

    @Test fun findMarksTheWordsInTheMessages() {
        make(two())
        show()
        compose.runOnIdle { nav.taskAsk = FIND_CHAT }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(FIND_IN_CHAT).performTextInput("market")
        compose.waitForIdle()
        val marked = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("marks") { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.spanStyles.any { it.item == FOUND_MARK && t.text.substring(it.start, it.end).equals("market", true) } } == true
        }, useUnmergedTree = true)
        marked.assertCountEquals(2)
    }

    @Test fun theInstructionsForbidClaimingALookupThatDidNotRun() {
        assertTrue(Prompt.NO_CLAIM in Prompt.rulesForPc(null, ""))
        assertTrue(Prompt.NO_CLAIM in Prompt.system())
        assertTrue(Prompt.NO_CLAIM in Prompt.system(rules = null))
    }
}
