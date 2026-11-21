package io.github.odnwks.mimir.online

import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
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
import java.util.concurrent.CountDownLatch

/**
 * A Whisper with internet (owner, 2026-11-19): lookups go through Tor only, never directly and
 * never by the phone's own name lookup; no Tor, no lookup; Tor stops and its state is wiped when
 * the Whisper ends, keeping only the relay list; a slow Tor says so and can be cancelled.
 */
@RunWith(RobolectricTestRunner::class)
class TorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private lateinit var direct: (Net.Request) -> Net.Reply

    /** A stand-in for the phone core's Tor. */
    inner class FakeTor : TorLink.Core {
        val asked = mutableListOf<String>()
        var connectAnswer = ""
        var hold: CountDownLatch? = null
        var started = 0
        var stopped = 0
        override fun new(state: String, cache: String): Long { started++; File(state, "guards").writeText("chosen relays"); return 7L }
        override fun connect(h: Long, timeoutMs: Long): String { hold?.await(); return connectAnswer }
        override fun progress(h: Long) = 40L
        override fun get(h: Long, url: String, headers: String, timeoutMs: Long, maxBytes: Long): String {
            asked += url
            val r = direct(Net.Request(URL(url), emptyMap(), 1000, 1_000_000))
            return JSONObject().put("status", r.status).put("type", r.type).put("body", String(r.body)).toString()
        }
        override fun cancel(h: Long) { hold?.countDown() }
        override fun stop(h: Long) { stopped++; hold?.countDown() }
    }

    private val tor = FakeTor()
    private lateinit var files: File
    private lateinit var keep: File

    @Before fun setUp() {
        web.install()
        direct = Net.transport
        // Nothing may go directly, or be looked up by the phone's own name lookup.
        Net.transport = { throw AssertionError("a direct connection in a Whisper with internet") }
        Net.resolve = { throw AssertionError("the phone looked up a site's name") }
        Online.set(Online.Settings(searchAccepted = true, place = Place("Leeds", 53.8, -1.55)))
        LookupLog.clear()
        Search.forget()
        files = tmp.newFolder(); keep = tmp.newFolder()
        TorLink.core = tor
        TorLink.setUp(files, keep)
        Engine.TOR_SLOW_MS = 20_000L
    }

    @After fun tearDown() {
        TorLink.stop()
        TorLink.core = TorLink.Native
        TorLink.stateDir = null
        TorLink.cacheDir = null
        Online.set(Online.Settings())
        Engine.TOR_SLOW_MS = 20_000L
    }

    private fun engine(runner: PromptRunner): Engine {
        Catalog.reset()
        val dir = tmp.newFolder()
        File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(1_800_000_000_000L) }
        return Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), runner, supported = true, now = { 1_800_000_000_000L })
    }

    private fun Engine.waitDone(ms: Long = 5_000) {
        val until = System.currentTimeMillis() + ms
        Thread.sleep(30)
        while (state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @Test fun lookupsGoThroughTorOnlyAndNothingIsKept() {
        val runner = PromptRunner()
        val e = engine(runner)
        e.startWhisper(web = true)
        assertEquals(1, tor.started)
        e.send("search for cheap flights to Oslo")
        e.waitDone()
        assertEquals(1, tor.asked.count { "duckduckgo" in it })
        assertTrue(runner.prompts.last().contains("91,200"))
        // Shown while it lasts, but the Privacy check keeps nothing of it.
        assertEquals(LookupLog.Route.Tor, LookupLog.entries.value.first().route)
        // The weather: the saved place isn't used (it would say where the owner lives); it asks.
        e.send("what's the weather like")
        e.waitDone()
        assertTrue(e.state.value.askPlace)
        e.skipPlace()
        e.waitDone()
        // Ending it stops Tor and wipes its state; only the relay list's folder is kept.
        e.endWhisper()
        assertEquals(1, tor.stopped)
        assertFalse(File(files, "tor/state/guards").exists())
        assertTrue(File(keep, "tor/cache").isDirectory)
    }

    @Test fun noTorNoLookupItNeverGoesDirectly() {
        tor.connectAnswer = "Couldn't reach Tor"
        val runner = PromptRunner()
        val e = engine(runner)
        e.startWhisper(web = true)
        e.send("search for cheap flights to Oslo")
        e.waitDone()
        assertTrue(tor.asked.isEmpty())
        assertTrue(runner.prompts.last().contains("couldn't connect"))
    }

    @Test fun aSlowTorSaysSoAndCancelAnswersWithoutTheInternet() {
        tor.hold = CountDownLatch(1)
        Engine.TOR_SLOW_MS = 300L
        val runner = PromptRunner()
        val e = engine(runner)
        e.startWhisper(web = true)
        e.send("search for cheap flights to Oslo")
        val until = System.currentTimeMillis() + 5_000
        while (!e.state.value.torSlow && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(e.state.value.torSlow)
        assertEquals("Still waiting…", e.state.value.looking)
        e.cancelTor()
        e.waitDone()
        assertFalse(e.state.value.writing)
        assertTrue(tor.asked.isEmpty())
        assertTrue(runner.prompts.last().contains("couldn't connect"))
    }

    @Test fun anOfflineWhisperNeverStartsTor() {
        val e = engine(PromptRunner())
        e.startWhisper()
        e.send("search for cheap flights to Oslo")
        e.waitDone()
        assertEquals(0, tor.started)
        assertTrue(tor.asked.isEmpty())
    }
}
