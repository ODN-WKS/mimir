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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.URL

/**
 * In a normal chat, a web search answer comes from the top pages themselves, with numbered
 * sources (P21 stage 1); the owner is asked once first; a Whisper behaves exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
class PageEvidenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val pages = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val words = "best electric kettle"
    private val fromPc = listOf(
        Search.Result("Kettle guide", "https://kettles.example/guide", "A guide to kettles.", listOf("brave")),
        Search.Result("Tea blog", "https://tea.example/post", "Notes on tea.", listOf("google")),
        Search.Result("Shop", "https://shop.example/kettle", "Kettles for sale.", listOf("brave")),
    )

    @Before fun setUp() {
        web.install()
        val fake = Net.transport
        Net.transport = { r ->
            val host = r.url.host
            fun page(text: String) = Net.Reply(200, null, "text/html; charset=utf-8",
                "<html><head><title>$host</title></head><body><main><p>$text</p></main></body></html>".toByteArray())
            when (host) {
                "kettles.example" -> { pages += host; page("The Quickboil kettle boils a litre in ninety seconds and costs 39 pounds.") }
                "tea.example" -> { pages += host; page("Green tea wants water at eighty degrees, so a kettle with a dial helps.") }
                "shop.example" -> { pages += host; page("The Quickboil kettle is in stock in three colours.") }
                "www.coindesk.com", "www.example.org" -> { pages += host; page("Bitcoin pages say many things.") }
                else -> fake(r)
            }
        }
        Online.set(Online.Settings())
        Online.dataSaver = { false }
        LookupLog.clear()
        Search.forget()
    }

    @After fun tearDown() {
        Search.viaPc = null
        Online.set(Online.Settings())
        Online.dataSaver = { false }
        Search.forget()
    }

    private fun engine(runner: Runner): Engine {
        Catalog.reset()
        val dir = tmp.newFolder()
        File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(1_800_000_000_000L) }
        return Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), runner, supported = true, now = { 1_800_000_000_000L })
    }

    private fun Engine.waitDone(ms: Long = 8_000) {
        val until = System.currentTimeMillis() + ms
        Thread.sleep(30)
        while (state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    /** The fetched part of the last prompt. */
    private fun fetched(p: String) = p.substringAfter("[Information the app fetched").substringBefore("[End of fetched information]")

    @Test fun withPagesAcceptedTheAnswerReadsThePagesWithNumberedSourcesInOrder() {
        Online.set(Online.Settings(searchAccepted = true, pagesAccepted = true))
        Search.viaPc = { fromPc }
        val runner = PromptRunner()
        val e = engine(runner)
        e.send("search for $words")
        e.waitDone()
        assertNull(e.state.value.askSearch)
        assertTrue(pages.toString(), "kettles.example" in pages)
        val block = fetched(runner.prompts.last())
        assertTrue(block, block.contains("[1]"))
        // A page's own words, not only its snippet.
        assertTrue(block, block.contains("Quickboil"))
        assertTrue(block, block.contains(Evidence.INSTRUCTION))
        // One source per number, in number order, found through the PC.
        val sources = e.state.value.chat.turns.last().sources
        assertTrue(sources.isNotEmpty())
        assertEquals(sources.size, sources.map { it.site }.distinct().size)
        sources.forEachIndexed { i, s ->
            assertTrue("[${i + 1}] ${s.site} in $block", block.contains("[${i + 1}] ${s.site}"))
            assertEquals("your PC's search", s.via)
        }
        assertFalse(block, block.contains("[${sources.size + 1}]"))
    }

    @Test fun withoutPagesAcceptedTheWarningShowsOnThePcPathAndAcceptingReadsThePages() {
        Online.set(Online.Settings(searchAccepted = true, pagesAccepted = false))
        Search.viaPc = { fromPc }
        val runner = PromptRunner()
        val e = engine(runner)
        e.send("search for $words")
        e.waitDone()
        assertEquals(words, e.state.value.askSearch)
        assertEquals(
            "Mímir searches the web and reads the top pages it finds for you. Your search words go to your PC's search or to DuckDuckGo, " +
                "and each page it reads sees a visit from this phone. A site may sometimes block it. Use it anyway?",
            Search.WARNING,
        )
        // Nothing is read before the owner says yes.
        assertTrue(pages.toString(), pages.isEmpty())
        e.acceptSearch()
        e.waitDone()
        assertTrue(Online.settings.value.searchAccepted)
        assertTrue(Online.settings.value.pagesAccepted)
        assertTrue(pages.toString(), "kettles.example" in pages)
        val block = fetched(runner.prompts.last())
        assertTrue(block, block.contains("[1]"))
        assertTrue(block, block.contains("Quickboil"))
    }

    @Test fun notNowToReadingPagesAnswersFromTheResultsAsBefore() {
        Online.set(Online.Settings(searchAccepted = true, pagesAccepted = false))
        Search.viaPc = { fromPc }
        val runner = PromptRunner()
        val e = engine(runner)
        e.send("search for $words")
        e.waitDone()
        assertEquals(words, e.state.value.askSearch)
        e.declineSearch()
        e.waitDone()
        assertFalse(Online.settings.value.pagesAccepted)
        assertTrue(pages.toString(), pages.isEmpty())
        assertTrue(runner.prompts.last().contains(Search.describe(words, fromPc, "the owner's PC search")))
        assertFalse(e.state.value.chat.turns.last().user)
    }

    @Test fun aFirstSearchWithNothingAcceptedWarnsBeforeSearchingAndAcceptingSetsBoth() {
        Search.viaPc = { null }
        val runner = PromptRunner()
        val e = engine(runner)
        e.send("search for cheap flights to Oslo")
        e.waitDone()
        assertEquals("cheap flights to Oslo", e.state.value.askSearch)
        assertTrue(web.asked.none { "duckduckgo" in it })
        e.acceptSearch()
        e.waitDone()
        assertTrue(Online.settings.value.searchAccepted && Online.settings.value.pagesAccepted)
        assertEquals(1, web.asked.count { "duckduckgo" in it })
        assertTrue(pages.toString(), pages.isNotEmpty())
        assertTrue(fetched(runner.prompts.last()).contains(Evidence.INSTRUCTION))
    }

    @Test fun aWhisperWithInternetGetsTodaysBlockAndReadsNoPage() {
        Online.set(Online.Settings(searchAccepted = true, pagesAccepted = true))
        val direct = Net.transport
        val viaTor = java.util.concurrent.CopyOnWriteArrayList<String>()
        Net.transport = { throw AssertionError("a direct connection in a Whisper with internet") }
        Net.resolve = { throw AssertionError("the phone looked up a site's name") }
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
            val runner = PromptRunner()
            val e = engine(runner)
            e.startWhisper(web = true)
            e.send("search for cheap flights to Oslo")
            e.waitDone()
            val p = runner.prompts.last()
            val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(1_800_000_000_000L))
            val today = Prompt.fetched(Search.describe("cheap flights to Oslo", Search.parse(FakeWeb.LITE)), "DuckDuckGo", at)
            assertTrue(p, p.contains(today))
            assertFalse(p.contains(Evidence.INSTRUCTION))
            assertEquals(viaTor.toString(), 1, viaTor.size)
            assertTrue(viaTor.single().contains("duckduckgo"))
            assertTrue(pages.isEmpty())
            e.endWhisper()
        } finally {
            TorLink.stop()
            TorLink.core = TorLink.Native
            TorLink.stateDir = null
            TorLink.cacheDir = null
        }
    }

    /** A model that cites a source that doesn't exist. */
    private class CitingRunner : Runner {
        val prompts = mutableListOf<String>()
        override fun open(path: String) = File(path).exists()
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            prompts += prompt
            sink.onPiece("The Quickboil boils a litre in ninety seconds [1]. Green tea wants eighty degrees [2]. It won an award [7].".toByteArray())
            return """{"gen_tokens":20,"gen_ms":100}"""
        }
        override fun stop() {}
        override fun close() {}
    }

    @Test fun aSourceNumberThatDoesNotExistIsTakenOutOfTheStoredAnswer() {
        Online.set(Online.Settings(searchAccepted = true, pagesAccepted = true))
        Search.viaPc = { fromPc }
        val runner = CitingRunner()
        val e = engine(runner)
        e.send("search for $words")
        e.waitDone()
        val answer = e.state.value.chat.turns.last()
        assertFalse(answer.user)
        assertTrue(answer.sources.size <= 3)
        assertTrue(answer.text, answer.text.contains("[1]"))
        assertFalse(answer.text, answer.text.contains("[7]"))
    }

    @Test fun anAnswerWithoutEvidenceKeepsItsBracketsAsWritten() {
        // Today's path (no pages accepted, and "Not now" said): nothing is checked or taken out.
        Online.set(Online.Settings(searchAccepted = true, pagesAccepted = false))
        Search.viaPc = { fromPc }
        val e = engine(CitingRunner())
        e.send("search for $words")
        e.waitDone()
        e.declineSearch()
        e.waitDone()
        assertTrue(e.state.value.chat.turns.last().text.contains("[7]"))
    }

    @Test fun anEarlierTurnsEvidenceIsStillCutForAFollowUp() {
        val long = "Kettles boil water quickly and the Quickboil is one of the fastest of them all. ".repeat(4)
        val chosen = (1..4).map { Chosen(it, "site$it.example", "https://site$it.example/p", "Page $it", null, long) }
        val block = Prompt.fetched(Evidence.block(words, chosen, 4), "a web search", "10:00")
        assertTrue(block.length > Prompt.EARLIER_CHARS * 2)
        val earlier = Prompt.earlier(block)!!
        val body = earlier.substringAfter("]\n").substringBefore("\n[End of earlier lookup]")
        assertTrue("${body.length}", body.length <= Prompt.EARLIER_CHARS + 1)
        assertTrue(body, body.startsWith("Search: \"$words\""))
        assertTrue(body.endsWith("…"))
    }
}
