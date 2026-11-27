package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.online.Router
import io.github.odnwks.mimir.online.Search
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.PieceSink
import io.github.odnwks.mimir.standalone.Runner
import io.github.odnwks.mimir.standalone.Skill
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** P46: the Skill bubble beside Model · Effort. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class P46SkillTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var engine: Engine

    private class Prompts : Runner {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun open(path: String) = File(path).exists()
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            seen += prompt
            sink.onPiece("Done.".toByteArray())
            return """{"gen_tokens":1,"gen_ms":10}"""
        }
        override fun stop() {}
        override fun close() {}
    }

    private val model = Prompts()
    private val searched = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        Search.forget()
        Online.set(Online.Settings(search = true, searchAccepted = true))
        Search.viaPc = { words -> searched += words; emptyList() }
    }

    @After fun tearDown() {
        Search.viaPc = null
        Online.set(Online.Settings())
        Search.forget()
    }

    private fun show(registry: SaveableStateRegistry? = null) {
        engine = testEngine(tmp.newFolder(), ready = true, runner = model)
        compose.setContent {
            val content = @androidx.compose.runtime.Composable {
                CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { PhoneChat(engine, nav) } }
            }
            if (registry != null) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { content() } else content()
        }
        compose.waitForIdle()
    }

    private fun done() {
        val until = System.currentTimeMillis() + 5_000
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(20)
        assertFalse(engine.state.value.writing)
        compose.waitForIdle()
    }

    private fun send(words: String, skill: Skill?) {
        assertTrue(engine.ask(words, skill = skill))
        done()
    }

    private fun cut(n: SemanticsNode): Boolean {
        val out = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        // Really cut: more than one line, an ellipsis, or words wider than their box. (hasVisualOverflow
        // is also true for a box that only shrank to its words.)
        return out.any { it.lineCount > 1 || it.isLineEllipsized(0) || it.getLineRight(0) > it.size.width + 0.5f } || n.children.any { cut(it) }
    }

    // 1: both bubbles show at 384 dp, side by side, and the Model · Effort one is never cut.
    @Test fun theBubbleSitsBesideModelAndEffort() {
        show()
        for (skill in listOf(null) + Skill.entries) {
            compose.runOnIdle { nav.skill = skill }
            compose.waitForIdle()
            val model = compose.onNodeWithContentDescription("Model and effort:", substring = true, useUnmergedTree = true)
            val chip = compose.onNodeWithTag(SKILL_CHIP_TAG)
            model.assertIsDisplayed()
            chip.assertIsDisplayed()
            val m = model.fetchSemanticsNode().boundsInRoot
            val c = chip.fetchSemanticsNode().boundsInRoot
            assertTrue("$skill overlaps: $m $c", m.right <= c.left)
            with(compose.density) { assertTrue("$skill off screen: $c", c.right <= 384.dp.toPx() && c.width >= 40.dp.toPx()) }
            assertFalse("$skill: model words are cut", cut(model.fetchSemanticsNode()))
        }
    }

    // 2: the instruction goes with the next message only, and the bubble goes back to none.
    @Test fun aPickedSkillGoesWithTheNextMessageOnly() {
        show()
        compose.runOnIdle { nav.skill = Skill.Teach }
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("how do tides work")
        compose.onNodeWithContentDescription("Send").performClick()
        done()
        assertTrue(model.seen.last().contains(Skill.Teach.instruction))
        assertNull(nav.skill)
        assertEquals(Skill.Teach.id, engine.state.value.chat.turns.first().skill)
        compose.onNodeWithText("Skill: Teach me", substring = true).assertIsDisplayed()
        send("and the moon?", null)
        assertFalse(model.seen.last().contains(Skill.Teach.instruction))
    }

    // 3: lookup skills force a lookup the Router wouldn't make; the others don't.
    @Test fun onlyLookupSkillsForceALookup() {
        val words = "tell me a story about a quiet garden"
        assertNull(Router.need(words))
        show()
        for (s in listOf(Skill.Writing, Skill.Teach, Skill.Summarize)) { engine.newChat(); send(words, s) }
        assertTrue("searched: $searched", searched.isEmpty())
        for (s in listOf(Skill.Deep, Skill.Shopping, Skill.Trace)) {
            searched.clear(); Search.forget(); engine.newChat(); send(words, s)
            assertEquals("$s", 1, searched.size)
        }
        assertTrue(Skill.People.lookup && Skill.People.need(words, null)!!.words.contains("forum"))
        assertTrue(Skill.entries.all { (it.need(words, null) != null) == it.lookup })
        assertTrue(Skill.Shopping.need("one two three four five six seven eight nine ten eleven twelve", null)!!.words.split(' ').size <= 12)
    }

    // 4: a Whisper with a lookup skill stores nothing and looks nothing up.
    @Test fun aWhisperWithALookupSkillStoresNothing() {
        val registry = SaveableStateRegistry(null) { true }
        show(registry)
        engine.startWhisper(); compose.waitForIdle()
        compose.runOnIdle { nav.skill = Skill.Deep }
        compose.onNodeWithTag(SKILL_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Whisper to $AI_NAME").performTextInput("secret whisper words")
        compose.waitForIdle()
        val saved = compose.runOnIdle { registry.performSave() }.toString()
        assertFalse(saved, saved.contains("secret whisper") || saved.contains(Skill.Deep.id) || saved.contains(Skill.Deep.title))
        compose.onNodeWithContentDescription("Send").performClick()
        done()
        assertTrue(searched.isEmpty())
        assertTrue(engine.drafts.texts().isEmpty())
        engine.endWhisper(); compose.waitForIdle()
        val kept = tmp.root.walk().filter { it.name == "standalone.bin" }.joinToString { it.readText() }
        assertFalse(kept, kept.contains("secret whisper") || kept.contains(Skill.Deep.id))
    }

    // 5: None and exactly the seven, in order; no "Act as me".
    @Test fun theSheetListsTheSevenSkills() {
        assertEquals(
            listOf("Deep research", "Shopping research", "Fix my writing", "Teach me", "What people are saying", "Summarize", "Trace a claim"),
            Skill.entries.map { it.title },
        )
        var picked: Skill? = null
        compose.setContent { MimirTheme(reduceMotion = true) { androidx.compose.foundation.layout.Column { SkillRows(null) { picked = it } } } }
        compose.onAllNodesWithTag(SKILL_ROW_TAG).assertCountEquals(8)
        compose.onNodeWithText("None").assertIsDisplayed()
        compose.onNodeWithText("Act as me", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Trace a claim").performClick()
        assertEquals(Skill.Trace, picked)
    }

    // The instructions, one by one.
    @Test fun eachSkillHasItsInstruction() {
        mapOf(
            Skill.Deep to "Bottom line", Skill.Shopping to "Never invent a price", Skill.Writing to "finished text first",
            Skill.Teach to "one question", Skill.People to "newest first", Skill.Summarize to "Short points first", Skill.Trace to "no verdict words",
        ).forEach { (s, words) ->
            assertTrue("$s", s.instruction.contains(words, ignoreCase = true))
            assertTrue("$s", s.instruction.contains("no unasked advice or warnings"))
        }
    }

    // A PC Whisper's words carry the instruction; on screen it reads as the skill's name.
    @Test fun aSkillSentInTheWordsShowsAsItsName() {
        val sent = Skill.withWords("check this", Skill.Trace)
        assertTrue(sent.contains(Skill.Trace.instruction))
        assertEquals("check this\n\nSkill: Trace a claim", Skill.shown(sent))
        assertEquals("check this", Skill.shown(Skill.withWords("check this", null)))
    }

    // The skill stays with the kept message.
    @Test fun theSkillIsKeptWithTheMessage() {
        val dir = tmp.newFolder()
        val store = io.github.odnwks.mimir.standalone.Store(File(dir, "s.bin"), PlainCodec)
        val chat = io.github.odnwks.mimir.standalone.LocalChat("c", "m", listOf(io.github.odnwks.mimir.standalone.Turn(true, "q", id = "1", skill = Skill.Trace.id)))
        store.save(io.github.odnwks.mimir.standalone.Saved("m", listOf(chat)))
        assertEquals(Skill.Trace.id, store.load().chats.single().turns.single().skill)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) = assertEquals(n, fetchSemanticsNodes().size)
