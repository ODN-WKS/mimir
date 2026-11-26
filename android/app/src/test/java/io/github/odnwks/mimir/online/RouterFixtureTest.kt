package io.github.odnwks.mimir.online

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Reads crates/proto/tests/fixtures/router.jsonl and validates that Router.need() returns the
 * expected route for each input. Shared fixture with the Rust side (B5).
 */
@RunWith(RobolectricTestRunner::class)
class RouterFixtureTest {
    private fun fixtures(): List<JSONObject> {
        // Path from android/app module to the Rust fixtures directory.
        val f = File("../../crates/proto/tests/fixtures/router.jsonl")
        return f.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
    }

    @Test fun routerFixturesAllPass() {
        val rows = fixtures()
        assertTrue("router.jsonl must have fixture lines", rows.isNotEmpty())
        for (row in rows) {
            val input = row.getString("input")
            val expectedRoute = row.getString("route")
            val flags: List<String> = (0 until row.getJSONArray("flags").length())
                .map { row.getJSONArray("flags").getString(it) }

            // "read <url>" inputs are page-reader patterns (phe10): Router.need() returns null.
            if (expectedRoute == "page") {
                // Page reading is triggered by tappedPage, not by Router.need().
                // The fixture documents the behavior; we verify that need() doesn't misclassify it.
                val need = Router.need(input)
                assertTrue("'$input' should not be classified as weather/encyclopedia/search by need()",
                    need == null || need is Router.Need.Search)
                continue
            }

            val need = Router.need(input)
            when (expectedRoute) {
                "none" -> assertNull("'$input' expected none but got $need", need)
                "weather" -> {
                    assertTrue("'$input' expected weather but got $need", need is Router.Need.Weather)
                    // PHE-13: check the place if a flag specifies it.
                    val placeFlag = flags.firstOrNull { it.startsWith("place:") }?.removePrefix("place:")
                    if (placeFlag != null) {
                        val w = need as Router.Need.Weather
                        assertEquals("'$input' wrong place", placeFlag, w.place)
                    }
                }
                "search" -> {
                    assertTrue("'$input' expected search but got $need", need is Router.Need.Search)
                    // PHE-15: Grokipedia only source check.
                    if (flags.contains("source:grokipedia")) {
                        val s = need as Router.Need.Search
                        assertEquals("'$input' expected GrokipediaOnly source",
                            Router.SearchSource.GrokipediaOnly, s.source)
                    }
                }
                "encyclopedia" -> assertTrue("'$input' expected encyclopedia but got $need",
                    need is Router.Need.Encyclopedia)
                else -> throw AssertionError("unknown route '$expectedRoute' for '$input'")
            }
        }
    }

    /**
     * P21 stage 2: the time-bound cues (anything current is looked up even when worded loosely), and
     * general knowledge that must not search. Same line shape as router.jsonl.
     */
    private val widened = """
{"input": "who is the current president of France", "route": "search", "flags": ["p21", "current", "office"]}
{"input": "what is the current population of Tokyo", "route": "search", "flags": ["p21", "current"]}
{"input": "latest iPhone model?", "route": "search", "flags": ["p21", "latest"]}
{"input": "what's the newest Pixel phone", "route": "search", "flags": ["p21", "newest"]}
{"input": "who won the game today", "route": "search", "flags": ["p21", "today"]}
{"input": "who won the Super Bowl this year", "route": "search", "flags": ["p21", "this year"]}
{"input": "is the Louvre still open", "route": "search", "flags": ["p21", "still"]}
{"input": "GTA 6 release date", "route": "search", "flags": ["p21", "release date"]}
{"input": "when was the Pixel 10 released", "route": "search", "flags": ["p21", "released"]}
{"input": "what was the score of the Arsenal game last night", "route": "search", "flags": ["p21", "score"]}
{"input": "Premier League standings", "route": "search", "flags": ["p21", "standings"]}
{"input": "what is the Lakers schedule", "route": "search", "flags": ["p21", "schedule"]}
{"input": "is the pharmacy on Main Street open now", "route": "search", "flags": ["p21", "open now"]}
{"input": "is the PS5 Pro in stock", "route": "search", "flags": ["p21", "in stock"]}
{"input": "what version of Android is out", "route": "search", "flags": ["p21", "version"]}
{"input": "who is the prime minister of Canada", "route": "search", "flags": ["p21", "office"]}
{"input": "who is the CEO of Tesla", "route": "search", "flags": ["p21", "office"]}
{"input": "who's the mayor of Chicago", "route": "search", "flags": ["p21", "office"]}
{"input": "who is the governor of Texas", "route": "search", "flags": ["p21", "office"]}
{"input": "who is the king of Sweden", "route": "search", "flags": ["p21", "office"]}
{"input": "who is the queen of Denmark", "route": "search", "flags": ["p21", "office"]}
{"input": "who is the pope", "route": "search", "flags": ["p21", "office"]}
{"input": "who is the president of Brazil", "route": "search", "flags": ["p21", "office"]}
{"input": "how much is a Tesla Model 3", "route": "search", "flags": ["p21", "how much is"]}
{"input": "how much does an iPhone 16 cost", "route": "search", "flags": ["p21", "how much does"]}
{"input": "can you tell me the latest Android version", "route": "search", "flags": ["p21", "latest"]}
{"input": "who was the first president", "route": "encyclopedia", "flags": ["p21", "negative"]}
{"input": "what is a version control system", "route": "encyclopedia", "flags": ["p21", "negative"]}
{"input": "what is an electric current", "route": "encyclopedia", "flags": ["p21", "negative"]}
{"input": "how much is 2 plus 2", "route": "none", "flags": ["p21", "negative"]}
{"input": "how much is 15% of 80", "route": "none", "flags": ["p21", "negative"]}
{"input": "how much does the earth weigh", "route": "none", "flags": ["p21", "negative"]}
{"input": "how are you today", "route": "none", "flags": ["p21", "negative"]}
{"input": "what day is it today", "route": "none", "flags": ["p21", "negative"]}
{"input": "what's the current time", "route": "none", "flags": ["p21", "negative"]}
{"input": "write a poem about today", "route": "none", "flags": ["p21", "negative"]}
{"input": "can you make a schedule for my week", "route": "none", "flags": ["p21", "negative", "phe05"]}
{"input": "I still love my old phone", "route": "none", "flags": ["p21", "negative", "phe05"]}
{"input": "is my order still in stock", "route": "none", "flags": ["p21", "negative", "phe05"]}
{"input": "what's the score of my test today", "route": "none", "flags": ["p21", "negative", "phe05"]}
{"input": "how much does my dog's medicine cost", "route": "none", "flags": ["p21", "negative", "phe05"]}
{"input": "who is the current president of my school club", "route": "none", "flags": ["p21", "negative", "phe05"]}
{"input": "weather for tomorrow in Paris", "route": "weather", "flags": ["p21", "place:Paris"]}
{"input": "weather tomorrow in Paris", "route": "weather", "flags": ["p21", "place:Paris"]}
{"input": "weather in Paris tomorrow", "route": "weather", "flags": ["p21", "place:Paris"]}
{"input": "weather 90210", "route": "weather", "flags": ["p21", "place:90210"]}
{"input": "weather in SW1A 1AA", "route": "weather", "flags": ["p21", "place:SW1A 1AA"]}
{"input": "what's the weather in sw1a 1aa tomorrow?", "route": "weather", "flags": ["p21", "place:SW1A 1AA"]}
"""

    @Test fun theTimeBoundCuesSearchAndGeneralKnowledgeDoesNot() {
        val rows = widened.lines().filter { it.isNotBlank() }.map { JSONObject(it) }
        assertTrue(rows.size >= 40)
        val wrong = mutableListOf<String>()
        for (row in rows) {
            val input = row.getString("input")
            val flags = (0 until row.getJSONArray("flags").length()).map { row.getJSONArray("flags").getString(it) }
            val need = Router.need(input)
            val ok = when (row.getString("route")) {
                "none" -> need == null
                "search" -> need is Router.Need.Search
                "encyclopedia" -> need is Router.Need.Encyclopedia
                "weather" -> need is Router.Need.Weather &&
                    flags.firstOrNull { it.startsWith("place:") }?.removePrefix("place:")?.let { it == need.place } != false
                else -> false
            }
            if (!ok) wrong += "'$input' expected ${row.getString("route")} ${flags.filter { it.startsWith("place:") }} but got $need"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        // PHE-05 and the 300-character limit still hold for the new cues.
        assertEquals(null, Router.need("what is the latest news about my sister"))
        assertEquals(null, Router.need("who is the current president of France " + "and more ".repeat(40)))
    }
}
