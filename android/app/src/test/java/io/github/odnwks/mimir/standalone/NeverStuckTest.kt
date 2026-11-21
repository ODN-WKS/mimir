package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.LookupLog
import io.github.odnwks.mimir.online.Net
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.ui.PlainCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lookup questions must never hang (owner, 2026-11-19: "it just sat there thinking"). Measured on
 * an S25: the forecast site didn't answer, and "Think first" thought about a weather question for
 * over 2 minutes without starting its answer. Every stage here has a limit, and each ends with an
 * honest answer.
 */
@RunWith(RobolectricTestRunner::class)
class NeverStuckTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val think0 = Engine.THINK_MAX_MS
    private val reply0 = Engine.REPLY_MAX_MS
    private val wait0 = Engine.LOOKUP_WAIT_MS

    /** A model stand-in: [script] decides the pieces for each prompt; it records what it read and when. */
    private class Model(private val script: (String, PieceSink) -> Unit) : Runner {
        val prompts = CopyOnWriteArrayList<String>()
        val warmed = CopyOnWriteArrayList<String>()
        val limits = CopyOnWriteArrayList<Int>()
        @Volatile var openedAt = 0L
        override fun open(path: String): Boolean { openedAt = System.currentTimeMillis(); return File(path).exists() }
        override fun warm(prompt: String): String { warmed += prompt; return """{"new_prompt_tokens":10}""" }
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            prompts += prompt
            limits += maxTokens
            script(prompt, sink)
            return """{"gen_tokens":5,"gen_ms":100,"prompt_tokens":20,"new_prompt_tokens":20,"prompt_ms":10}"""
        }
        override fun stop() {}
        override fun close() {}
    }

    @Before fun setUp() { web.install(); Online.set(Online.Settings()); LookupLog.clear(); Catalog.reset() }

    @After fun tearDown() {
        Engine.THINK_MAX_MS = think0
        Engine.REPLY_MAX_MS = reply0
        Engine.LOOKUP_WAIT_MS = wait0
        Online.set(Online.Settings())
    }

    private fun engine(runner: Runner): Engine {
        val dir = tmp.newFolder()
        File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(1_800_000_000_000L) }
        return Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), runner, supported = true, now = { 1_800_000_000_000L })
    }

    /** Waits for the reply; returns how long it took. */
    private fun Engine.done(limitMs: Long): Long {
        val t0 = System.currentTimeMillis()
        Thread.sleep(20)
        while (state.value.writing && System.currentTimeMillis() - t0 < limitMs + 5_000) Thread.sleep(10)
        assertFalse("the reply never finished", state.value.writing)
        return System.currentTimeMillis() - t0
    }

    private fun pieces(sink: PieceSink, vararg words: String) { for (w in words) if (!sink.onPiece(w.toByteArray())) return }

    @Test fun thinkingThatNeverEndsIsClosedAndTheAnswerWritten() {
        Engine.THINK_MAX_MS = 300
        val model = Model { prompt, sink ->
            if (prompt.endsWith("</think>\n\n")) pieces(sink, "Paris", " is the capital.")
            else if (sink.onPiece("<think>\n".toByteArray())) while (sink.onPiece("hmm ".toByteArray())) Thread.sleep(5)
        }
        val e = engine(model)
        e.setThink(true)
        e.send("What is the capital of France? Explain your reasoning carefully.")
        val took = e.done(2_000)
        assertTrue("took $took ms", took < 2_000)
        assertEquals("Paris is the capital.", e.state.value.chat.turns.last().text)
        assertTrue("the second read starts with the first one", model.prompts[1].startsWith(model.prompts[0]))
    }

    @Test fun aReplyThatNeverEndsIsStoppedAndSaysSo() {
        Engine.REPLY_MAX_MS = 400
        val e = engine(Model { _, sink -> while (sink.onPiece("on and on ".toByteArray())) Thread.sleep(5) })
        e.send("Tell me a story")
        val took = e.done(1_500)
        assertTrue("took $took ms", took < 1_500)
        assertTrue(e.state.value.chat.turns.last().text.endsWith("(Stopped here: it was taking too long on this phone.)"))
    }

    @Test fun aLookupThatHangsIsAnsweredWithoutItAndSaysSo() {
        Engine.LOOKUP_WAIT_MS = 600
        Net.transport = { Thread.sleep(60_000); throw java.io.IOException("never") }
        val model = Model { _, sink -> pieces(sink, "It didn't come back in time.") }
        val e = engine(model)
        e.send("what's the weather in Portland")
        val took = e.done(1_500)
        assertTrue("took $took ms", took < 2_000)
        assertTrue(model.prompts.last().contains("didn't come back in time"))
        assertTrue("Try again shows", e.state.value.lookupFailed)
        assertEquals(null, e.state.value.looking)
    }

    @Test fun stopDuringALookupStopsAtOnce() {
        Net.transport = { Thread.sleep(60_000); throw java.io.IOException("never") }
        val model = Model { _, sink -> pieces(sink, "x") }
        val e = engine(model)
        e.send("what's the weather in Portland")
        Thread.sleep(200)
        assertTrue("it says what it's doing", e.state.value.looking?.startsWith("Looking up the weather") == true)
        val t0 = System.currentTimeMillis()
        e.stop()
        e.done(1_000)
        assertTrue(System.currentTimeMillis() - t0 < 1_000)
        assertTrue("no answer was written", model.prompts.isEmpty())
        assertEquals(null, e.state.value.looking)
    }

    @Test fun theModelLoadsAndReadsTheChatWhileTheLookupFetches() {
        val fetchedAt = java.util.concurrent.atomic.AtomicLong()
        val fake = Net.transport
        Net.transport = { r -> Thread.sleep(300); fetchedAt.set(System.currentTimeMillis()); fake(r) }
        web.geocode = """{"results":[{"name":"Portland","admin1":"Oregon","country":"United States","latitude":45.5152,"longitude":-122.6784}]}"""
        val model = Model { _, sink -> pieces(sink, "Light rain.") }
        val e = engine(model)
        e.setThink(true)
        e.send("what's the weather in Portland")
        e.done(8_000)
        assertTrue("the model loaded before the weather came back", model.openedAt in 1 until fetchedAt.get())
        assertEquals(1, model.warmed.size)
        assertTrue("what was read while fetching is the start of the prompt", model.prompts.last().startsWith(model.warmed[0]))
        assertTrue("the weather is in it", model.prompts.last().contains("[Information the app fetched"))
        // A lookup's answer is a plain fact: no thinking first, and a short answer.
        assertTrue(model.prompts.last().endsWith(Prompt.NO_THOUGHT))
        assertTrue(model.limits.last() <= Engine.LOOKUP_REPLY_TOKENS)
    }

    @Test fun typingLoadsTheModelAndReadsTheChatAhead() {
        val model = Model { _, sink -> pieces(sink, "Hello.") }
        val e = engine(model)
        e.send("Hi")
        e.done(2_000)
        val before = model.warmed.size
        e.prepare()
        val until = System.currentTimeMillis() + 2_000
        while (model.warmed.size == before && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals("read ahead once, while typing", before + 1, model.warmed.size)
        e.prepare()
        Thread.sleep(100)
        assertEquals("typing more doesn't read it again", before + 1, model.warmed.size)
        val ahead = model.warmed.last()
        assertTrue("it is the start of what came before plus the new message's opening", ahead.startsWith(model.prompts[0]))
        e.send("And again")
        e.done(2_000)
        assertTrue("the answer's prompt starts with what was read ahead", model.prompts.last().startsWith(ahead))
    }
}
