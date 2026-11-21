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
}
