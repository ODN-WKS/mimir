package io.github.odnwks.mimir.online

import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.Store
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
import java.net.InetAddress

/** Reading a page, only after the owner's tap: plain words, no scripts, never in a Whisper. */
@RunWith(RobolectricTestRunner::class)
class PageReadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val link = "https://news.example.com/bridge"

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
        Online.dataSaver = { false }
        LookupLog.clear()
    }

    @After fun tearDown() {
        Online.set(Online.Settings())
        Online.dataSaver = { false }
    }

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

    @Test fun aSentLinkIsReadOnlyAfterTheTapAndAsPlainWords() {
        val runner = PromptRunner()
        val e = engine(runner)
        e.send("is this true? $link")
        e.waitDone()
        // Never opened unasked.
        assertTrue(web.asked.isEmpty())
        e.readPage(link)
        e.waitDone()
        assertEquals(listOf(link), web.asked)
        val p = runner.prompts.last()
        assertTrue(p.contains("After two years of repairs"))
        assertTrue(p.contains("Page title: Bridge reopens"))
        assertFalse(p.contains("steal()"))
        assertFalse(p.contains("Home News Sport"))
        // The page can't pose as a message.
        assertFalse(p.substringAfter("After two years").substringBefore("[End of fetched information]").let { it.contains("<|im_start|>") || it.contains("<|im_end|>") })
        assertEquals(2, e.state.value.chat.turns.size)
        assertEquals(listOf("news.example.com"), e.state.value.chat.turns.last().sources.map { it.site })
    }

    @Test fun plainHttpTheHomeNetworkAWhisperAndDataSaverAreNeverRead() {
        val runner = PromptRunner()
        val e = engine(runner)
        e.send("look at http://news.example.com/bridge")
        e.waitDone()
        e.readPage("http://news.example.com/bridge")
        e.waitDone()
        assertTrue(web.asked.isEmpty())
        // The home network: refused after the name lookup, and Mímir is told.
        Net.resolve = { listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))) }
        e.readPage("https://router.example.com/admin")
        e.waitDone()
        assertTrue(web.asked.isEmpty())
        assertTrue(runner.prompts.last().contains("couldn't read"))
        web.install()
        Online.dataSaver = { true }
        e.readPage(link)
        e.waitDone()
        assertTrue(web.asked.isEmpty())
        assertTrue(runner.prompts.last().contains("Data Saver"))
        Online.dataSaver = { false }
        e.startWhisper()
        e.send("read $link")
        e.waitDone()
        e.readPage(link)
        e.waitDone()
        assertTrue(web.asked.isEmpty())
    }
}
