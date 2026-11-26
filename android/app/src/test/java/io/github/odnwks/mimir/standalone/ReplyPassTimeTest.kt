package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.LookupLog
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.online.Search
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P21 stage 3, piece B: a reply's time limit counts from the start of each pass, so a chat whose
 * first pass (the model asking for a lookup) took most of the limit still gets its whole answer.
 */
@RunWith(RobolectricTestRunner::class)
class ReplyPassTimeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val reply0 = Engine.REPLY_MAX_MS

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings(searchAccepted = true))
        Online.dataSaver = { false }
        LookupLog.clear()
        Search.forget()
        Search.viaPc = { listOf(Search.Result("AI models compared", "https://models.example/compare", "A list of open AI models and their rules.", listOf("brave"))) }
        Catalog.reset()
    }

    @After fun tearDown() {
        Engine.REPLY_MAX_MS = reply0
        Search.viaPc = null
        Online.set(Online.Settings())
        Search.forget()
        Catalog.reset()
    }

    @Test fun aSecondPassGetsItsOwnTimeAfterASlowFirstPass() {
        Engine.REPLY_MAX_MS = 900
        val pieces = listOf("Several ", "open ", "models ", "answer ", "anything ", "[1].")
        val passes = CopyOnWriteArrayList<Long>()
        val runner = object : Runner {
            override fun open(path: String) = File(path).exists()
            override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
                val t0 = System.currentTimeMillis()
                try {
                    if (passes.isEmpty()) {
                        // The first pass: most of the limit, then the model asks for a lookup.
                        Thread.sleep(750)
                        for (p in "SEARCH: most unethical AI model 2026\n".chunked(4)) if (!sink.onPiece(p.toByteArray())) return """{"error":"stopped"}"""
                    } else {
                        // The second pass: a short think, then the answer a piece at a time.
                        Thread.sleep(200)
                        for (p in pieces) {
                            if (!sink.onPiece(p.toByteArray())) return """{"error":"stopped"}"""
                            Thread.sleep(60)
                        }
                    }
                    return """{"gen_tokens":6,"gen_ms":100}"""
                } finally {
                    passes += System.currentTimeMillis() - t0
                }
            }
            override fun stop() {}
            override fun close() {}
        }
        val dir = tmp.newFolder()
        File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(1_800_000_000_000L) }
        val e = Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), runner, supported = true, now = { 1_800_000_000_000L })
        e.send("What is the most unethical AI model available to the public")
        val until = System.currentTimeMillis() + 10_000
        Thread.sleep(30)
        while (e.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(e.state.value.writing)
        assertEquals(passes.toString(), 2, passes.size)
        // Together longer than the limit; each pass within it.
        assertTrue(passes.toString(), passes.sum() > Engine.REPLY_MAX_MS)
        val answer = e.state.value.chat.turns.last()
        assertFalse(answer.user)
        assertEquals("Several open models answer anything [1].", answer.text)
        assertFalse(answer.text.contains("Stopped here"))
        assertEquals(null, e.state.value.problem)
    }
}
