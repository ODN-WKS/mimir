package io.github.odnwks.mimir.online

import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.PieceSink
import io.github.odnwks.mimir.standalone.Prompt
import io.github.odnwks.mimir.standalone.Runner
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.ui.PlainCodec
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P21 stage 2, piece C1: in a normal chat with web search on, the phone's model may ask for a
 * lookup itself with one line, "SEARCH: <words>". The app hides that line, looks the words up and
 * asks again. "Where is Paris" is answered at once; a Whisper behaves exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
class ModelAsksSearchTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val now = 1_800_000_000_000L

    /** The words each web search was made with (through the PC). */
    private val searched = CopyOnWriteArrayList<String>()
    private val results = listOf(
        Search.Result("AI models compared", "https://models.example/compare", "A list of open AI models and their rules.", listOf("brave")),
        Search.Result("Model news", "https://news.example.org/models", "Which models answer anything.", listOf("google")),
    )

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings(searchAccepted = true))
        Online.dataSaver = { false }
        LookupLog.clear()
        Search.forget()
        Search.viaPc = { w -> searched += w; results }
        Catalog.reset()
        ShadowLog.clear()
    }

    @After fun tearDown() {
        Search.viaPc = null
        Online.set(Online.Settings())
        Search.forget()
        Catalog.reset()
    }

    /**
     * A model stand-in: [script] gives the reply for the n-th prompt (from 0), streamed a few
     * characters at a time. It records each prompt, its token cap, whether it was stopped, and
     * every text the chat showed while it wrote.
     */
    private class Scripted(private val script: (Int, String) -> String) : Runner {
        val prompts = CopyOnWriteArrayList<String>()
        val limits = CopyOnWriteArrayList<Int>()
        val stopped = CopyOnWriteArrayList<Int>()
        val shown = CopyOnWriteArrayList<String>()
        var engine: Engine? = null
        override fun open(path: String) = File(path).exists()
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            val n = prompts.size
            prompts += prompt
            limits += maxTokens
            val text = script(n, prompt)
            var sent = 0
            for (piece in text.chunked(3)) {
                val go = sink.onPiece(piece.toByteArray())
                engine?.state?.value?.partial?.let { shown += it }
                sent++
                if (!go) { stopped += n; return """{"error":"stopped","gen_tokens":$sent,"gen_ms":10}""" }
            }
            return """{"gen_tokens":$sent,"gen_ms":100}"""
        }
        override fun stop() {}
        override fun close() {}
    }

    private fun engine(runner: Scripted, folder: File = tmp.newFolder()): Engine {
        File(folder, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(now) }
        return Engine(folder, Store(File(folder, "standalone.bin"), PlainCodec), runner, supported = true, now = { now }).also { runner.engine = it }
    }

    private fun Engine.waitDone(ms: Long = 8_000) {
        val until = System.currentTimeMillis() + ms
        Thread.sleep(30)
        while (state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse("the reply never finished", state.value.writing)
    }

    private val fetchedMark = "[Information the app fetched"

    private fun Engine.chatText() = state.value.chat.turns.joinToString("\n") { it.text }

    // ---- 1. The model asks, the app looks it up, the model answers from it ----

    @Test fun aSearchLineIsHiddenLookedUpAndAnsweredFromWhatWasFound() {
        val answer = "From what was found, several open models answer anything [1]."
        val runner = Scripted { n, _ -> if (n == 0) "SEARCH: most unethical AI model 2026\nAnd then more words that never show." else answer }
        val e = engine(runner)
        val question = "What is the most unethical AI model available to the public"
        e.send(question)
        e.waitDone()
        assertEquals(listOf("most unethical AI model 2026"), searched)
        assertEquals(2, runner.prompts.size)
        // The first prompt holds the sentence and the date, and no fetched text.
        assertTrue(runner.prompts[0].contains(Prompt.ASK_SEARCH))
        assertTrue(runner.prompts[0].contains(Prompt.askSearch(now)))
        assertFalse(runner.prompts[0].contains(fetchedMark))
        // The model was stopped at the end of its search line.
        assertEquals(listOf(0), runner.stopped)
        // The second prompt has what was found for this message.
        assertTrue(runner.prompts[1].contains(fetchedMark))
        assertTrue(runner.prompts[1].contains("most unethical AI model 2026"))
        // Shown and kept: the second answer only, never the search line.
        val turns = e.state.value.chat.turns
        assertEquals(2, turns.size)
        assertEquals(question, turns[0].text)
        assertEquals(answer, turns[1].text)
        assertTrue(turns[1].sources.isNotEmpty())
        assertTrue(runner.shown.toString(), runner.shown.none { "search:" in it.lowercase() || "never show" in it })
        assertFalse(e.chatText().contains("SEARCH", ignoreCase = false))
        assertEquals("", e.state.value.partial)
    }

    @Test fun theDateIsTheDayOnlyAndTheSentenceIsTheOwnersWords() {
        val line = Prompt.askSearch(now)
        assertTrue(line, Regex("^Today is (Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday) \\d{1,2} [A-Z][a-z]+ \\d{4}\\. ").containsMatchIn(line))
        assertFalse("no time of day", Regex("\\d{1,2}:\\d{2}").containsMatchIn(line))
        assertEquals(
            "If you need facts you were not given (anything current or recent, a price, a score, a schedule, a release, " +
                "who holds a job now, or anything you are not sure of), do not answer: reply with one line only, SEARCH: and the " +
                "best search words. The app looks it up and asks you again. For general knowledge, just answer.",
            Prompt.ASK_SEARCH,
        )
        assertTrue(line.endsWith(Prompt.ASK_SEARCH))
        // The owner's no-guessing sentence is still there, word for word.
        val p = Prompt.build(Catalog.default.format, listOf(io.github.odnwks.mimir.standalone.Turn(true, "Hi")), online = true, ask = line)
        assertTrue(p.contains(Prompt.NO_GUESS))
        assertTrue(p.contains(line))
        assertFalse(Prompt.build(Catalog.default.format, listOf(io.github.odnwks.mimir.standalone.Turn(true, "Hi")), online = true, ask = null).contains(Prompt.ASK_SEARCH))
    }

    // ---- 2. General knowledge streams at once, in full ----

    @Test fun generalKnowledgeIsAnsweredAtOnceWithNoSearch() {
        val runner = Scripted { _, _ -> "Paris is in France." }
        val e = engine(runner)
        e.send("where is Paris")
        e.waitDone()
        assertTrue(searched.isEmpty())
        assertTrue(web.asked.toString(), web.asked.isEmpty())
        assertEquals(1, runner.prompts.size)
        assertEquals("Paris is in France.", e.state.value.chat.turns.last().text)
        assertTrue("it streamed", runner.shown.any { it == "Paris is in France." })
        assertTrue(runner.stopped.isEmpty())
    }

    @Test fun anAnswerThatOnlyStartsLikeTheSearchWordIsShownInFull() {
        val runner = Scripted { _, _ -> "Search engines read pages and list them." }
        val e = engine(runner)
        e.send("how do web indexes work")
        e.waitDone()
        assertTrue(searched.isEmpty())
        assertEquals(1, runner.prompts.size)
        assertEquals("Search engines read pages and list them.", e.state.value.chat.turns.last().text)
        assertTrue(runner.shown.toString(), runner.shown.any { it.startsWith("Search engines") })
    }

    // ---- 3. The words go back through the Router: the weather takes the weather path ----

    @Test fun aSearchForTheWeatherTakesTheWeatherPath() {
        val runner = Scripted { n, _ -> if (n == 0) " search: weather Tokyo tomorrow" else "Light rain tomorrow." }
        val e = engine(runner)
        e.send("do I need a brolly over there")
        e.waitDone()
        assertTrue("no web search", searched.isEmpty())
        assertTrue(web.asked.toString(), web.asked.any { "geocoding-api" in it && "Tokyo" in it })
        assertTrue(runner.prompts.last(), runner.prompts.last().contains("Weather for"))
        assertEquals("Light rain tomorrow.", e.state.value.chat.turns.last().text)
    }

    // ---- 4. The second pass never asks again ----

    @Test fun aSecondSearchLineIsShownAsTextWithNoSecondLookup() {
        val runner = Scripted { n, _ -> if (n == 0) "SEARCH: newest kettle models" else "SEARCH: x" }
        val e = engine(runner)
        e.send("which kettle came out last week")
        e.waitDone()
        assertEquals(listOf("newest kettle models"), searched)
        assertEquals(2, runner.prompts.size)
        assertEquals("SEARCH: x", e.state.value.chat.turns.last().text)
    }

    // ---- 5. A Whisper is unchanged ----

    @Test fun aWhisperHasNoSentenceAndShowsASearchLineAsText() {
        val runner = Scripted { _, _ -> "SEARCH: newest kettle models" }
        val e = engine(runner)
        e.startWhisper()
        e.send("which kettle came out last week")
        e.waitDone()
        assertEquals(1, runner.prompts.size)
        assertFalse(runner.prompts[0].contains(Prompt.ASK_SEARCH))
        assertFalse(runner.prompts[0].contains("Today is "))
        assertTrue(searched.isEmpty())
        assertTrue(web.asked.isEmpty())
        assertEquals("SEARCH: newest kettle models", e.state.value.chat.turns.last().text)
        e.endWhisper()
    }

    @Test fun aWhisperWithInternetHasNoSentenceAndShowsASearchLineAsText() {
        val direct = Net.transport
        val viaTor = CopyOnWriteArrayList<String>()
        Net.transport = { throw AssertionError("a direct connection in a Whisper with internet") }
        TorLink.core = object : TorLink.Core {
            override fun new(state: String, cache: String) = 7L
            override fun connect(h: Long, timeoutMs: Long) = ""
            override fun progress(h: Long) = 100L
            override fun get(h: Long, url: String, headers: String, timeoutMs: Long, maxBytes: Long): String {
                viaTor += url
                val r = direct(Net.Request(URL(url), emptyMap(), 1000, 1_000_000))
                return JSONObject().put("status", r.status).put("type", r.type).put("body", String(r.body)).toString()
            }
            override fun cancel(h: Long) {}
            override fun stop(h: Long) {}
        }
        TorLink.setUp(tmp.newFolder(), tmp.newFolder())
        try {
            val runner = Scripted { _, _ -> "SEARCH: newest kettle models" }
            val e = engine(runner)
            e.startWhisper(web = true)
            e.send("which kettle came out last week")
            e.waitDone()
            assertEquals(1, runner.prompts.size)
            assertFalse(runner.prompts[0].contains(Prompt.ASK_SEARCH))
            assertTrue(searched.isEmpty())
            assertTrue(viaTor.toString(), viaTor.isEmpty())
            assertEquals("SEARCH: newest kettle models", e.state.value.chat.turns.last().text)
            e.endWhisper()
        } finally {
            TorLink.stop()
            TorLink.core = TorLink.Native
            TorLink.stateDir = null
            TorLink.cacheDir = null
        }
    }

    // ---- 6. Web search off in Settings ----

    @Test fun withWebSearchOffThereIsNoSentenceAndNoSearchLineHandling() {
        Online.set(Online.Settings(search = false, searchAccepted = true))
        val runner = Scripted { _, _ -> "SEARCH: newest kettle models" }
        val e = engine(runner)
        e.send("which kettle came out last week")
        e.waitDone()
        assertEquals(1, runner.prompts.size)
        assertFalse(runner.prompts[0].contains(Prompt.ASK_SEARCH))
        assertTrue(searched.isEmpty())
        assertEquals("SEARCH: newest kettle models", e.state.value.chat.turns.last().text)
    }

    @Test fun withLookupsOffThereIsNoSentenceAndNoSearchLineHandling() {
        Online.set(Online.Settings(lookups = false, searchAccepted = true))
        val runner = Scripted { _, _ -> "SEARCH: newest kettle models" }
        val e = engine(runner)
        e.send("which kettle came out last week")
        e.waitDone()
        assertEquals(1, runner.prompts.size)
        assertFalse(runner.prompts[0].contains(Prompt.ASK_SEARCH))
        assertTrue(searched.isEmpty())
        assertEquals("SEARCH: newest kettle models", e.state.value.chat.turns.last().text)
    }

    // ---- 7. Think first: a short decision first, with thinking off ----

    @Test fun withThinkFirstASearchLineFromTheShortDecisionStartsTheLookup() {
        val runner = Scripted { n, _ -> if (n == 0) "SEARCH: latest phone release" else "The newest phone came out on Monday [1]." }
        val e = engine(runner)
        e.setThink(true)
        e.send("what phone came out this week")
        e.waitDone()
        assertEquals(Engine.DECIDE_TOKENS, runner.limits[0])
        assertTrue(Engine.DECIDE_TOKENS <= 24)
        assertTrue("the decision doesn't think", runner.prompts[0].endsWith(Prompt.NO_THOUGHT))
        assertTrue(runner.prompts[0].contains(Prompt.ASK_SEARCH))
        assertEquals(listOf("latest phone release"), searched)
        assertEquals(2, runner.prompts.size)
        assertTrue(runner.prompts[1].contains(fetchedMark))
        assertEquals("The newest phone came out on Monday [1].", e.state.value.chat.turns.last().text)
        assertTrue(ShadowLog.getLogs().any { "reply timing" in it.msg && "decide=" in it.msg })
    }

    @Test fun withThinkFirstAPlainDecisionIsDroppedAndOneThinkingReplyIsWritten() {
        val runner = Scripted { n, _ -> if (n == 0) "Paris is in France, in the" else "<think>\nEasy.\n</think>\n\nParis is in France." }
        val e = engine(runner)
        e.setThink(true)
        e.send("where is Paris")
        e.waitDone()
        assertEquals(2, runner.prompts.size)
        assertEquals(Engine.DECIDE_TOKENS, runner.limits[0])
        assertTrue(runner.prompts[0].endsWith(Prompt.NO_THOUGHT))
        assertTrue("the answer thinks first", runner.prompts[1].endsWith("<|im_start|>assistant\n"))
        assertTrue(searched.isEmpty())
        assertEquals("Paris is in France.", e.state.value.chat.turns.last().text)
        assertTrue("the decision's words never show", runner.shown.none { it.endsWith("in the") })
    }

    // ---- The reference rule: words that point back are asked of the model first ----

    @Test fun aRouterSearchThatPointsBackGetsItsWordsFromTheModel() {
        val runner = Scripted { n, _ ->
            when (n) {
                0 -> "The Quickboil and the Rapidpour are both good kettles."
                1 -> "SEARCH: Rapidpour kettle price"
                else -> "The Rapidpour costs 29 pounds [1]."
            }
        }
        val e = engine(runner)
        e.send("name two good kettles")
        e.waitDone()
        e.send("search for the cheaper one")
        e.waitDone()
        assertEquals(listOf("Rapidpour kettle price"), searched)
        assertEquals(3, runner.prompts.size)
        assertEquals(Engine.DECIDE_TOKENS, runner.limits[1])
        assertTrue(runner.prompts[2].contains(fetchedMark))
        assertEquals("The Rapidpour costs 29 pounds [1].", e.state.value.chat.turns.last().text)
        assertFalse(e.chatText().contains("SEARCH"))
    }

    @Test fun withNoSearchLineTheRoutersWordsAreKept() {
        val runner = Scripted { n, _ -> if (n == 1) "I think you mean the second one." else "Both are good kettles." }
        val e = engine(runner)
        e.send("name two good kettles")
        e.waitDone()
        e.send("search for the cheaper one")
        e.waitDone()
        assertEquals(listOf("the cheaper one"), searched)
        assertEquals("Both are good kettles.", e.state.value.chat.turns.last().text)
    }

    // ---- 8. The safety net ----

    @Test fun anAnswerSayingItCouldNotLookLeadsToOneLookupAndIsReplaced() {
        val runner = Scripted { n, _ -> if (n == 0) "I couldn't look that up." else "Recipe box opens at nine [1]." }
        val e = engine(runner)
        e.send("Tell me something about Recipe box")
        e.waitDone()
        assertEquals(listOf("Tell me something about Recipe box"), searched)
        assertEquals(2, runner.prompts.size)
        assertTrue(runner.prompts[1].contains(fetchedMark))
        val turns = e.state.value.chat.turns
        assertEquals(2, turns.size)
        assertEquals("Recipe box opens at nine [1].", turns.last().text)
    }

    @Test fun theSafetyNetNeverRunsTwice() {
        val runner = Scripted { _, _ -> "I couldn't look that up." }
        val e = engine(runner)
        e.send("Tell me something about Recipe box")
        e.waitDone()
        assertEquals(1, searched.size)
        assertEquals(2, runner.prompts.size)
        assertEquals("I couldn't look that up.", e.state.value.chat.turns.last().text)
    }

    @Test fun theSafetyNetIsOffInAWhisper() {
        val runner = Scripted { _, _ -> "I couldn't look that up." }
        val e = engine(runner)
        e.startWhisper()
        e.send("Tell me something about Recipe box")
        e.waitDone()
        assertEquals(1, runner.prompts.size)
        assertTrue(searched.isEmpty())
        e.endWhisper()
    }

    // ---- 6 (per model): a model that doesn't ask gets only the Router and the safety net ----

    @Test fun aModelThatDoesNotAskHasNoSentenceButKeepsTheSafetyNet() {
        val quiet = Catalog.default.copy(id = "quiet-model", asksToSearch = false)
        assertTrue(Catalog.default.asksToSearch)
        assertTrue(Catalog.builtIn.all { it.asksToSearch })
        Catalog.found = listOf(quiet)
        val folder = tmp.newFolder()
        File(folder, "models").apply { mkdirs() }.resolve(quiet.file).apply { writeText("model"); setLastModified(now) }
        val runner = Scripted { n, _ -> if (n == 0) "SEARCH: newest kettle models" else "x" }
        val e = engine(runner, folder)
        e.choose(quiet.id)
        e.send("which kettle came out last week")
        e.waitDone()
        assertEquals(1, runner.prompts.size)
        assertFalse(runner.prompts[0].contains(Prompt.ASK_SEARCH))
        assertTrue(searched.isEmpty())
        assertEquals("SEARCH: newest kettle models", e.state.value.chat.turns.last().text)
        // The safety net still works for it.
        val net = Scripted { n, _ -> if (n == 0) "I don't have current information on that." else "It opens at nine [1]." }
        val folder2 = tmp.newFolder()
        File(folder2, "models").apply { mkdirs() }.resolve(quiet.file).apply { writeText("model"); setLastModified(now) }
        val e2 = engine(net, folder2)
        e2.choose(quiet.id)
        e2.send("Tell me something about Recipe box")
        e2.waitDone()
        assertEquals(listOf("Tell me something about Recipe box"), searched)
        assertEquals("It opens at nine [1].", e2.state.value.chat.turns.last().text)
    }

    // ---- 9. "search" alone after a question still works ----

    @Test fun searchAloneAfterAQuestionSearchesThatQuestion() {
        val runner = Scripted { n, _ -> if (n == 0) "It opens most days." else "It opens at nine [1]." }
        val e = engine(runner)
        e.send("Recipe box opening hours")
        e.waitDone()
        assertTrue(searched.isEmpty())
        e.send("search")
        e.waitDone()
        assertEquals(listOf("Recipe box opening hours"), searched)
        assertEquals("It opens at nine [1].", e.state.value.chat.turns.last().text)
    }
}
