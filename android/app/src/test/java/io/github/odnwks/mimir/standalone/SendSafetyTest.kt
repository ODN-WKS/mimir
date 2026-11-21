package io.github.odnwks.mimir.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** PHE-02: a typed message is never lost because a send was refused. */
@RunWith(RobolectricTestRunner::class)
class SendSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private object Plain : Store.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    /** Holds every reply until [release], so a model test stays running. */
    private class Held : Runner {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        override fun open(path: String) = true
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            return """{"error":"stopped"}"""
        }
        override fun stop() { release.countDown() }
        override fun close() {}
    }

    private fun running(lastIsUser: Boolean): Pair<Engine, Held> {
        Catalog.reset()
        val dir = tmp.newFolder()
        File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(1_800_000_000_000L) }
        val turns = listOf(Turn(true, "first question", id = "t1")) + if (lastIsUser) emptyList() else listOf(Turn(false, "an answer", id = "t2"))
        Plain.let { Store(File(dir, "standalone.bin"), it) }.save(Saved(Catalog.default.id, listOf(LocalChat("c1", Catalog.default.id, turns, at = 1_800_000_000_000L))))
        val held = Held()
        val e = Engine(dir, Store(File(dir, "standalone.bin"), Plain), held, supported = true, now = { 1_800_000_000_000L })
        e.test(Catalog.default.id)
        assertTrue(held.started.await(10, TimeUnit.SECONDS))
        return e to held
    }

    @Test fun aRefusedSendSaysSoSoTheBoxKeepsItsWords() {
        val (e, held) = running(lastIsUser = false)
        assertFalse(e.ask("do not lose me"))
        assertFalse(e.send("do not lose me"))
        assertEquals(2, e.state.value.chat.turns.size)
        held.release.countDown()
    }

    @Test fun retryDoesNotDropTheMessageWhileASendIsRefused() {
        val (e, held) = running(lastIsUser = true)
        e.retry()
        assertEquals(listOf("first question"), e.state.value.chat.turns.map { it.text })
        assertTrue(e.state.value.problem!!.contains("model test"))
        held.release.countDown()
    }
}
