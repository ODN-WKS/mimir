package io.github.odnwks.mimir.online

import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.ui.PlainCodec
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
import java.io.File

/**
 * "What is / who was" questions (owner, 2026-11-19): Wikipedia's search finds the exact title, the
 * same title is read on Grokipedia, and Mímir is told to answer what was asked from both, neither being
 * the authority, and to compare them only if asked. Grokipedia only after the one-time warning.
 */
@RunWith(RobolectricTestRunner::class)
class EncyclopediaTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
        LookupLog.clear()
        Search.forget()
    }

    @After fun tearDown() = Online.set(Online.Settings())

    private fun engine(runner: PromptRunner): Engine {
        Catalog.reset()
        val dir = tmp.newFolder()
        File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(1_800_000_000_000L) }
        return Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), runner, supported = true, now = { 1_800_000_000_000L })
    }

    private fun Engine.waitDone() {
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @Test fun onlyPlainWhatIsAndWhoWasQuestionsGoToTheEncyclopedias() {
        assertEquals(Router.Need.Encyclopedia("a black hole"), Router.need("What is a black hole?"))
        assertEquals(Router.Need.Encyclopedia("Ada Lovelace"), Router.need("who was Ada Lovelace"))
        assertEquals(Router.Need.Encyclopedia("the Roman Empire"), Router.need("tell me about the Roman Empire"))
        for (t in listOf("what is my current dose", "who are you", "what is the time", "what's up", "what is wrong with it", "what is the latest on the election", "what's the best pizza"))
            assertTrue(t, Router.need(t) !is Router.Need.Encyclopedia)
    }

    @Test fun bothAreReadUnderTheSameTitleAndComparedWithNeitherTheAuthority() {
        Online.set(Online.Settings(searchAccepted = true))
        val runner = PromptRunner()
        val e = engine(runner)
        e.send("What is a black hole?")
        e.waitDone()
        assertTrue(web.asked.any { "list=search" in it && "black+hole" in it })
        assertTrue(web.asked.any { it.endsWith("grokipedia.com/page/Black_hole") })
        val p = runner.prompts.last()
        assertTrue(p.contains("From Wikipedia, \"Black hole\""))
        assertTrue(p.contains("From Grokipedia, \"Black hole\""))
        assertTrue(p.contains("dates to 1783"))
        assertFalse(p.contains("var x"))
        assertFalse(p.contains("Sign in"))
        assertTrue(p.contains("treating neither as the authority"))
        assertEquals(listOf("Wikipedia", "Grokipedia"), e.state.value.chat.turns.last().sources.map { it.site })
    }

    @Test fun withNoGrokipediaPageOrABlockItAnswersFromWikipediaAndSaysSo() {
        Online.set(Online.Settings(searchAccepted = true))
        for (setUp in listOf<() -> Unit>({ web.grok = null }, { web.grok = FakeWeb.GROK; web.grokFails = true })) {
            setUp()
            val runner = PromptRunner()
            val e = engine(runner)
            e.send("What is a black hole?")
            e.waitDone()
            val p = runner.prompts.last()
            assertTrue(p.contains("From Wikipedia"))
            assertFalse(p.contains("From Grokipedia"))
            assertTrue(p.contains("Wikipedia only"))
            assertEquals(listOf("Wikipedia"), e.state.value.chat.turns.last().sources.map { it.site })
        }
    }

    @Test fun beforeTheWarningIsAcceptedTheFirstAskIsAnsweredFromWikipediaWithNoQuestion() {
        val runner = PromptRunner()
        val e = engine(runner)
        e.send("who was Ada Lovelace")
        e.waitDone()
        // Never held up by a question: Wikipedia answers at once, Grokipedia waits for the warning.
        assertNull(e.state.value.askSearch)
        assertTrue(web.asked.none { "grokipedia" in it })
        assertTrue(web.asked.any { "wikipedia" in it })
        assertEquals(listOf("Wikipedia"), e.state.value.chat.turns.last().sources.map { it.site })
    }

    @Test fun wikipediaAloneNeedsNoWarningAndAWhisperReadsNothing() {
        Online.set(Online.Settings(encyclopedias = Encyclopedias.Wikipedia))
        val e = engine(PromptRunner())
        e.send("What is a black hole?")
        e.waitDone()
        assertNull(e.state.value.askSearch)
        assertTrue(web.asked.none { "grokipedia" in it })
        assertEquals(listOf("Wikipedia"), e.state.value.chat.turns.last().sources.map { it.site })
        web.asked.clear()
        e.startWhisper()
        e.send("What is a black hole?")
        e.waitDone()
        assertTrue(web.asked.isEmpty())
    }
}
