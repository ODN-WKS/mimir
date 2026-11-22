package io.github.odnwks.mimir.online

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.net.InetAddress
import java.util.concurrent.CountDownLatch

/**
 * Lookups are fast and never hang (owner, 2026-11-19): about 3 s typical, 6 s at most, however a
 * site or the network behaves. The weather comes from several sources asked at once, a search from
 * the PC and DuckDuckGo at once, and everything that is read is small.
 */
@RunWith(RobolectricTestRunner::class)
class FastLookupTest {
    private val web = FakeWeb()

    /** Held by every stand-in that "hangs forever"; let go after each check. */
    private val hang = CountDownLatch(1)

    @Before fun setUp() {
        web.install()
        web.geocode = """{"results":[{"name":"Portland","admin1":"Oregon","country":"United States","latitude":45.5152,"longitude":-122.6784}]}"""
        Online.set(Online.Settings())
        LookupLog.clear()
        Search.forget()
        ShadowLog.clear()
    }

    @After fun tearDown() {
        hang.countDown()
        Net.viaPc = null
        Net.torTransport = { throw Net.Refused("Tor isn't running") }
        Search.viaPc = null
        Online.set(Online.Settings())
        Search.forget()
    }

    private fun timed(f: () -> Unit): Long {
        val t0 = System.currentTimeMillis()
        f()
        return System.currentTimeMillis() - t0
    }

    /** The fake internet, except where [slow] says a site hangs forever and [answer] gives one a different answer. */
    private fun transport(slow: (String) -> Boolean = { false }, answer: (String) -> Net.Reply? = { null }) {
        val fake = Net.transport
        Net.transport = { r ->
            val u = r.url.toString()
            if (slow(u)) { hang.await(); throw java.io.IOException("never answered") }
            answer(u) ?: fake(r)
        }
    }

    private val portland = Place("Portland, Oregon, United States", 45.5152, -122.6784)

    private fun json(s: String, type: String = "application/json") = Net.Reply(200, null, type, s.toByteArray())

    // ---- Nothing waits longer than the budget ----

    @Test fun aSiteThatNeverAnswersEndsWithinTheBudget() {
        assertEquals(6_000L, Net.BUDGET_MS)
        Net.transport = { hang.await(); throw java.io.IOException("never") }
        var e: Throwable? = null
        val took = timed { e = runCatching { Net.get("https://api.open-meteo.com/v1/forecast?x=1", ChatMode.Normal, "x") }.exceptionOrNull() }
        assertTrue("took $took ms", took < Net.BUDGET_MS + 500)
        assertTrue(e.toString(), e is Net.Failed)
        assertTrue(Net.sorry(e!!), "too slow" in Net.sorry(e!!))
    }

    @Test fun aNameLookupThatNeverAnswersEndsWithinTheBudgetToo() {
        Net.resolve = { hang.await(); listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))) }
        var e: Throwable? = null
        val took = timed { e = runCatching { Net.within(1_500) { Net.get("https://api.open-meteo.com/v1/forecast?x=1", ChatMode.Normal, "x") } }.exceptionOrNull() }
        assertTrue("took $took ms", took < 2_000)
        assertTrue(e.toString(), e is Net.Failed)
        assertTrue(web.asked.isEmpty())
    }

    @Test fun thePcAnswersWhileThePhonesNameLookupIsStuck() {
        Net.resolve = { hang.await(); emptyList() }
        val direct = Net.transport
        Net.viaPc = { r -> direct(r) }
        val took = timed {
            val got = Net.get("https://api.open-meteo.com/v1/forecast?x=1", ChatMode.Normal, "x")
            assertTrue(got.text.contains("daily"))
        }
        assertTrue("took $took ms", took < 1_000)
        assertEquals(LookupLog.Route.Pc, LookupLog.entries.value.first().route)
    }

    @Test fun theHomeNetworkIsStillRefusedAfterTheNameIsLookedUpInTheWaysOwnThread() {
        Net.resolve = { listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))) }
        val e = runCatching { Net.get("https://router.example.com/", ChatMode.Normal, "x") }.exceptionOrNull()
        assertTrue(e.toString(), e is Net.Refused)
        assertTrue(web.asked.isEmpty())
    }

    // ---- Weather from several sources, the first good answer wins ----

    /** MET Norway's compact answer for a day and a half from 10:00 local time at Portland (UTC-8). */
    private fun metNoJson(): String {
        val start = java.time.Instant.parse("2026-11-19T18:00:00Z")
        val entries = (0 until 40).joinToString(",") { i ->
            val t = start.plusSeconds(i * 3600L)
            val rain = i < 3
            """{"time":"$t","data":{"instant":{"details":{"air_temperature":${5 + (i % 24) / 3.0},"wind_speed":3.5}},
              "next_1_hours":{"summary":{"symbol_code":"${if (rain) "lightrain" else "cloudy"}"},"details":{"precipitation_amount":${if (rain) 1.0 else 0.0}}},
              "next_6_hours":{"summary":{"symbol_code":"${if (rain) "lightrain" else "cloudy"}"},"details":{"precipitation_amount":0.0}}}}"""
        }
        return """{"type":"Feature","properties":{"timeseries":[$entries]}}"""
    }

    @Test fun openMeteoHangingWhileMetNorwayAnswersTheWeatherIsFoundAtOnce() {
        web.metNo = metNoJson()
        transport(slow = { "api.open-meteo.com/v1/forecast" in it })
        var found: Found? = null
        val took = timed {
            Net.within(Net.BUDGET_MS) {
                val place = Weather.find("Portland", ChatMode.Normal)!!
                found = Weather.forecast(place, ChatMode.Normal, imperial = false, ask = "weather in Portland")
            }
        }
        // MET Norway answered at once: nothing waited for Open-Meteo.
        assertTrue("took $took ms", took < 1_000)
        val block = found!!.block
        assertTrue(block, block.startsWith("Weather for Portland, Oregon, United States (MET Norway)"))
        assertTrue(block, block.contains("\nNow: ") && block.contains("\nToday: ") && block.contains("\nTomorrow: "))
        assertTrue(block, block.contains("light rain"))
        assertEquals(listOf("met.no"), found!!.sources.map { it.site })
        assertEquals("met.no", Weather.SITE)
        assertTrue("${block.length} characters: $block", block.length < 350)
        assertTrue(web.askedMore.any { it.startsWith("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=45.52&lon=-122.68") })
    }

    @Test fun inTheUnitedStatesTheWeatherServiceAnswersWhenTheOthersHang() {
        web.points = """{"properties":{"forecast":"https://api.weather.gov/gridpoints/STO/39,122/forecast"}}"""
        web.gridForecast = WEATHER_GOV
        transport(slow = { "api.open-meteo.com/v1/forecast" in it || "api.met.no" in it })
        var found: Found? = null
        val took = timed { Net.within(Net.BUDGET_MS) { found = Weather.forecast(portland, ChatMode.Normal, imperial = true) } }
        assertTrue("took $took ms", took < 1_000)
        assertTrue(found!!.block, found!!.block.contains("(the US National Weather Service)"))
        assertEquals(listOf("weather.gov"), found!!.sources.map { it.site })
        assertTrue(web.askedMore.any { it.startsWith("https://api.weather.gov/points/45.52,-122.68") })
        // Not asked outside the United States.
        hang.countDown()
        web.install()
        web.askedMore.clear()
        Net.within(Net.BUDGET_MS) { Weather.forecast(Place("Leeds", 53.80, -1.55), ChatMode.Normal, imperial = false) }
        assertTrue(web.askedMore.toString(), web.askedMore.none { "weather.gov" in it })
    }

    @Test fun whicheverSourceAnsweredTheForecastIsKeptForEveryone() {
        web.metNo = metNoJson()
        transport(slow = { "api.open-meteo.com/v1/forecast" in it })
        Net.within(Net.BUDGET_MS) { Weather.forecast(portland, ChatMode.Normal, imperial = false) }
        val before = web.asked.size + web.askedMore.size
        // Within 15 minutes: nothing is asked again, from any source.
        val again = Net.within(Net.BUDGET_MS) { Weather.forecast(portland, ChatMode.Normal, imperial = false, ask = "will it rain tomorrow") }
        assertEquals(before, web.asked.size + web.askedMore.size)
        assertEquals(listOf("met.no"), again.sources.map { it.site })
        // The same answer in other units is another question.
        Net.within(Net.BUDGET_MS) { Weather.forecast(portland, ChatMode.Normal, imperial = true) }
        assertTrue(web.askedMore.size > 1)
    }

    @Test fun everySourceFailingSaysSoInPlainWordsWithinTheBudget() {
        Net.transport = { throw java.net.ConnectException("refused") }
        var e: Throwable? = null
        val took = timed { e = runCatching { Net.within(Net.BUDGET_MS) { Weather.forecast(portland, ChatMode.Normal, imperial = false) } }.exceptionOrNull() }
        assertTrue("took $took ms", took < Net.BUDGET_MS + 500)
        assertTrue(e.toString(), e is Net.Failed)
        val said = Net.sorry(e!!)
        assertEquals("Couldn't look that up just now (the site is out of reach just now). Use the buttons below to look again, or to answer without it.", said)
    }

    @Test fun aPlaceIsFoundFromOpenStreetMapWhenOpenMeteosPlaceSearchHangs() {
        web.nominatim = """[{"lat":"45.5152","lon":"-122.6784","display_name":"Portland, Multnomah County, Oregon, United States"}]"""
        transport(slow = { "geocoding-api" in it })
        var place: Place? = null
        val took = timed { place = Net.within(Net.BUDGET_MS) { Weather.find("Portland", ChatMode.Normal) } }
        assertTrue("took $took ms", took < 1_000)
        assertEquals("Portland, Oregon, United States", place?.name)
        assertEquals(45.5152, place!!.lat, 1e-6)
        // The phone only: OpenStreetMap is never asked through the PC.
        assertFalse(Net.pcMay(java.net.URL(web.askedMore.single())))
        // And the place is kept a day: asked once.
        Net.within(Net.BUDGET_MS) { Weather.find("portland", ChatMode.Normal) }
        assertEquals(1, web.askedMore.size)
    }

    // Q2 review: "no results" from one search while the other failed is not proof the place is missing, so the
    // person is told the lookup failed (the connection), never that the place wasn't found.
    @Test fun noResultsFromOneSearchWhileTheOtherFailsIsAFailureNotAMissingPlace() {
        web.geocode = """{"generationtime_ms":0.1}"""
        val e = runCatching { Net.within(Net.BUDGET_MS) { Weather.find("Qwertyville", ChatMode.Normal) } }.exceptionOrNull()
        assertTrue(e.toString(), e is Net.Failed)
    }

    @Test fun theWeatherServicesAnswerAndMetNorwaysAreReadAsOpenMeteoShapedData() {
        val gov = Weather.fromWeatherGov(JSONObject(WEATHER_GOV), imperial = true)
        val d = gov.getJSONObject("daily")
        assertEquals("2026-11-19", d.getJSONArray("time").getString(0))
        assertEquals(40.0, d.getJSONArray("temperature_2m_min").getDouble(0), 0.01)
        assertEquals(55.0, d.getJSONArray("temperature_2m_max").getDouble(1), 0.01)
        assertEquals(26.0, d.getJSONArray("precipitation_probability_max").getDouble(1), 0.01)
        assertEquals(10.0, d.getJSONArray("wind_speed_10m_max").getDouble(1), 0.01)
        assertEquals(3, d.getJSONArray("weather_code").getInt(1))
        val metric = Weather.fromWeatherGov(JSONObject(WEATHER_GOV), imperial = false)
        assertEquals(12.8, metric.getJSONObject("daily").getJSONArray("temperature_2m_max").getDouble(1), 0.1)

        val met = Weather.fromMetNo(JSONObject(metNoJson()), java.time.ZoneOffset.ofHours(-8), imperial = false)
        val md = met.getJSONObject("daily")
        assertEquals("2026-11-19", md.getJSONArray("time").getString(0))
        assertEquals("2026-11-20", md.getJSONArray("time").getString(1))
        // Three mm of light rain this morning: a rainy day, a likely rain; tomorrow dry and overcast.
        assertEquals(61, md.getJSONArray("weather_code").getInt(0))
        assertEquals(3, md.getJSONArray("weather_code").getInt(1))
        assertEquals(3.0, md.getJSONArray("precipitation_sum").getDouble(0), 0.01)
        assertTrue(md.getJSONArray("precipitation_probability_max").getDouble(0) >= 50)
        assertEquals(3.5 * 3.6, met.getJSONObject("current").getDouble("wind_speed_10m"), 0.01)
        assertEquals("2026-11-19T10:00", met.getJSONObject("current").getString("time"))
        // Said as a guess: the chance isn't printed as if the service gave it.
        assertFalse(Weather.describe(met, "Portland", false, 0).contains("% rain"))
        assertTrue(Weather.describe(met, "Portland", false, 0, "will it rain today").contains("Start the answer with \"Yes\""))
    }

    // ---- Small blocks, because the phone reads them slowly ----

    @Test fun aTwoMegabytePageBecomesABlockCutToTheLimit() {
        val body = "<html><head><title>Big</title></head><body><main>" + "<p>The old bridge reopens on Monday after two years of repairs.</p>\n".repeat(32_000) + "</main></body></html>"
        assertTrue(body.length > 2_000_000)
        val direct = Net.transport
        Net.transport = { r -> if (r.url.host == "news.example.com") json(body, "text/html") else direct(r) }
        var page: PageText.Page? = null
        val took = timed {
            val got = Net.get("https://news.example.com/big", ChatMode.Normal, "x", accept = setOf("text/html"))
            page = PageText.of(got.text)
        }
        assertEquals(2_500, PageText.PAGE_CHARS)
        assertTrue("${page!!.text.length} characters", page!!.text.length < PageText.PAGE_CHARS + 60)
        assertTrue(page!!.text.endsWith("[…the rest of the page was left out]"))
        assertEquals("Big", page!!.title)
        assertTrue("took $took ms", took < 3_000)
    }

    @Test fun anEncyclopediaArticleIsCutToAboutSevenHundredCharacters() {
        web.wikiExtract = """{"query":{"pages":{"1":{"pageid":1,"title":"Black hole","extract":"${"A black hole is a region of spacetime. ".repeat(80)}"}}}}"""
        val f = Encyclopedia.look("black hole", Encyclopedias.Wikipedia, grok = false, ChatMode.Normal)!!
        assertEquals(700, Encyclopedia.ARTICLE_CHARS)
        assertTrue("${f.wikipedia!!.text.length}", f.wikipedia!!.text.length < 700 + 60)
        val g = Encyclopedia.look("black hole", Encyclopedias.Both, grok = true, ChatMode.Normal)!!
        assertNotNull(g.wikipedia)
        assertNotNull(g.grokipedia)
        assertTrue(g.wikipedia!!.text.length < 760)
    }

    @Test fun theBothEncyclopediasAreReadAtTheSameTimeOnceTheTitleIsKnown() {
        val started = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val fake = Net.transport
        Net.transport = { r ->
            val u = r.url.toString()
            if ("prop=extracts" in u || "grokipedia.com" in u) { started[if ("grokipedia" in u) "grok" else "wiki"] = System.currentTimeMillis(); Thread.sleep(400) }
            fake(r)
        }
        val took = timed { assertNotNull(Encyclopedia.look("black hole", Encyclopedias.Both, grok = true, ChatMode.Normal)) }
        assertTrue("took $took ms", took < 750)
        assertTrue(Math.abs(started["wiki"]!! - started["grok"]!!) < 200)
    }

    @Test fun searchResultsAreTheTopThreeWithShortSnippets() {
        val long = "word ".repeat(100)
        val rs = (1..5).map { Search.Result("Title $it", "https://site$it.example/page", long) }
        val text = Search.describe("x", rs)
        assertEquals(1 + 3, text.lines().size)
        for (line in text.lines().drop(1)) assertTrue("${line.length}: $line", line.length < 160 + 60)
        assertTrue(text, text.contains("…"))
        assertEquals("short one", Search.shorten("short one"))
    }

    // ---- Search: the PC and DuckDuckGo at the same time ----

    @Test fun withThePcHangingDuckDuckGoAnswersAtOnce() {
        Online.set(Online.Settings(searchAccepted = true))
        Search.viaPc = { hang.await(); null }
        var results = emptyList<Search.Result>()
        val took = timed {
            // PHW-04: searchViaPc waits up to PC_FIRST_MS for the PC, then returns null for fallback.
            assertNull(Search.searchViaPc("price of bitcoin today", ChatMode.Normal))
            results = Search.search("price of bitcoin today", ChatMode.Normal)
        }
        // Takes at most PC_FIRST_MS + a small buffer (DuckDuckGo is instant via fake transport).
        assertTrue("took $took ms", took < Search.PC_FIRST_MS + 1_000)
        assertEquals(2, results.size)
        // DuckDuckGo was asked once, not again by the second call.
        assertEquals(1, web.asked.count { "duckduckgo" in it })
    }

    @Test fun withDuckDuckGoHangingThePcsAnswerIsUsedAtOnce() {
        Online.set(Online.Settings(searchAccepted = true))
        transport(slow = { "duckduckgo" in it })
        Search.viaPc = { listOf(Search.Result("From the PC", "https://pc.example/x", "found by SearXNG", listOf("brave"))) }
        var results: List<Search.Result>? = null
        val took = timed { results = Search.searchViaPc("price of bitcoin today", ChatMode.Normal) }
        assertTrue("took $took ms", took < 1_000)
        assertEquals("From the PC", results!!.single().title)
    }

    @Test fun aBlockedDuckDuckGoDoesNotHoldUpThePcOrGetAskedTwice() {
        Online.set(Online.Settings(searchAccepted = true))
        web.blocked = true
        Search.viaPc = { null }
        assertNull(Search.searchViaPc("cheap flights", ChatMode.Normal))
        val e = runCatching { Search.search("cheap flights", ChatMode.Normal) }.exceptionOrNull()
        assertTrue(e.toString(), e is Search.Blocked)
        assertEquals(1, web.asked.count { "duckduckgo" in it })
    }

    @Test fun withoutTheOneTimeWarningOnlyThePcIsAsked() {
        Online.set(Online.Settings(searchAccepted = false))
        Search.viaPc = { listOf(Search.Result("From the PC", "https://pc.example/x", "found")) }
        assertEquals(1, Search.searchViaPc("cheap flights", ChatMode.Normal)!!.size)
        assertTrue(web.asked.none { "duckduckgo" in it })
        // A search that finds nothing is an answer too, not a reason to ask DuckDuckGo behind the owner's back.
        Search.forget()
        Search.viaPc = { emptyList() }
        assertEquals(emptyList<Search.Result>(), Search.searchViaPc("zzz qqq", ChatMode.Normal))
        assertTrue(web.asked.none { "duckduckgo" in it })
    }

    // ---- What is logged ----

    @Test fun aNormalChatLogsHostRouteTimesStatusAndSizeButNeverTheWords() {
        Net.within(Net.BUDGET_MS) { Weather.find("Portland", ChatMode.Normal) }
        val lines = ShadowLog.getLogsForTag(Net.TAG).map { it.msg }
        val line = lines.firstOrNull { "geocoding-api.open-meteo.com" in it }
        assertNotNull(lines.toString(), line)
        for (part in listOf("from this phone", "dns ", "connect ", "total ", "status 200", " bytes")) assertTrue("$part in $line", part in line!!)
        assertTrue(lines.toString(), lines.none { "Portland" in it || "portland" in it })
    }

    @Test fun aFailedRequestIsLoggedWithoutItsAddress() {
        Net.transport = { throw java.io.IOException("could not read https://api.open-meteo.com/v1/forecast?latitude=45.52&longitude=-122.68") }
        runCatching { Net.within(1_500) { Net.get("https://api.open-meteo.com/v1/forecast?latitude=45.52&longitude=-122.68", ChatMode.Normal, "Portland") } }
        val lines = ShadowLog.getLogsForTag(Net.TAG).map { it.msg }
        assertTrue(lines.toString(), lines.any { "failed (IOException" in it })
        assertTrue(lines.toString(), lines.none { "45.52" in it || "Portland" in it })
    }

    @Test fun aWhisperLogsNothing() {
        Net.torTransport = { json("""{"results":[]}""") }
        Net.get("https://geocoding-api.open-meteo.com/v1/search?name=Portland", ChatMode.WhisperWeb, "Portland")
        assertEquals(emptyList<String>(), ShadowLog.getLogsForTag(Net.TAG).map { it.msg })
        val refused = runCatching { Net.get("https://geocoding-api.open-meteo.com/v1/search?name=Portland", ChatMode.Whisper, "Portland") }.exceptionOrNull()
        assertTrue(refused is Net.Refused)
        assertEquals(emptyList<String>(), ShadowLog.getLogsForTag(Net.TAG).map { it.msg })
        assertTrue(web.asked.isEmpty() && web.askedMore.isEmpty())
    }

    @Test fun theWeatherThroughTorAsksOneSourceAfterTheOtherNeverAllAtOnce() {
        val seen = mutableListOf<String>()
        Net.torTransport = { r ->
            seen += r.url.host
            if (r.url.host == "api.open-meteo.com") throw java.io.IOException("down") else json(metNoJson())
        }
        val found = Net.within(Long.MAX_VALUE / 4) { Weather.forecast(portland, ChatMode.WhisperWeb, imperial = false) }
        assertEquals(listOf("api.open-meteo.com", "api.met.no"), seen)
        assertTrue(found.block, found.block.contains("(MET Norway)"))
        assertTrue(LookupLog.entries.value.all { it.route == LookupLog.Route.Tor })
    }

    companion object {
        /** The National Weather Service's twelve-hour periods, as the service writes them. */
        const val WEATHER_GOV = """{"properties":{"periods":[
          {"number":1,"name":"Tonight","startTime":"2026-11-19T21:00:00-08:00","isDaytime":false,"temperature":40,"temperatureUnit":"F",
           "probabilityOfPrecipitation":{"unitCode":"wmoUnit:percent","value":38},"windSpeed":"3 mph","shortForecast":"Chance Rain Showers"},
          {"number":2,"name":"Friday","startTime":"2026-11-20T06:00:00-08:00","isDaytime":true,"temperature":55,"temperatureUnit":"F",
           "probabilityOfPrecipitation":{"unitCode":"wmoUnit:percent","value":26},"windSpeed":"5 to 10 mph","shortForecast":"Mostly Cloudy"},
          {"number":3,"name":"Friday Night","startTime":"2026-11-20T18:00:00-08:00","isDaytime":false,"temperature":41,"temperatureUnit":"F",
           "probabilityOfPrecipitation":{"unitCode":"wmoUnit:percent","value":null},"windSpeed":"5 mph","shortForecast":"Mostly Clear"}]}}"""
    }
}
