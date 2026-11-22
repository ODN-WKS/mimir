package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.getOrNull
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.link.LChatRecord
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.standalone.ChatSync
import io.github.odnwks.mimir.standalone.Cut
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R23: edit an earlier message and carry on from there; and the words under a message that got no answer. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class EditMessageTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var engine: Engine

    private fun three() = listOf(
        Turn(true, "First question", id = "m1", at = 1000), Turn(false, "First answer", id = "m1.a", at = 1001),
        Turn(true, "Second question", id = "m2", at = 2000), Turn(false, "Second answer", id = "m2.a", at = 2001),
        Turn(true, "Third question", id = "m3", at = 3000), Turn(false, "Third answer", id = "m3.a", at = 3001),
    )

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    private fun open(turns: List<Turn> = three()) {
        engine = testEngine(tmp.newFolder(), ready = true, turns = turns)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { AppRoot(FakeStation(LinkStation.empty("test").copy(paired = false)), Navigator()) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun editingTheFirstOfThreeMessagesCutsTheRestAndAsksAgainFromThere() {
        engine = testEngine(tmp.newFolder(), ready = true, turns = three())
        val cut = engine.editMessage("m1", "First question, better")
        assertNotNull(cut)
        assertEquals(listOf("m1", "m1.a", "m2", "m2.a", "m3", "m3.a"), cut!!.removed.map { it.id })
        waitFor { !engine.state.value.writing }
        val chat = engine.state.value.chat
        // Only the edited message and its new answer are left, in the same order, with the same id.
        assertEquals(listOf("m1", "m1.a"), chat.turns.map { it.id })
        assertEquals("First question, better", chat.turns[0].text)
        assertEquals(2, chat.turns[0].version)
        assertTrue(chat.turns[1].text.isNotBlank() && chat.turns[1].text != "First answer")
        // The PC is told about each turn that went (the old answer included), once.
        assertEquals(setOf("m2", "m2.a", "m3", "m3.a"), chat.cuts.map { it.id }.toSet() - "m1.a")
        assertTrue(chat.cuts.all { !it.sent && it.version == 2 })
        // The new answer is above the tombstone's version, so it replaces everywhere.
        assertTrue(chat.turns[1].version > chat.cuts.first { it.id == "m1.a" }.version)
    }

    @Test fun undoPutsBackTheOldWordsAndTheCutTurnsWithHigherVersions() {
        engine = testEngine(tmp.newFolder(), ready = true, turns = three())
        val cut = engine.editMessage("m2", "Second, changed")!!
        waitFor { !engine.state.value.writing }
        engine.undoEdit(cut)
        val chat = engine.state.value.chat
        assertEquals(listOf("First question", "First answer", "Second question", "Second answer", "Third question", "Third answer"), chat.turns.map { it.text })
        assertTrue("restored turns go to the PC again", chat.turns.drop(2).all { it.seq == 0L && it.version >= 3 })
        assertTrue(chat.cuts.isEmpty())
    }

    @Test fun aMessageFollowedByAProjectTaskCannotBeEdited() {
        engine = testEngine(tmp.newFolder(), ready = true, turns = listOf(
            Turn(true, "Fix the garden planner", id = "a", at = 1), Turn(false, "Started", id = "a.a", at = 2, task = "t1", project = "/p"),
            Turn(true, "Thanks", id = "b", at = 3), Turn(false, "Welcome", id = "b.a", at = 4),
        ))
        val chat = engine.state.value.chat
        assertFalse(engine.canEdit(chat, 0))
        assertTrue(engine.canEdit(chat, 2))
        assertNull(engine.editMessage("a", "Changed"))
        assertEquals(4, engine.state.value.chat.turns.size)
    }

    @Test fun aTombstoneFromTheOtherSideRemovesTheTurnAndAnOlderCopyNeverBringsItBack() {
        val chat = LocalChat("c", "m", three().map { it.copy(seq = 5) })
        val gone = LChatTurn("m2", true, "", at = 2000, version = 2, seq = 9, gone = true)
        val after = ChatSync.merge(chat, LChatRecord("c", null, 4000, 9, listOf(gone)), "m")
        assertEquals(listOf("m1", "m1.a", "m2.a", "m3", "m3.a"), after.turns.map { it.id })
        // The PC sends the old copy again (a late reply): ignored.
        val again = ChatSync.merge(after, LChatRecord("c", null, 4000, 10, listOf(LChatTurn("m2", true, "Second question", at = 2000, version = 1, seq = 4))), "m")
        assertEquals(after.turns.map { it.id }, again.turns.map { it.id })
        // An undo from another device (higher version) brings it back.
        val back = ChatSync.merge(again, LChatRecord("c", null, 4000, 11, listOf(LChatTurn("m2", true, "Second question", at = 2000, version = 5, seq = 11))), "m")
        assertEquals(listOf("m1", "m1.a", "m2", "m2.a", "m3", "m3.a"), back.turns.map { it.id })
        assertTrue(back.cuts.none { it.id == "m2" })
    }

    @Test fun anOwnCutIsKeptUntilTheOtherSideConfirmsAndOlderCopiesAreIgnored() {
        val chat = LocalChat("c", "m", three().take(2), cuts = listOf(Cut("m2", true, 2000, 2)))
        val after = ChatSync.merge(chat, LChatRecord("c", null, 4000, 9, listOf(LChatTurn("m2", true, "Second question", at = 2000, version = 1, seq = 4))), "m")
        assertEquals(2, after.turns.size)
        assertEquals(1, after.cuts.size)
        assertFalse(after.cuts[0].sent)
    }

    @Test fun theWordsUnderAMessageWithNoAnswerSayWhoStoppedIt() {
        assertEquals("You stopped this answer.", noAnswerWords(stopped = true, pc = true))
        assertEquals("Your PC didn't finish that answer. Try again.", noAnswerWords(stopped = false, pc = true))
        assertEquals("That answer didn't finish. Try again.", noAnswerWords(stopped = false, pc = false))
        // The PC's own plain reason replaces the general line, but never the owner's own Stop.
        assertEquals("Your PC's model isn't running.", interruptedWords(stopped = false, pc = true, why = "Your PC's model isn't running."))
        assertEquals("Your PC didn't finish that answer. Try again.", interruptedWords(stopped = false, pc = true, why = null))
        assertEquals("You stopped this answer.", interruptedWords(stopped = true, pc = true, why = "Your PC's model isn't running."))
    }

    @Test fun holdingOneOfMyMessagesOffersEditFirstAndEditingInPlaceCutsTheRestWithUndo() {
        open()
        compose.onNodeWithText("First question").performSemanticsAction(SemanticsActions.OnLongClick); compose.waitForIdle()
        val items = listOf(EDIT, COPY, SELECT_TEXT, SHARE, LISTEN_ITEM)
        for (item in items) assertTrue("$item in the menu", compose.onAllNodesWithText(item).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText(EDIT).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithTag("edit-box").performTextReplacement("First question, edited")
        compose.onNodeWithContentDescription("Send the edited message").performClick()
        waitFor { !engine.state.value.writing }
        compose.waitForIdle()
        assertEquals(listOf("m1", "m1.a"), engine.state.value.chat.turns.map { it.id })
        compose.onNodeWithText("First question, edited").assertIsDisplayed()
        compose.onNodeWithText("Later messages removed").assertIsDisplayed()
        compose.onNodeWithText("Second question").assertDoesNotExist()
        compose.onNodeWithText("Undo", substring = true).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Second question").assertIsDisplayed()
        compose.onNodeWithText("First question").assertIsDisplayed()
    }

    @Test fun cancelLeavesTheMessageAsItWas() {
        open()
        compose.onNodeWithText("Third question").performSemanticsAction(SemanticsActions.OnLongClick); compose.waitForIdle()
        compose.onNodeWithText(EDIT).performClick(); compose.waitForIdle()
        compose.onNodeWithTag("edit-box").performTextReplacement("Something else")
        compose.onNodeWithText("Cancel").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Third question").assertIsDisplayed()
        assertEquals(6, engine.state.value.chat.turns.size)
    }

    /** A model that says a few words, then waits until it is stopped. */
    private class WaitsForStop(private val words: List<String>) : io.github.odnwks.mimir.standalone.Runner {
        @Volatile private var stopped = false
        override fun open(path: String) = java.io.File(path).exists()
        override fun reply(prompt: String, maxTokens: Int, sink: io.github.odnwks.mimir.standalone.PieceSink): String {
            stopped = false
            for (w in words) if (!sink.onPiece(w.toByteArray())) break
            while (!stopped) Thread.sleep(10)
            return """{"gen_tokens":${words.size},"gen_ms":100}"""
        }
        override fun stop() { stopped = true }
        override fun close() {}
    }

    private fun stoppedChat(words: List<String>) {
        engine = testEngine(tmp.newFolder(), ready = true, runner = WaitsForStop(words))
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) {
                MimirTheme(reduceMotion = true) { AppRoot(FakeStation(LinkStation.empty("test").copy(paired = false)), Navigator()) }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { engine.send("Tell me a story") }
        waitFor { engine.state.value.writing && engine.state.value.partial.isNotEmpty() || words.isEmpty() && engine.state.value.writing }
        Thread.sleep(200)
        compose.runOnIdle { engine.stop() }
        waitFor { !engine.state.value.writing }
        compose.waitForIdle()
    }

    @Test fun stoppingAnAnswerKeepsWhatWasWrittenAndSaysYouStoppedIt() {
        stoppedChat(listOf("Once ", "upon "))
        compose.onNodeWithText("You stopped this answer.").assertIsDisplayed()
        compose.onNodeWithText("Once upon", substring = true).assertIsDisplayed()
        compose.onNodeWithText(TRY_AGAIN).assertIsDisplayed()
        compose.onAllNodesWithText("interrupted", substring = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test fun stoppingBeforeAnyWordSaysYouStoppedItAndOffersTryAgain() {
        stoppedChat(emptyList())
        compose.onNodeWithText("You stopped this answer.").assertIsDisplayed()
        compose.onNodeWithText(TRY_AGAIN).assertIsDisplayed()
        compose.onAllNodesWithText("Your PC didn't finish", substring = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    /** R23: the message box takes what the keyboard writes as it is (no doubled or lost words) and clears after Send. */
    @Test fun theMessageBoxKeepsWhatIsTypedAndClearsAfterSend() {
        open(emptyList())
        val box = compose.onNodeWithContentDescription("Message Mímir")
        box.performTextInput("Name one fruit")
        compose.waitForIdle()
        box.performTextInput(" please")
        compose.waitForIdle()
        fun typed() = compose.onNodeWithContentDescription("Message Mímir").fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text
        assertEquals("Name one fruit please", typed())
        box.performTextReplacement("Name one vegetable")
        compose.waitForIdle()
        assertEquals("Name one vegetable", typed())
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor { !engine.state.value.writing && engine.state.value.chat.turns.size == 2 }
        compose.waitForIdle()
        assertEquals("Name one vegetable", engine.state.value.chat.turns[0].text)
        assertTrue("the box is empty after Send", typed().isNullOrEmpty())
    }
}
