package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.data.Mapping
import io.github.odnwks.mimir.data.Power
import io.github.odnwks.mimir.link.Wire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** "Test on this device", how a model fits a phone, and what powers tasks. */
@RunWith(RobolectricTestRunner::class)
class ModelTestTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Answers each test question the way [answer] says; counts loads and closes. */
    private class Answers(private val answer: (String) -> String, private val loads: Boolean = true) : Runner {
        var opened = 0
        var closed = 0
        override fun open(path: String): Boolean { opened++; return loads }
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            val words = answer(prompt).split(" ")
            for (w in words) if (!sink.onPiece("$w ".toByteArray())) return """{"error":"stopped"}"""
            return """{"gen_tokens":${words.size},"gen_ms":100}"""
        }
        override fun stop() {}
        override fun close() { closed++ }
    }

    private val good: (String) -> String = { p ->
        when {
            "France" in p -> "Paris"
            "12 plus 30" in p -> "42"
            "rhymes" in p -> "Hat"
            else -> "A pin tumbler lock has spring-loaded pins; a tension wrench turns the plug while a pick lifts each pin to the shear line."
        }
    }

    private val uncensored = Catalog.builtIn.first { it.refusalsRemoved }
    private val filtered = uncensored.copy(id = "plain", refusalsRemoved = false)

    @Test fun sensibleAnswersAndNoRefusalGiveAGoodVerdict() {
        val r = ModelTester(Answers(good), AtomicBoolean(false), { 2_000 }, { 30f }, { 1L }).run(uncensored, "x") {}!!
        assertTrue(r.loaded)
        assertEquals(3, r.sensible)
        assertEquals(3, r.asked)
        assertEquals(false, r.refused)
        assertTrue(r.good)
        assertTrue(r.verdict, r.verdict.startsWith("Good for chat on this phone"))
        assertTrue(r.wordsPerSecond > 0)
        assertEquals(2_000, r.memoryMb)
    }

    @Test fun aRefusalIsCaughtOnlyForModelsWithNoSafetyFilter() {
        val refuses: (String) -> String = { p -> if ("lock" in p) "I'm sorry, but I can't help with that request at all, as it may be misused." else good(p) }
        val r = ModelTester(Answers(refuses), AtomicBoolean(false)).run(uncensored, "x") {}!!
        assertEquals(true, r.refused)
        assertFalse(r.good)
        assertTrue(r.verdict, r.verdict.contains("safety filter"))
        // A filtered model isn't asked.
        val f = ModelTester(Answers(refuses), AtomicBoolean(false)).run(filtered, "x") {}!!
        assertNull(f.refused)
        assertTrue(f.good)
    }

    @Test fun oddAnswersAndAFailedLoadAreSaidPlainly() {
        val odd = ModelTester(Answers({ "Banana" }), AtomicBoolean(false)).run(filtered, "x") {}!!
        assertEquals(0, odd.sensible)
        assertTrue(odd.verdict, odd.verdict.startsWith("Gave 0 of 3 sensible answers"))
        val none = ModelTester(Answers(good, loads = false), AtomicBoolean(false)).run(filtered, "x") {}!!
        assertFalse(none.loaded)
        assertTrue(none.verdict.startsWith("Didn't load"))
    }

    @Test fun aCancelledTestKeepsNoResult() {
        val stop = AtomicBoolean(false)
        val r = ModelTester(Answers(good), stop).run(uncensored, "x") { p -> if (p.step == 2) stop.set(true) }
        assertNull(r)
    }

    @Test fun resultsSurviveAsJson() {
        val r = TestResult(5L, true, 5_700, 8.4, 2_100, 31f, 34f, 3, 3, false)
        assertEquals(r, TestResult.fromJson(JSONObject(r.toJson().toString())))
        val bare = TestResult(5L, false)
        assertEquals(bare, TestResult.fromJson(JSONObject(bare.toJson().toString())))
    }

    @Test fun fitFollowsThePhonesMemory() {
        val s25 = 11_500_000_000L
        assertEquals(Fit.Easily, Fit.of(2_707_515_040, s25))
        assertEquals(Fit.Tight, Fit.of(5_335_289_696, s25))
        assertEquals(Fit.TooBig, Fit.of(5_335_289_696, 4_000_000_000))
    }

    @Test fun theEngineTestsADownloadedModelClosesItAndKeepsTheNumbers() {
        Catalog.reset()
        val dir = tmp.newFolder()
        File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(1_800_000_000_000L) }
        val runner = Answers(good)
        fun engine() = Engine(dir, Store(File(dir, "standalone.bin"), Plain), runner, supported = true, now = { 1_800_000_000_000L }, memoryMb = { 1_500 })
        val e = engine()
        e.test(Catalog.default.id)
        val deadline = System.currentTimeMillis() + 10_000
        while ((e.state.value.testing != null || e.state.value.tests.isEmpty()) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val r = e.state.value.tests[Catalog.default.id]
        assertNotNull(r)
        assertTrue(r!!.good)
        assertNull(e.state.value.testing)
        assertTrue("the model is closed again", runner.closed >= 1)
        // Only the numbers are kept, and they're there next time.
        assertEquals(setOf("models", "model-tests.json", "standalone.bin"), dir.list()!!.filter { !it.endsWith(".tmp") }.toSet())
        assertEquals(r, engine().state.value.tests[Catalog.default.id])
        // A model that isn't on the phone isn't tested.
        e.test("gemma4-e2b-heretic-q4km")
        assertNull(e.state.value.testing)
    }

    @Test fun thePcsPowerSettingArrivesAndIsShown() {
        val l = Wire.modelList(JSONObject("""{"free":[],"free_model":null,"switch_to_free":false,"ollama":"","power":"free"}"""))!!
        assertEquals(Power.FreeOnly, Mapping.models(l, "auto").power)
        val old = Wire.modelList(JSONObject("""{"free":[],"free_model":null,"switch_to_free":false,"ollama":""}"""))!!
        assertNull(Mapping.models(old, "auto").power)
        assertEquals(Power.ClaudeOnly, Power.of("claude"))
        assertEquals(Power.Backup, Power.of("backup"))
    }

    private object Plain : Store.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }
}
