package io.github.odnwks.mimir.online

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reading the pages a search found (P21 stage 1): all at once, inside the lookup's time limit; a
 * slow, refused or huge page never costs the answer; nothing is read in a Whisper or with Data Saver.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderTest {
    private val web = FakeWeb()
    private val hang = CountDownLatch(1)
    private val calls = AtomicInteger(0)

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
        Net.torTransport = { throw Net.Refused("the hidden route is off just now") }
    }

    private fun html(words: String) = "<html><head><title>T</title></head><body><main><p>$words</p></main></body></html>"

    private fun result(host: String) = Search.Result("Page on $host", "https://$host/page", "The snippet from $host.")

    /** A stand-in for the real reader of a reply: a long page is cut when asked, and too big otherwise. */
    private fun sized(r: Net.Request, body: ByteArray): Net.Reply {
        val kept = if (body.size <= r.maxBytes) body
            else if (r.cut) body.copyOf(r.maxBytes)
            else throw java.io.IOException("the page is too big")
        return Net.Reply(200, null, "text/html; charset=utf-8", kept)
    }

    @Test fun aSlowARefusedAHugeAndAGoodPageAreReadTogetherWithinTheBudget() {
        val huge = html("The huge page starts with the facts. " + "filler words here ".repeat(60_000)).toByteArray()
        assertTrue(huge.size > 1_000_000)
        val asked = java.util.concurrent.CopyOnWriteArrayList<Net.Request>()
        Net.transport = { r ->
            asked += r
            when (r.url.host) {
                "slow.example" -> { hang.await(); throw java.io.IOException("never answered") }
                "big.example" -> sized(r, huge)
                "refused.example" -> throw Net.Refused("the site said no")
                "good.example" -> sized(r, html("The kettle boils water in ninety seconds.").toByteArray())
                else -> Net.Reply(404, null, "text/plain", ByteArray(0))
            }
        }
        val picked = listOf(result("slow.example"), result("big.example"), result("refused.example"), result("good.example"))
        val t0 = System.currentTimeMillis()
        val reads = Reader.read(picked, ChatMode.Normal)
        val took = System.currentTimeMillis() - t0
        assertTrue("took $took ms", took < Net.BUDGET_MS)
        // Every page comes back, in the same order.
        assertEquals(picked, reads.map { it.result })
        assertNull("the slow page", reads[0].text)
        assertNotNull("the huge page is cut, not failed", reads[1].text)
        assertTrue(reads[1].text!!.contains("The huge page starts with the facts."))
        assertNull("the refused page", reads[2].text)
        assertTrue(reads[3].text!!.contains("The kettle boils water in ninety seconds."))
        // Each page was asked as a cut read of at most 400 KB, as a page (never as data).
        for (r in asked) {
            assertTrue(r.cut)
            assertEquals(Reader.PAGE_BYTES, r.maxBytes)
            assertEquals(400_000, r.maxBytes)
            assertTrue(r.timeoutMs <= Reader.PAGE_MS)
            assertTrue(r.headers["Accept"].orEmpty().contains("text/html"))
            assertTrue(!r.headers["Accept"].orEmpty().contains("json"))
        }
        // One entry in the Privacy check for the reading, naming the sites read.
        val entry = LookupLog.entries.value.single { it.site == "read pages" }
        assertTrue(entry.words, entry.words.contains("big.example") && entry.words.contains("good.example"))
    }

    @Test fun aPageThatFailsGivesNoTextAndNeverFailsTheOthers() {
        Net.transport = { r ->
            when (r.url.host) {
                "down.example" -> Net.Reply(503, null, "text/html", ByteArray(0))
                "pdf.example" -> Net.Reply(200, null, "application/pdf", "%PDF".toByteArray())
                else -> sized(r, html("Plain facts about tea.").toByteArray())
            }
        }
        val reads = Reader.read(listOf(result("down.example"), result("pdf.example"), result("tea.example")), ChatMode.Normal)
        assertNull(reads[0].text)
        assertNull(reads[1].text)
        assertTrue(reads[2].text!!.contains("Plain facts about tea."))
    }

    @Test fun aWhisperAndDataSaverReadNothing() {
        Net.transport = { calls.incrementAndGet(); Net.Reply(200, null, "text/html", html("x").toByteArray()) }
        Net.torTransport = { calls.incrementAndGet(); Net.Reply(200, null, "text/html", html("x").toByteArray()) }
        val picked = listOf(result("a.example"), result("b.example"))
        for (mode in listOf(ChatMode.WhisperWeb, ChatMode.Whisper)) {
            val reads = Reader.read(picked, mode)
            assertEquals(picked, reads.map { it.result })
            assertTrue(reads.all { it.text == null })
        }
        Online.dataSaver = { true }
        val reads = Reader.read(picked, ChatMode.Normal)
        assertTrue(reads.all { it.text == null })
        assertEquals(0, calls.get())
        assertTrue(LookupLog.entries.value.isEmpty())
    }

    // ---- Net's cut-off mode ----

    @Test fun cutKeepsTheFirstBytesAndWithoutItALongPageIsStillTooBig() {
        val body = ByteArray(1_000_000) { (it % 251).toByte() }
        val until = System.currentTimeMillis() + 10_000
        val cut = Net.readBody(java.io.ByteArrayInputStream(body), 400_000, cut = true, until = until)
        assertEquals(400_000, cut.size)
        assertArrayEquals(body.copyOf(400_000), cut)
        val e = runCatching { Net.readBody(java.io.ByteArrayInputStream(body), 400_000, cut = false, until = until) }.exceptionOrNull()
        assertTrue(e.toString(), e is java.io.IOException && e.message == "the page is too big")
        // A short page is read whole either way.
        assertEquals(1_000, Net.readBody(java.io.ByteArrayInputStream(body.copyOf(1_000)), 400_000, cut = true, until = until).size)
        assertEquals(1_000, Net.readBody(java.io.ByteArrayInputStream(body.copyOf(1_000)), 400_000, cut = false, until = until).size)
    }

    @Test fun cutIsOffUnlessAskedAndReachesTheRequest() {
        assertEquals(false, Net.Request(java.net.URL("https://a.example/"), emptyMap(), 1_000, 10).cut)
        val seen = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        Net.transport = { r -> seen += r.cut; Net.Reply(200, null, "text/html", html("x").toByteArray()) }
        Net.get("https://a.example/one", ChatMode.Normal, "x")
        Net.get("https://a.example/two", ChatMode.Normal, "x", cut = true)
        assertEquals(listOf(false, true), seen.toList())
    }
}
