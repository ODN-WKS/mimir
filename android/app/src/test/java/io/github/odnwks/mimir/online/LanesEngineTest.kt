package io.github.odnwks.mimir.online

import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
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
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * P21 stage 3, piece B: in a normal chat a science question also reads PubMed beside the web
 * search, within the same time; a Whisper, Data Saver and web search off ask no database at all.
 */
@RunWith(RobolectricTestRunner::class)
class LanesEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val hang = CountDownLatch(1)

    /** Every address asked directly from this phone. */
    private val asked = CopyOnWriteArrayList<URL>()
    /** The words each web search through the PC was made with. */
    private val searched = CopyOnWriteArrayList<String>()

    private val question = "search for studies on vitamin d and depression"
    private val fromPc = listOf(
        Search.Result("Winter tips", "https://tips.example/winter", "Ways to enjoy the winter.", listOf("brave")),
        Search.Result("Sunshine blog", "https://sun.example/post", "Notes on sunshine.", listOf("google")),
    )

    private fun fixture(name: String) = javaClass.getResource("/lanes/$name")!!.readText()

    private fun page(title: String, text: String) = Net.Reply(200, null, "text/html; charset=utf-8",
        "<html><head><title>$title</title></head><body><main><p>$text</p></main></body></html>".toByteArray())

    @Before fun setUp() {
        web.install()
        val fake = Net.transport
        Net.transport = { r ->
            asked += r.url
            when (r.url.host) {
                "eutils.ncbi.nlm.nih.gov" -> Net.Reply(200, null, "application/json; charset=UTF-8",
                    fixture(if ("esearch" in r.url.path) "pubmed-esearch.json" else "pubmed-esummary.json").toByteArray())
                "pubmed.ncbi.nlm.nih.gov" -> page("Vitamin D and depression",
                    "In this study of 400 adults, low vitamin D levels were linked to more depression symptoms after one year.")
                "tips.example" -> page("Winter tips", "Vitamin D comes from the sun, so wear a warm coat and walk outside in the cold months.")
                "sun.example" -> page("Sunshine", "A walk in the sunshine lifts the mood of many people in spring.")
                else -> fake(r)
            }
        }
        Online.set(Online.Settings(searchAccepted = true))
        Online.dataSaver = { false }
        LookupLog.clear()
        Search.forget()
        Search.viaPc = { w -> searched += w; fromPc }
        Catalog.reset()
    }

    @After fun tearDown() {
        hang.countDown()
        Search.viaPc = null
        Online.set(Online.Settings())
        Online.dataSaver = { false }
        Search.forget()
    }

    private fun engine(runner: Runner): Engine {
        val dir = tmp.newFolder()
        File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(1_800_000_000_000L) }
        return Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), runner, supported = true, now = { 1_800_000_000_000L })
    }

    private fun Engine.waitDone(ms: Long = 10_000) {
        val until = System.currentTimeMillis() + ms
        Thread.sleep(30)
        while (state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse("the reply never finished", state.value.writing)
    }

    private fun fetched(p: String) = p.substringAfter("[Information the app fetched").substringBefore("[End of fetched information]")

    private fun laneAsks() = asked.filter { it.host in Lanes.HOSTS }

    @Test fun aScienceQuestionCanCitePubMedAndPubMedGetsOnlyTheSearchWords() {
        val runner = PromptRunner()
        val e = engine(runner)
        e.send(question)
        e.waitDone()
        val words = searched.single()
        val block = fetched(runner.prompts.last())
        // PubMed's page is in the evidence under its own site, with its own words.
        assertTrue(block, Regex("\\[\\d] pubmed\\.ncbi\\.nlm\\.nih\\.gov").containsMatchIn(block))
        assertTrue(block, block.contains("low vitamin D levels were linked to more depression symptoms"))
        val sources = e.state.value.chat.turns.last().sources
        val pubmed = sources.single { it.site == "pubmed.ncbi.nlm.nih.gov" }
        assertEquals("https://pubmed.ncbi.nlm.nih.gov/43092117/", pubmed.url)
        // Found from this phone, not through the PC.
        assertEquals("", pubmed.via)
        assertTrue(sources.filter { it.site != pubmed.site }.all { it.via == "your PC's search" })
        // PubMed's search holds the search words and nothing else of the chat.
        val search = laneAsks().single { it.path.endsWith("esearch.fcgi") }
        val params = search.query.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(words, params["term"])
        assertEquals(setOf("db", "retmode", "retmax", "sort", "term"), params.keys)
        assertTrue(laneAsks().none { "search+for" in it.toString() || "search%20for" in it.toString() })
        // The summary asks for PubMed's own ids only.
        assertTrue(laneAsks().single { it.path.endsWith("esummary.fcgi") }.query.endsWith("&id=43092117,43069169"))
        // At most 5 pages read.
        val read = asked.filter { it.host !in Lanes.HOSTS && it.host != "lite.duckduckgo.com" }.map { it.host }.distinct()
        assertTrue(read.toString(), read.size <= 5)
        // The Privacy check lists the database asked.
        assertTrue(LookupLog.entries.value.any { it.site == "eutils.ncbi.nlm.nih.gov" && it.words == words })
    }

    @Test fun aGeneralQuestionAsksNoDatabase() {
        val e = engine(PromptRunner())
        e.send("search for best electric kettle")
        e.waitDone()
        assertEquals(1, searched.size)
        assertTrue(laneAsks().toString(), laneAsks().isEmpty())
    }

    @Test fun aWhisperAndAWhisperWithInternetAskNoDatabase() {
        val e = engine(PromptRunner())
        e.startWhisper(web = false)
        e.send(question)
        e.waitDone()
        e.endWhisper()
        assertTrue(laneAsks().toString(), laneAsks().isEmpty())

        val direct = Net.transport
        val viaTor = CopyOnWriteArrayList<String>()
        Net.transport = { r -> asked += r.url; throw AssertionError("a direct connection in a Whisper with internet") }
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
            val w = engine(PromptRunner())
            w.startWhisper(web = true)
            w.send(question)
            w.waitDone()
            w.endWhisper()
            assertTrue(viaTor.toString(), viaTor.none { URL(it).host in Lanes.HOSTS })
            assertTrue(laneAsks().toString(), laneAsks().isEmpty())
        } finally {
            TorLink.stop()
            TorLink.core = TorLink.Native
            TorLink.stateDir = null
            TorLink.cacheDir = null
        }
    }

    @Test fun dataSaverAndWebSearchOffAskNoDatabase() {
        Online.dataSaver = { true }
        val e = engine(PromptRunner())
        e.send(question)
        e.waitDone()
        assertTrue(laneAsks().toString(), laneAsks().isEmpty())
        Online.dataSaver = { false }
        Online.set(Online.Settings(searchAccepted = true, search = false))
        Search.forget()
        val off = engine(PromptRunner())
        off.send(question)
        off.waitDone()
        assertTrue(laneAsks().toString(), laneAsks().isEmpty())
    }

    /** Review 2026-11-26: a blocked or failed web search still answers from what the databases found. */
    @Test fun whenTheWebSearchFailsTheDatabasesStillAnswer() {
        Search.viaPc = null
        val withPubMed = Net.transport
        Net.transport = { r -> if ("duckduckgo" in r.url.host) { asked += r.url; throw java.io.IOException("no signal") } else withPubMed(r) }
        val runner = PromptRunner()
        val e = engine(runner)
        e.send(question)
        e.waitDone()
        assertTrue(asked.toString(), asked.any { "duckduckgo" in it.host })
        val block = fetched(runner.prompts.last())
        assertTrue(block, Regex("\\[\\d] pubmed\\.ncbi\\.nlm\\.nih\\.gov").containsMatchIn(block))
        assertTrue(e.state.value.chat.turns.last().sources.any { it.site == "pubmed.ncbi.nlm.nih.gov" })
    }

    @Test fun aDatabaseThatHangsNeverHoldsTheAnswerPastTheLookupsBudget() {
        val fast = Net.transport
        Net.transport = { r -> if (r.url.host == "eutils.ncbi.nlm.nih.gov") { asked += r.url; hang.await(); throw java.io.IOException("never") } else fast(r) }
        val runner = PromptRunner()
        val e = engine(runner)
        val t0 = System.currentTimeMillis()
        e.send(question)
        e.waitDone()
        val took = System.currentTimeMillis() - t0
        assertTrue("took $took ms", took < Net.BUDGET_MS)
        assertTrue(laneAsks().isNotEmpty())
        // The web's pages still answer.
        val block = fetched(runner.prompts.last())
        assertTrue(block, block.contains("[1]"))
        assertFalse(block, block.contains("pubmed"))
    }
}
