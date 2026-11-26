package io.github.odnwks.mimir.online

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * P21 stage 3, piece B: which open databases a question reads beside the web search, and how each
 * one's recorded reply becomes results (title, https address on the database's own site, snippet,
 * its own engine name).
 */
@RunWith(RobolectricTestRunner::class)
class LanesTest {
    private val web = FakeWeb()
    private val hang = CountDownLatch(1)

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
        Online.dataSaver = { false }
        LookupLog.clear()
    }

    @After fun tearDown() {
        hang.countDown()
        Online.set(Online.Settings())
        Online.dataSaver = { false }
    }

    private fun fixture(name: String) = javaClass.getResource("/lanes/$name")!!.readText()

    private fun lanes(q: String) = Lanes.lanesFor(q, Router.words(q) ?: q)

    // ---- Detection ----

    @Test fun eachLaneIsFoundAndGeneralQuestionsHaveNone() {
        for (q in listOf(
            "what does the research say about vitamin d and depression",
            "is there clinical trial evidence that fish oil helps",
            "does coffee cause cancer",
            "any studies on intermittent fasting",
            "is red meat linked to heart disease",
            "find papers on battery chemistry",
        )) assertTrue(q, Lane.Science in lanes(q))
        for (q in listOf(
            "kotlin null pointer exception when inlined",
            "TypeError: undefined is not a function",
            "how do I reverse a list in python",
            "how to read a file line by line in c++",
            "gradle could not compile the project",
            "python traceback KeyError in dict",
        )) assertTrue(q, Lane.Code in lanes(q))
        for (q in listOf(
            "what are people saying about the new framework release",
            "opinions on mechanical keyboards",
            "reviews of the latest e-reader",
            "reddit thoughts about home brewing",
        )) assertTrue(q, Lane.PeopleSay in lanes(q))
        for (q in listOf(
            "who wrote the left hand of darkness",
            "a good novel about the sea",
            "author of the book dune",
            "ISBN 9780441478125",
        )) assertTrue(q, Lane.Books in lanes(q))
        for (q in listOf(
            "where is Paris",
            "what time is it in Tokyo",
            "weather in Oslo tomorrow",
            "price of bitcoin",
            "who is the prime minister of Canada",
            "book a table for two tonight",
            "buy toilet paper online",
            "margin of error in a poll",
        )) assertEquals(q, emptySet<Lane>(), lanes(q))
    }

    @Test fun aQuestionCanHaveMoreThanOneLane() {
        assertEquals(setOf(Lane.Science, Lane.PeopleSay), lanes("what are people saying about the new study on sleep"))
    }

    // ---- Each service's recorded reply ----

    private fun check(results: List<Search.Result>, host: String, engine: String) {
        assertTrue(results.isNotEmpty())
        assertTrue(results.size <= Lanes.MAX)
        for (r in results) {
            assertTrue(r.url, r.url.startsWith("https://$host/"))
            assertEquals(host, r.site)
            assertTrue(r.title.isNotBlank())
            assertEquals(listOf(engine), r.engines)
            assertTrue(r.snippet.length <= Lanes.SNIPPET_CHARS + 1)
        }
    }

    @Test fun openAlexGivesTitlesAndTheAbstractBackInOrder() {
        val r = Lanes.parseOpenAlex(fixture("openalex.json"))
        check(r, "openalex.org", Lanes.OPENALEX)
        assertEquals("https://openalex.org/W1542183800", r[0].url)
        assertTrue(r[0].title, r[0].title.startsWith("PHYSICAL FITNESS ATTRIBUTES, VITAMIN D, DEPRESSION"))
        assertTrue(r[0].snippet, r[0].snippet.startsWith("Published 2013. Physical fitness attributes, vitamin D, depression, and BMD in Omani"))
    }

    @Test fun crossrefGivesJournalArticlesOnItsOwnSite() {
        val r = Lanes.parseCrossref(fixture("crossref.json"))
        check(r, "search.crossref.org", Lanes.CROSSREF)
        assertEquals(2, r.size)
        assertTrue(r[0].title.startsWith("Co-Relation between Vitamin D Levels and Hamilton Depression Scores"))
        assertTrue(r[0].url, r[0].url.contains("10.18535%2Fjmscr%2Fv5i7.200"))
        assertEquals("journal of medical science and clinical research, 2017.", r[0].snippet)
    }

    @Test fun pubMedGivesIdsThenTitlesOnPubMedItself() {
        assertEquals(listOf("43092117", "43069169"), Lanes.parsePubMedIds(fixture("pubmed-esearch.json")))
        val r = Lanes.parsePubMedSummary(fixture("pubmed-esummary.json"))
        check(r, "pubmed.ncbi.nlm.nih.gov", Lanes.PUBMED)
        assertEquals("https://pubmed.ncbi.nlm.nih.gov/43092117/", r[0].url)
        assertEquals("Research Progress on Exercise Combined with Micronutrients Regulating Oxidative Stress in Depression", r[0].title)
        assertEquals("International journal of molecular sciences, 2026 Nov 20.", r[0].snippet)
    }

    @Test fun stackExchangeGivesStackOverflowQuestions() {
        val r = Lanes.parseStackExchange(fixture("stackexchange.json"))
        check(r, "stackoverflow.com", Lanes.STACK_EXCHANGE)
        assertEquals("Kotlin Null pointer exception", r[0].title)
        assertEquals("Score 0, 1 answer, one accepted. Tags: kotlin, firebase-realtime-database, nullpointerexception.", r[0].snippet)
    }

    @Test fun hackerNewsGivesTheDiscussionPage() {
        val r = Lanes.parseHackerNews(fixture("hackernews.json"))
        check(r, "news.ycombinator.com", Lanes.HACKER_NEWS)
        assertEquals("https://news.ycombinator.com/item?id=29485465", r[0].url)
        assertEquals("Rust support in the Linux kernel", r[0].title)
        assertEquals("550 points, 418 comments, 2021-12-08. Links to lkml.org.", r[0].snippet)
    }

    @Test fun openLibraryGivesTheBookAndItsAuthor() {
        val r = Lanes.parseOpenLibrary(fixture("openlibrary.json"))
        check(r, "openlibrary.org", Lanes.OPEN_LIBRARY)
        assertEquals("https://openlibrary.org/works/OL59800W", r[0].url)
        assertEquals("The Left Hand of Darkness", r[0].title)
        assertEquals("By Ursula K. Le Guin. First published 1969.", r[0].snippet)
    }

    @Test fun aBrokenOrEmptyReplyGivesNothing() {
        assertTrue(Lanes.parseOpenAlex("{}").isEmpty())
        assertTrue(Lanes.parseCrossref("{\"message\":{}}").isEmpty())
        assertTrue(Lanes.parsePubMedIds("{\"esearchresult\":{\"idlist\":[\"12; drop\"]}}").isEmpty())
        assertTrue(Lanes.parseStackExchange("{\"items\":[{\"title\":\"x\",\"link\":\"http://stackoverflow.com/q/1\"}]}").isEmpty())
        assertTrue(Lanes.parseHackerNews("{\"hits\":[{\"title\":\"x\",\"objectID\":\"../evil\"}]}").isEmpty())
        assertTrue(Lanes.parseOpenLibrary("{\"docs\":[{\"title\":\"x\",\"key\":\"/authors/OL1A\"}]}").isEmpty())
    }

    // ---- Asking the services ----

    @Test fun eachServiceIsAskedWithTheSearchWordsOnlyAndAtMostFive() {
        val asked = CopyOnWriteArrayList<Net.Request>()
        Net.transport = { r ->
            asked += r
            val body = when (r.url.host) {
                "api.openalex.org" -> fixture("openalex.json")
                "api.crossref.org" -> fixture("crossref.json")
                "eutils.ncbi.nlm.nih.gov" -> if ("esearch" in r.url.path) fixture("pubmed-esearch.json") else fixture("pubmed-esummary.json")
                "api.stackexchange.com" -> fixture("stackexchange.json")
                "hn.algolia.com" -> fixture("hackernews.json")
                "openlibrary.org" -> fixture("openlibrary.json")
                else -> throw AssertionError("asked ${r.url.host}")
            }
            Net.Reply(200, null, "application/json; charset=utf-8", body.toByteArray())
        }
        val words = "vitamin d depression"
        val got = Lanes.search(Lane.entries.toSet(), words, ChatMode.Normal)
        assertEquals(6, got.size)
        assertTrue(got.all { it.isNotEmpty() && it.size <= Lanes.MAX })
        assertEquals(Lanes.HOSTS, asked.map { it.url.host }.toSet())
        for (r in asked) {
            assertTrue(r.url.toString(), r.url.protocol == "https")
            assertTrue(r.timeoutMs <= Lanes.LANE_MS)
            assertEquals(Net.AGENT, r.headers["User-Agent"])
            assertTrue(r.headers.keys.none { it.equals("Cookie", true) || it.equals("Referer", true) })
            val params = r.url.query.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            // The only free text is the search words; the rest are fixed settings (and PubMed's own ids).
            val free = params.filterKeys { it in setOf("search", "query", "term", "q") }.values.toSet()
            if ("esummary" in r.url.path) assertEquals("43092117,43069169", params["id"]) else assertEquals(r.url.toString(), setOf(words), free)
            for ((k, v) in params) if (k !in setOf("search", "query", "term", "q", "id", "select", "filter", "fields")) assertTrue("$k=$v", v.length <= 16)
        }
    }

    @Test fun aServiceThatHangsGivesNothingAndNeverHoldsTheLookup() {
        Net.transport = { r ->
            if (r.url.host == "eutils.ncbi.nlm.nih.gov") { hang.await(); throw java.io.IOException("never") }
            Net.Reply(200, null, "application/json", fixture("openalex.json").toByteArray())
        }
        val t0 = System.currentTimeMillis()
        val got = Net.within(Net.BUDGET_MS) { Lanes.search(setOf(Lane.Science), "vitamin d depression", ChatMode.Normal) }
        val took = System.currentTimeMillis() - t0
        assertTrue("took $took ms", took < Lanes.LANE_MS + 500)
        assertTrue("took $took ms", took < Net.BUDGET_MS)
        // PubMed (hanging) gives nothing; OpenAlex still answers.
        assertTrue(got[0].isEmpty())
        assertTrue(got[1].isNotEmpty())
    }

    @Test fun nothingIsAskedInAWhisperWithLookupsOffOrWithDataSaver() {
        val asked = CopyOnWriteArrayList<String>()
        Net.transport = { r -> asked += r.url.host; Net.Reply(200, null, "application/json", "{}".toByteArray()) }
        Net.torTransport = { r -> asked += r.url.host; Net.Reply(200, null, "application/json", "{}".toByteArray()) }
        try {
            for (mode in listOf(ChatMode.Whisper, ChatMode.WhisperWeb)) assertTrue(Lanes.search(Lane.entries.toSet(), "x y", mode).isEmpty())
            Online.dataSaver = { true }
            assertTrue(Lanes.search(Lane.entries.toSet(), "x y", ChatMode.Normal).isEmpty())
            Online.dataSaver = { false }
            Online.set(Online.Settings(lookups = false))
            assertTrue(Lanes.search(Lane.entries.toSet(), "x y", ChatMode.Normal).isEmpty())
            assertTrue(asked.toString(), asked.isEmpty())
        } finally {
            Net.torTransport = { throw Net.Refused("the hidden route is off just now") }
        }
    }

    // ---- Joining ----

    @Test fun lanesTakeTurnsWithTheWebSoEachListsBestRanksNearTheTop() {
        fun r(s: String, e: String) = Search.Result(s, "https://$s.example/", "", listOf(e))
        val web = listOf(r("w1", "brave"), r("w2", "brave"), r("w3", "brave"))
        val pm = listOf(r("p1", Lanes.PUBMED))
        val oa = listOf(r("o1", Lanes.OPENALEX), r("o2", Lanes.OPENALEX))
        assertEquals(listOf("w1", "p1", "o1", "w2", "o2", "w3"), Lanes.join(web, listOf(pm, emptyList(), oa)).map { it.title })
        assertEquals(web, Lanes.join(web, emptyList()))
        assertTrue(Lanes.found(pm[0]))
        assertTrue(!Lanes.found(web[0]))
        // Fuse counts each database as its own index family.
        assertEquals(setOf("pubmed", "openalex", "crossref", "stack exchange", "hacker news", "open library"), Lanes.ENGINES.map(Fuse::lineageOf).toSet())
    }
}
