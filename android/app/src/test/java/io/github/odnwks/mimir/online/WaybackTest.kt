package io.github.odnwks.mimir.online

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P21 stage 3, piece W: a page that is gone (404, 410) or whose site doesn't answer is read from
 * the Wayback Machine and the source says so. A page that blocks the app is never read from the
 * archive, and neither a Whisper, Data Saver nor web search off ever asks it.
 */
@RunWith(RobolectricTestRunner::class)
class WaybackTest {
    private val web = FakeWeb()
    private val asked = CopyOnWriteArrayList<Net.Request>()
    private val viaTor = CopyOnWriteArrayList<URL>()

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
        Online.dataSaver = { false }
        LookupLog.clear()
    }

    @After fun tearDown() {
        Online.set(Online.Settings())
        Online.dataSaver = { false }
        Net.torTransport = { throw Net.Refused("the hidden route is off just now") }
    }

    private fun result(host: String) = Search.Result("Kettle facts", "https://$host/page?id=7", "A kettle snippet from $host.")

    private fun html(words: String) = Net.Reply(200, null, "text/html; charset=utf-8",
        "<html><head><title>T</title></head><body><main><p>$words</p></main></body></html>".toByteArray())

    private fun status(code: Int) = Net.Reply(code, null, "text/html", ByteArray(0))

    private val copy = "The archived kettle boils water in ninety seconds."

    /** How long the archive takes to answer. */
    private var archiveMs = 0L

    /** The page's own site answers with [site]; the archive holds a copy. */
    private fun fake(site: (Net.Request) -> Net.Reply) {
        Net.transport = { r ->
            asked += r
            if (r.url.host == "web.archive.org") { Thread.sleep(archiveMs); html(copy) } else site(r)
        }
    }

    private fun archiveAsks() = asked.filter { it.url.host == "web.archive.org" }

    private fun header(reads: List<PageRead>): String {
        val chosen = Rank.choose("how fast does the kettle boil water", "kettle boil water", reads)
        return Evidence.block("kettle boil water", chosen, 1).lineSequence().first { it.startsWith("[1] ") }
    }

    private fun readFromTheArchive(host: String) {
        val r = result(host)
        val reads = Reader.read(listOf(r), ChatMode.Normal)
        // The result stays the page's own; only its words come from the copy.
        assertEquals(r, reads.single().result)
        assertTrue(reads.single().text.toString(), reads.single().text.orEmpty().contains(copy))
        assertTrue(header(reads), header(reads).endsWith(" (archived copy)"))
        val ask = archiveAsks().single { host in it.url.toString() }
        assertEquals("https://web.archive.org/web/2026/${r.url}", ask.url.toString())
        // The same limits as any page.
        assertTrue(ask.cut)
        assertEquals(Reader.PAGE_BYTES, ask.maxBytes)
        assertTrue(ask.timeoutMs <= Reader.PAGE_MS)
        // The Privacy check says the archive was asked.
        assertTrue(LookupLog.entries.value.any { it.site == "web.archive.org" })
    }

    @Test fun aPageThatIsNotFoundIsReadFromTheArchiveAndSaysSo() {
        fake { status(404) }
        readFromTheArchive("gone404.example")
    }

    @Test fun aPageThatWasRemovedIsReadFromTheArchive() {
        fake { status(410) }
        readFromTheArchive("gone410.example")
    }

    @Test fun aSiteThatRefusesTheConnectionIsReadFromTheArchive() {
        fake { throw java.net.ConnectException("refused") }
        readFromTheArchive("down.example")
    }

    @Test fun aSiteWithNoAddressIsReadFromTheArchive() {
        fake { throw AssertionError("never reached") }
        val resolve = Net.resolve
        Net.resolve = { if (it == "noname.example") throw java.net.UnknownHostException(it) else resolve(it) }
        readFromTheArchive("noname.example")
    }

    @Test fun aSiteThatStaysSilentIsStillReadFromTheArchiveInTime() {
        fake { r -> Thread.sleep(r.timeoutMs.toLong()); throw java.net.SocketTimeoutException("connect timed out") }
        // About what the real archive takes.
        archiveMs = 1_200
        val t0 = System.currentTimeMillis()
        readFromTheArchive("silent.example")
        val took = System.currentTimeMillis() - t0
        assertTrue("took $took ms", took < Net.BUDGET_MS)
    }

    @Test fun aPageReadFromItsOwnSiteIsNotMarked() {
        fake { html("The kettle boils water in two minutes.") }
        val reads = Reader.read(listOf(result("fine.example")), ChatMode.Normal)
        assertTrue(header(reads), "archived" !in header(reads))
        assertTrue(archiveAsks().isEmpty())
    }

    @Test fun aGonePageWithNoCopyKeepsItsSnippet() {
        Net.transport = { r -> asked += r; status(404) }
        val reads = Reader.read(listOf(result("nocopy.example")), ChatMode.Normal)
        assertNull(reads.single().text)
        assertTrue(header(reads), "archived" !in header(reads))
        // One try only.
        assertEquals(1, archiveAsks().size)
    }

    @Test fun aSiteThatBlocksTheAppIsNeverReadFromTheArchive() {
        for (code in listOf(401, 403, 429, 451, 400, 500, 503)) {
            fake { status(code) }
            val reads = Reader.read(listOf(result("blocked$code.example")), ChatMode.Normal)
            assertNull("status $code", reads.single().text)
            assertTrue("status $code: ${archiveAsks().map { it.url }}", archiveAsks().isEmpty())
        }
    }

    @Test fun aSiteThatSendsTheAppInCirclesOrToTheHomeNetworkIsNeverReadFromTheArchive() {
        fake { r -> Net.Reply(302, "https://circle.example/again", "text/html", ByteArray(0)) }
        assertNull(Reader.read(listOf(result("circle.example")), ChatMode.Normal).single().text)
        fake { Net.Reply(302, "https://localhost/", "text/html", ByteArray(0)) }
        assertNull(Reader.read(listOf(result("inward.example")), ChatMode.Normal).single().text)
        assertTrue(archiveAsks().toString(), archiveAsks().isEmpty())
    }

    @Test fun aWhisperDataSaverAndWebSearchOffNeverAskTheArchive() {
        fake { status(404) }
        Net.torTransport = { r -> viaTor += r.url; status(404) }
        val picked = listOf(result("quiet.example"))
        Reader.read(picked, ChatMode.Whisper)
        Reader.read(picked, ChatMode.WhisperWeb)
        Online.dataSaver = { true }
        Reader.read(picked, ChatMode.Normal)
        Online.dataSaver = { false }
        Online.set(Online.Settings(search = false))
        Reader.read(picked, ChatMode.Normal)
        assertTrue(archiveAsks().toString(), archiveAsks().isEmpty())
        assertTrue(viaTor.toString(), viaTor.none { it.host == "web.archive.org" })
    }
}
