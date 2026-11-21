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
class FollowUpWeatherTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()

    @Before fun setUp() { web.install(); Online.set(Online.Settings()); LookupLog.clear() }
    @After fun tearDown() { Online.set(Online.Settings()) }

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

    private fun article(title: String, text: String) {
        web.wikiSearch = """{"query":{"search":[{"title":"$title"}]}}"""
        web.wikiExtract = """{"query":{"pages":{"1":{"pageid":1,"title":"$title","extract":"$text"}}}}"""
    }

    private fun followUp(first: String, second: String, fact: String) {
        val runner = PromptRunner()
        val e = engine(runner)
        e.send(first)
        e.waitDone()
        assertTrue(runner.prompts.last().contains(fact))
        val asked = web.asked.size
        e.send(second)
        e.waitDone()
        assertEquals("a follow-up looks nothing up", asked, web.asked.size)
        assertTrue(runner.prompts.last(), runner.prompts.last().contains(fact))
        // What was looked up stays with its message, so the follow-up's prompt starts exactly as the
        // last one did (plus the answer) and the model only reads what's new.
        val before = runner.prompts[runner.prompts.size - 2]
        assertTrue("the follow-up starts with the last prompt", runner.prompts.last().startsWith(before))
        // A new chat starts clean.
        e.newChat()
        e.send("how old was she when she died")
        e.waitDone()
        assertFalse(runner.prompts.last().contains(fact))
    }

    @Test fun aFollowUpKeepsTheLastLookup() {
        article("Ada Lovelace", "Ada Lovelace was an English mathematician born in 1815 who died in 1852.")
        followUp("who was Ada Lovelace", "how old was she when she died", "born in 1815 who died in 1852")
    }

    @Test fun teslaFollowUp() {
        article("Nikola Tesla", "Nikola Tesla was an inventor who died in New York City in 1943.")
        followUp("who was Nikola Tesla", "where did he die", "died in New York City in 1943")
    }

    @Test fun placesAreFoundInTheseWordings() {
        for (t in listOf("weather Seattle this weekend", "Seattle weather", "how's the weather in Seattle this weekend", "Seattle weather this weekend"))
            assertEquals(t, Router.Need.Weather("Seattle"), Router.need(t))
        assertEquals(Router.Need.Weather("Sacramento"), Router.need("Is it going to rain in Sacramento tomorrow?"))
        assertEquals(Router.Need.Weather(null), Router.need("Will it rain tomorrow?"))
    }

    private fun describe(ask: String) = Weather.describe(JSONObject(FORECAST8), "Seattle", false, 0, ask)

    @Test fun rainQuestionsAnswerYesOrNoFirst() {
        assertTrue(describe("Will it rain tomorrow?").contains("Start the answer with \"No\" (the chance of rain or snow tomorrow is 20%"))
        assertTrue(describe("will it rain today").contains("Start the answer with \"Yes\" (the chance of rain or snow today is 80%"))
        assertTrue(describe("is it going to rain this weekend").contains("Start the answer with \"Maybe\" (the chance of rain or snow this weekend is 40%"))
        assertFalse(describe("weather tomorrow").contains("Start the answer"))
    }

    @Test fun weekendIsSaturdayAndSundayWithTheDateOnce() {
        val w = describe("weather Seattle this weekend")
        assertTrue(w, w.contains("Saturday (2026-11-21)"))
        assertTrue(w, w.contains("Sunday (2026-11-22)"))
        assertFalse(w, w.contains("Today"))
        assertFalse(w, w.contains("Monday"))
        // Without a weekend question: now, today and tomorrow, nothing more (the phone reads slowly).
        val plain = describe("")
        assertTrue(plain, plain.contains("\nToday: ") && plain.contains("\nTomorrow: "))
        assertFalse(plain, plain.contains("Saturday") || plain.contains("2026-11-"))
        assertFalse(w, Regex("(2026-\\d\\d-\\d\\d) \\(\\1\\)").containsMatchIn(w))
    }

    @Test fun weekendAsksForEnoughDays() {
        Online.set(Online.Settings(place = Place("Seattle", 47.61, -122.33)))
        val e = engine(PromptRunner())
        e.send("weather Seattle this weekend")
        e.waitDone()
        assertTrue(web.asked.last { "api.open-meteo" in it }, web.asked.last { "api.open-meteo" in it }.contains("forecast_days=8"))
    }

    companion object {
        // 2026-11-19 is a Thursday.
        const val FORECAST8 = """{"current":{"time":"2026-11-19T09:45","temperature_2m":7,"weather_code":61,"wind_speed_10m":15},
            "daily":{"time":["2026-11-19","2026-11-20","2026-11-21","2026-11-22","2026-11-23","2026-11-24","2026-11-25","2026-11-26"],
            "weather_code":[61,3,3,61,0,0,0,0],"temperature_2m_max":[9,8,8,9,6,6,6,6],"temperature_2m_min":[4,3,3,4,1,1,1,1],
            "precipitation_probability_max":[80,20,40,10,0,0,0,0],"precipitation_sum":[3,0,0,0,0,0,0,0],"wind_speed_10m_max":[25,18,10,10,10,10,10,10]}}"""
    }
}
