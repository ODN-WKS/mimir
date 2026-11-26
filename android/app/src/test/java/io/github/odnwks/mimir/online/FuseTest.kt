package io.github.odnwks.mimir.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fusing several engines' results: one vote per index family, one page per site, https only. */
class FuseTest {
    private fun r(url: String, vararg engines: String) = Search.Result("Title $url", url, "snippet", engines.toList())

    @Test fun linksWithNoHostOrNotHttpsAreDropped() {
        assertNull(Fuse.normalize("http:///go/v1/x"))
        assertNull(Fuse.normalize("https:///go/v1/x"))
        assertNull(Fuse.normalize("http://news.example.com/a"))
        assertNull(Fuse.normalize("ftp://news.example.com/a"))
        val picked = Fuse.pick(listOf(r("http:///go/v1/x", "bing"), r("http://plain.example.com/a", "google"), r("https://safe.example.com/a", "brave")))
        assertEquals(listOf("https://safe.example.com/a"), picked.map { it.url })
    }

    @Test fun trackingPartsAndTheFragmentAreStripped() {
        assertEquals(
            "https://news.example.com/story?id=7",
            Fuse.normalize("https://News.Example.COM/story?utm_source=x&id=7&fbclid=abc&gclid=def&UTM_medium=y#comments"),
        )
        assertEquals("https://news.example.com/", Fuse.normalize("https://news.example.com#top"))
        // The same page under two tracking links is one result, with both engines.
        val picked = Fuse.pick(listOf(r("https://news.example.com/s?utm_source=a", "bing"), r("https://news.example.com/s#x", "google")))
        assertEquals(1, picked.size)
        assertEquals(listOf("bing", "google"), picked.single().engines)
    }

    @Test fun lineagesGroupEnginesFedByOneIndex() {
        assertEquals("google", Fuse.lineageOf("Google CSE"))
        assertEquals("google", Fuse.lineageOf("startpage"))
        for (e in listOf("bing", "DuckDuckGo", "yahoo", "Qwant", "ecosia")) assertEquals("bing", Fuse.lineageOf(e))
        assertEquals("brave", Fuse.lineageOf("Brave"))
        assertEquals("wikipedia", Fuse.lineageOf("Wikipedia"))
    }

    @Test fun twoBingFedEnginesOnOneResultCountOnce() {
        // First in the list with two Bing-fed engines, against two independent families one place lower.
        val picked = Fuse.pick(listOf(r("https://one.example.com/a", "bing", "duckduckgo"), r("https://two.example.com/a", "google", "brave")))
        assertEquals(listOf("https://two.example.com/a", "https://one.example.com/a"), picked.map { it.url })
    }

    @Test fun googleAndBraveOutrankBingOnly() {
        val picked = Fuse.pick(listOf(r("https://bing.example.com/a", "bing"), r("https://both.example.com/a", "google", "brave")))
        assertEquals("https://both.example.com/a", picked.first().url)
        // A result with no engines counts as one family (Bing): ahead of one other family below it, behind two.
        val one = Fuse.pick(listOf(r("https://x.example.com/a"), r("https://y.example.com/a", "brave")))
        assertEquals(listOf("https://x.example.com/a", "https://y.example.com/a"), one.map { it.url })
        val two = Fuse.pick(listOf(r("https://x.example.com/a"), r("https://y.example.com/a", "brave", "duckduckgo")))
        assertEquals(listOf("https://y.example.com/a", "https://x.example.com/a"), two.map { it.url })
    }

    @Test fun onePagePerSiteAndMaxRespected() {
        val many = listOf(
            r("https://www.site.example/a", "google"), r("https://site.example/b", "google"),
            r("https://b.example/1", "google"), r("https://c.example/1", "google"), r("https://d.example/1", "google"),
            r("https://e.example/1", "google"),
        )
        val picked = Fuse.pick(many)
        assertEquals(4, picked.size)
        assertEquals(listOf("https://www.site.example/a", "https://b.example/1", "https://c.example/1", "https://d.example/1"), picked.map { it.url })
        assertEquals(2, Fuse.pick(many, max = 2).size)
    }
}
