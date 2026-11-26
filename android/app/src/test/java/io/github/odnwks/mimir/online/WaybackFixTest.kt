package io.github.odnwks.mimir.online

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P21 stage 3, piece W2: an archived copy is kept only when it is really that page, every archive
 * try is listed in the Privacy check, and a site that is alive (slow, resetting, a broken secure
 * link) is never read from the archive.
 */
@RunWith(RobolectricTestRunner::class)
class WaybackFixTest {
    private val web = FakeWeb()
    private val asked = CopyOnWriteArrayList<Net.Request>()

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
        Online.dataSaver = { false }
        LookupLog.clear()
    }

    @After fun tearDown() {
        Online.set(Online.Settings())
        Online.dataSaver = { false }
        Net.phoneOnline = { true }
    }

    private fun html(words: String) = Net.Reply(200, null, "text/html; charset=utf-8",
        "<html><head><title>T</title></head><body><main><p>$words</p></main></body></html>".toByteArray())

    private fun status(code: Int) = Net.Reply(code, null, "text/html", ByteArray(0))

    private fun fake(archive: () -> Net.Reply = { html("The kettle boils water quickly.") }, site: (Net.Request) -> Net.Reply) {
        Net.transport = { r ->
            asked += r
            if (r.url.host == "web.archive.org") archive() else site(r)
        }
    }

    private fun archiveAsks() = asked.filter { it.url.host == "web.archive.org" }
    private fun siteAsks() = asked.filter { it.url.host != "web.archive.org" }
    private fun logged() = LookupLog.entries.value.filter { it.site == "web.archive.org" }

    private val planner = Search.Result("Garden planner guide", "https://plots.example/guide",
        "Plan vegetable beds and sowing dates through the season.")

    private fun kettle(host: String) = Search.Result("Kettle facts", "https://$host/page", "How a kettle boils water.")

    private fun read(r: Search.Result) = Reader.read(listOf(r), ChatMode.Normal).single()

    private fun refused(r: Search.Result, copy: String) {
        fake(archive = { html(copy) }) { status(404) }
        val got = read(r)
        assertEquals(1, archiveAsks().size)
        assertNull(got.text.toString(), got.text)
        assertTrue(!got.archived)
        val block = Evidence.block("vegetable beds", Rank.choose("when to sow vegetable beds", "vegetable beds", listOf(got)), 0)
        assertTrue(block, "archived" !in block)
        // Still listed in the Privacy check, once.
        assertEquals(1, logged().size)
    }

    private fun kept(r: Search.Result, copy: String) {
        fake(archive = { html(copy) }) { status(404) }
        val got = read(r)
        assertTrue(got.text.toString(), got.text.orEmpty().isNotEmpty())
        assertTrue(got.archived)
        assertEquals(1, logged().size)
    }

    // Bug 1: only a copy that is really that page.

    @Test fun aParkedDomainPageIsNotPassedOffAsThePage() {
        refused(planner, "This domain is for sale. Hosting plans and pricing for your new website. Make an offer today.")
    }

    @Test fun theArchivesOwnFrontPageIsNotPassedOffAsThePage() {
        refused(planner, "Internet Archive is a non-profit library. Search the Wayback Machine. Garden planner guide, vegetable beds, sowing dates, season.")
    }

    @Test fun aPageOfTheArchiveItselfMayNameTheArchive() {
        kept(Search.Result("Wayback Machine help", "https://help.archive.org/saving", "Saving pages and browsing old captures."),
            "The Wayback Machine keeps captures of pages. Saving a page makes a new capture.")
    }

    @Test fun theSitesOwnNameDoesNotCountAsASharedWord() {
        val r = Search.Result("Brightlamp garden planner", "https://brightlamp.example/guide", "Brightlamp example: vegetable beds and sowing dates.")
        refused(r, "Brightlamp example hosting. This default page shows a new garden.")
    }

    @Test fun aShortTitleAndSnippetNeedOnlyOneSharedWord() {
        kept(Search.Result("Kettle", "https://short.example/page", "Descaling."), "A kettle is easy to clean with vinegar.")
    }

    @Test fun twoSharedWordsKeepACopy() {
        kept(planner, "Sow carrots in spring. Raised beds suit vegetable growing.")
    }

    /** A real copy as the archive sends it: with its own banner, naming itself, put into the page. */
    @Test fun theArchivesBannerInACopyIsNotPartOfThePage() {
        fake(archive = {
            Net.Reply(200, null, "text/html; charset=utf-8", ("<html><head><title>T</title></head><body>" +
                "<!-- BEGIN WAYBACK TOOLBAR INSERT --><div id=\"wm-ipp-print\">The Wayback Machine - https://web.archive.org/web/2026/x</div>" +
                "<!-- END WAYBACK TOOLBAR INSERT --><p>Sow carrots in spring. Raised beds suit vegetable growing.</p></body></html>").toByteArray())
        }) { status(404) }
        val got = read(planner)
        assertTrue(got.archived)
        assertTrue(got.text.toString(), got.text.orEmpty().contains("Sow carrots") && "Wayback" !in got.text.orEmpty())
    }

    // Bug 2: every try is listed.

    @Test fun anArchiveTryThatFindsNothingIsListedOnce() {
        fake(archive = { status(404) }) { status(404) }
        assertNull(read(kettle("nothing.example")).text)
        assertEquals(1, archiveAsks().size)
        assertEquals(1, logged().size)
    }

    @Test fun anArchiveTryThatCannotConnectIsListedOnce() {
        fake(archive = { throw java.net.ConnectException("refused") }) { status(410) }
        assertNull(read(kettle("nocopy.example")).text)
        assertTrue(archiveAsks().isNotEmpty())
        assertEquals(1, logged().size)
    }

    @Test fun anArchiveTryThatSendsSomethingUnreadableIsListedOnce() {
        fake(archive = { Net.Reply(200, null, "application/pdf", ByteArray(3)) }) { status(404) }
        assertNull(read(kettle("pdf.example")).text)
        assertEquals(1, logged().size)
    }

    // Bug 3: a site that is alive is read from the site, as before.

    private fun neverFromTheArchive(tries: Int, failure: (Net.Request) -> Nothing) {
        asked.clear()
        fake { r -> failure(r) }
        assertNull(read(kettle("alive.example")).text)
        assertEquals("tries", tries, siteAsks().size)
        assertTrue(archiveAsks().toString(), archiveAsks().isEmpty())
    }

    private fun slow(message: String): (Net.Request) -> Nothing = { r ->
        Thread.sleep(r.timeoutMs.toLong())
        throw java.net.SocketTimeoutException(message)
    }

    @Test fun aSiteThatIsSlowToSendGetsBothTriesAndNoArchive() {
        neverFromTheArchive(2, slow("Read timed out"))
    }

    @Test fun androidsOwnReadTimeoutIsNotADeadSite() {
        neverFromTheArchive(2, slow("timeout"))
    }

    @Test fun aSlowSecureHandshakeIsNotADeadSite() {
        neverFromTheArchive(2, slow("SSL handshake timed out"))
    }

    @Test fun aSlowSiteThatAnswersOnItsSecondTryIsReadFromTheSite() {
        var n = 0
        fake { r -> if (n++ == 0) slow("Read timed out")(r) else html("The kettle boils water in two minutes.") }
        val got = read(kettle("second.example"))
        assertTrue(got.text.orEmpty().contains("two minutes"))
        assertTrue(!got.archived)
        assertTrue(archiveAsks().isEmpty())
    }

    @Test fun aResetConnectionIsNeverReadFromTheArchive() {
        neverFromTheArchive(2) { throw java.net.SocketException("Connection reset") }
        neverFromTheArchive(2) { throw java.net.ConnectException("failed to connect: connect failed: ECONNRESET (Connection reset by peer)") }
    }

    @Test fun aFailedSecureHandshakeIsNeverReadFromTheArchive() {
        neverFromTheArchive(2) { throw javax.net.ssl.SSLHandshakeException("handshake failed") }
    }

    @Test fun aSiteThatWasSlowOnceAndThenRefusesIsNeverReadFromTheArchive() {
        var n = 0
        neverFromTheArchive(2) { r -> if (n++ == 0) slow("Read timed out")(r) else throw java.net.ConnectException("refused") }
    }

    @Test fun aPhoneWithNoSignalAsksNoArchive() {
        Net.phoneOnline = { false }
        fake { throw java.net.UnknownHostException("offline.example") }
        assertNull(read(kettle("offline.example")).text)
        assertTrue(archiveAsks().isEmpty())
        assertTrue(logged().isEmpty())
    }

    @Test fun aConnectTimeoutAsAndroidWordsItIsReadFromTheArchive() {
        fake { r -> slow("failed to connect to dead.example/192.0.2.1 (port 443) after ${r.timeoutMs}ms")(r) }
        val got = read(kettle("dead.example"))
        assertTrue(got.archived)
        assertEquals(1, logged().size)
    }

    // Item 4: the settings are looked at again before the archive is asked.

    @Test fun dataSaverSwitchedOnWhileTheSiteIsReadAsksNoArchive() {
        fake { Online.dataSaver = { true }; status(404) }
        assertNull(read(kettle("saver.example")).text)
        assertTrue(archiveAsks().isEmpty())
    }

    // Item 5: what the source header says.

    private fun passage(archived: Boolean, quote: String) = Chosen(1, "a.example", "https://a.example/p", "T", null, quote, archived)

    private fun header(vararg c: Chosen) = Evidence.block("words", c.toList(), 1).lineSequence().first { it.startsWith("[1] ") }

    @Test fun aSourceWithALiveAndAnArchivedPageSaysPartly() {
        val h = header(passage(false, "Live words."), passage(true, "Old words."))
        assertTrue(h, h.endsWith(" (partly an archived copy)"))
        assertTrue(header(passage(true, "Old."), passage(true, "Older.")).endsWith(" (archived copy)"))
        assertTrue("archived" !in header(passage(false, "Live.")))
    }
}
