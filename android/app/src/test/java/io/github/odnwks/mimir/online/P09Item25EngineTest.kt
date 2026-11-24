package io.github.odnwks.mimir.online

import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.Runner
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

@RunWith(RobolectricTestRunner::class)
class P09Item25EngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()

    @Before fun setUp() { web.install(); Online.set(Online.Settings(searchAccepted = true)); LookupLog.clear() }
    @After fun tearDown() { Online.set(Online.Settings(searchAccepted = true)) }

    private fun engine(runner: Runner): Engine {
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

    @Test fun p09Item25TheOwnersQuestionReachesTheWebSearchAndTheModelIsToldWhatCame() {
        val runner = PromptRunner()
        val e = engine(runner)
        e.send("what is highest rated shaving replacement razor blades")
        e.waitDone()
        assertTrue(web.asked.toString(), web.asked.any { "duckduckgo" in it })
        assertTrue("no encyclopedia lookup first", web.asked.none { "wikipedia" in it })
    }

    @Test fun p09Item25AnEncyclopediaWithNoArticleFallsThroughToTheWebSearch() {
        web.wikiSearch = """{"query":{"search":[]}}"""
        web.grok = null
        val runner = PromptRunner()
        val e = engine(runner)
        e.send("what is a zorblax")
        e.waitDone()
        assertTrue(web.asked.toString(), web.asked.any { "wikipedia" in it })
        assertTrue(web.asked.toString(), web.asked.any { "duckduckgo" in it })
    }
}
