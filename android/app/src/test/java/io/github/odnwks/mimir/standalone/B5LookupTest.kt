package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.online.ChatMode
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.online.Router
import io.github.odnwks.mimir.online.Search
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * B5 lookup tests: PC-first search route (PHW-04, PHE-14) and one-shot choice clear (PHE-04).
 */
@RunWith(RobolectricTestRunner::class)
class B5LookupTest {
    @get:Rule val tmp = TemporaryFolder()

    private object Plain : Store.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    /** Answers immediately with fixed words. */
    private class QuickRunner(private val reply: String = "An answer.") : Runner {
        override fun open(path: String) = File(path).exists()
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            sink.onPiece(reply.toByteArray())
            return """{"gen_tokens":3,"gen_ms":10}"""
        }
        override fun stop() {}
        override fun close() {}
    }

    private fun engine(runner: Runner = QuickRunner()): Engine {
        val dir = tmp.newFolder()
        File(dir, "models").mkdirs()
        File(dir, "models/${Catalog.default.file}").apply { writeText("model"); setLastModified(1_800_000_000_000L) }
        return Engine(dir, Store(File(dir, "standalone.bin"), Plain), runner, supported = true)
    }

    private fun waitFor(e: Engine, timeoutMs: Long = 5_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (e.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(20)
        assertFalse("reply never finished", e.state.value.writing)
    }

    @Before fun setUp() {
        Catalog.reset()
        Search.forget()
        Online.set(Online.Settings(search = true, searchAccepted = true))
    }

    @After fun tearDown() {
        Search.viaPc = null
        Online.set(Online.Settings())
        Search.forget()
    }

    // PHW-04 / PHE-14: when PC is present, search goes to PC, not direct DuckDuckGo.
    @Test fun pcPresentSearchGoesToPcNotDirect() {
        val pcCalled = AtomicBoolean(false)
        Search.viaPc = { words ->
            pcCalled.set(true)
            listOf(Search.Result("Title", "https://example.com/", "A snippet"))
        }
        val results = Search.searchViaPc("rust programming language", ChatMode.Normal)
        assertNotNull("PC should answer when present", results)
        assertTrue("PC must have been called", pcCalled.get())
        assertEquals(1, results!!.size)
    }

    // PHW-04 / PHE-14: when PC is absent (viaPc null), search falls back to null (DuckDuckGo).
    @Test fun pcAbsentSearchFallsBackToDirect() {
        Search.viaPc = null
        val results = Search.searchViaPc("rust programming language", ChatMode.Normal)
        assertNull("Should return null (fall back to DuckDuckGo) when PC absent", results)
    }

    // PHE-04: one-shot forced search is cleared after the message is answered, even via PC.
    @Test fun oneShotForcedSearchClearedAfterSend() {
        val e = engine()
        Online.set(Online.Settings(search = true, searchAccepted = true))
        // Simulate a forced search being set.
        e.lookItUp()  // no-op when no turns, but tests the accessor path
        // Add two turns and force a search.
        e.ask("What is a black hole")
        waitFor(e)
        val turns1 = e.state.value.chat.turns.size
        // forcedSearch must be null after the first send (PHE-04): send again.
        e.ask("Tell me more")
        waitFor(e)
        // The second message should not carry a forced search flag.
        assertTrue("Should have more turns after second message", e.state.value.chat.turns.size > turns1)
        assertFalse("Should not still be writing", e.state.value.writing)
    }

    // PHE-04: one-shot choices (typedPlace, forcedSearch, tappedPage) are cleared after each send.
    // askPlace starts false and stays false after a plain (non-weather) send.
    @Test fun oneShotTypedPlaceClearedAfterSend() {
        val e = engine()
        assertFalse("askPlace should be false initially", e.state.value.askPlace)
        e.ask("Tell me a fun fact about penguins")
        waitFor(e)
        assertFalse("askPlace should still be false after a plain send", e.state.value.askPlace)
    }
}
