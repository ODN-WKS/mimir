package io.github.odnwks.mimir.online

import io.github.odnwks.mimir.data.ChatEntry
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.ui.weatherWords
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lookups must just work (owner, 2026-11-19, after "what's the weather in Portland" answered "Read
 * timed out" on mobile data): the weather and the encyclopedias are asked from this phone and
 * through the PC at once, the first good answer wins, a lookup never takes much more than 6 s,
 * the error says the real cause, and answers are kept a while.
 */
@RunWith(RobolectricTestRunner::class)
class LookupRoutesTest {
    private val web = FakeWeb()
    private val pcAsked = AtomicInteger()

    /** The PC's way, answering like the fake internet does (or as told). */
    private fun pc(delayMs: Long = 0, fail: Exception? = null) {
        val direct = Net.transport
        Net.viaPc = { r ->
            pcAsked.incrementAndGet()
            Thread.sleep(delayMs)
            fail?.let { throw it }
            assertTrue("the PC was asked for ${r.url}", Net.pcMay(r.url))
            direct(r)
        }
    }

    /** This phone's own way: slow, failing, or as the fake internet. */
    private fun phone(delayMs: Long = 0, fail: Exception? = null) {
        val fake = Net.transport
        Net.transport = { r ->
            Thread.sleep(delayMs)
            fail?.let { throw it }
            fake(r)
        }
    }

    @Before fun setUp() {
        web.install()
        web.geocode = """{"results":[{"name":"Portland","admin1":"Oregon","country":"United States","latitude":45.5152,"longitude":-122.6784}]}"""
        Online.set(Online.Settings())
        LookupLog.clear()
    }

    @After fun tearDown() {
        Net.viaPc = null
        Online.set(Online.Settings())
    }

    private fun timed(f: () -> Unit): Long {
        val t0 = System.currentTimeMillis()
        f()
        return System.currentTimeMillis() - t0
    }

    @Test fun theOwnersExactMessageIsAnsweredWhenThePhonesWayTimesOut() {
        assertEquals(Everyday.Route.Weather("Portland"), Everyday.route("what's the weather in Portland", listOf("Recipe-Box", "General")))
        pc()
        phone(delayMs = 1_500, fail = SocketTimeoutException("Read timed out"))
        var words = emptyList<String>()
        val took = timed { words = weatherWords("Portland", null) }
        assertTrue(words.joinToString("\n"), words.first().startsWith("Weather for Portland, Oregon, United States"))
        // The PC's answer is used as soon as it comes, not after the phone gives up.
        assertTrue("took $took ms", took < 1_400)
        assertTrue(LookupLog.entries.value.any { it.route == LookupLog.Route.Pc && it.bytes > 0 })
    }

    @Test fun whenThePcIsSlowOrAwayThePhonesOwnWayAnswers() {
        pc(delayMs = 3_000)
        val took = timed { assertTrue(weatherWords("Portland", null).first().startsWith("Weather for Portland")) }
        assertTrue("took $took ms", took < 2_000)
        // With the PC away (not connected), the phone's way is simply the only one.
        Net.forget()
        pc(fail = Net.PcAway())
        assertTrue(weatherWords("Portland", null).first().startsWith("Weather for Portland"))
        assertTrue(LookupLog.entries.value.first().route == LookupLog.Route.Phone)
    }

    @Test fun whenBothWaysFailTheMessageSaysWhyAndOffersTryAgain() {
        phone(fail = java.net.UnknownHostException("geocoding-api.open-meteo.com"))
        pc(fail = Net.PcAway())
        Net.phoneOnline = { false }
        val e = runCatching { weatherWords("Portland", null) }.exceptionOrNull() ?: return fail("no failure")
        assertTrue(e is Net.Failed)
        val said = Net.sorry(e)
        // The phone's own missing signal is the whole story, never "and your PC is away" too (QA I1).
        assertTrue(said, "this phone has no signal" in said && "your PC" !in said)
        assertFalse(said, "timed out" in said.lowercase())
        // The buttons under the answer say what to do next; the words never repeat "Try again" (Q2).
        assertFalse(said, "try again" in said.lowercase())

        // In the paired chat: the answer says so, with "Try again", which asks again in its place.
        val station = FakeStation()
        val tries = AtomicInteger()
        station.answerHere("what's the weather in Portland", "Weather for Portland…") {
            if (tries.incrementAndGet() == 1) weatherWords("Portland", null) else listOf("Weather for Portland: sunny.")
        }
        val failed = waitFor(station) { it.retry }
        assertTrue(failed.paragraphs.single(), failed.paragraphs.single().startsWith("Couldn't look that up just now (this phone has no signal)"))
        station.retryAnswer(failed.id)
        val again = waitFor(station) { !it.retry && it.playLabel.isNotEmpty() }
        assertEquals(listOf("Weather for Portland: sunny."), again.paragraphs)
    }

    @Test fun aSlowSiteSaysSoInPlainWordsAndNoLookupOutlastsItsBudget() {
        phone(delayMs = 5_000)
        pc(delayMs = 5_000)
        var e: Throwable? = null
        val took = timed { e = runCatching { Net.within(1_000) { weatherWords("Portland", null) } }.exceptionOrNull() }
        assertTrue("took $took ms", took < 1_800)
        val said = Net.sorry(e!!)
        assertTrue(said, "too slow" in said)
        // The whole budget is 6 s, with about 2.5 s to connect and the rest of the 6 s to read.
        assertEquals(6_000L, Net.BUDGET_MS)
        assertEquals(2_500, Net.CONNECT_MS)
        assertEquals(6_000, Net.READ_MS)
    }

    @Test fun withOnlyThePhonesWayAQuickFailureIsTriedOnceMore() {
        val calls = AtomicInteger()
        val fake = Net.transport
        Net.transport = { r ->
            if ("geocoding-api" in r.url.host && calls.incrementAndGet() == 1) throw java.net.ConnectException("refused") else fake(r)
        }
        assertTrue(weatherWords("Portland", null).first().startsWith("Weather for Portland"))
        assertEquals(2, calls.get()) // the place twice (one retry); the forecast was asked once, from several sources
    }

    @Test fun theWeatherIsKeptFifteenMinutesAndArticlesADay() {
        var now = 1_800_000_000_000L
        Net.clock = { now }
        weatherWords("Portland", null)
        val first = web.asked.size
        weatherWords("Portland", null)
        assertEquals("asked again within 15 min", first, web.asked.size)
        now += 16 * 60_000L
        weatherWords("Portland", null)
        // The forecast is asked again; the place is still kept (a day).
        assertEquals(first + 1, web.asked.size)
        assertTrue(web.asked.last().contains("api.open-meteo.com/v1/forecast"))

        Online.set(Online.Settings(encyclopedias = Encyclopedias.Wikipedia))
        Encyclopedia.look("black hole", Encyclopedias.Wikipedia, grok = false, ChatMode.Normal)
        val read = web.asked.size
        now += 23 * 3_600_000L
        Encyclopedia.look("black hole", Encyclopedias.Wikipedia, grok = false, ChatMode.Normal)
        assertEquals(read, web.asked.size)
        now += 2 * 3_600_000L
        Encyclopedia.look("black hole", Encyclopedias.Wikipedia, grok = false, ChatMode.Normal)
        assertTrue(web.asked.size > read)
    }

    @Test fun thePcIsOnlyAskedForItsFixedSitesAndAPageIsNeverSentThroughIt() {
        pc()
        Net.get("https://news.example.com/story", ChatMode.Normal, words = "x", accept = setOf("text/html"))
        assertEquals(0, pcAsked.get())
        for (u in listOf("http://api.open-meteo.com/v1/forecast", "https://api.open-meteo.com:444/v1/forecast", "https://evil.example/v1/forecast", "https://en.wikipedia.org/wiki/X"))
            assertFalse(u, Net.pcMay(java.net.URL(u)))
        // The same list as the PC's own.
        val pcList = File("../../crates/core/src/lookup.rs").readText()
        for ((host, path) in Net.PC_SITES) assertTrue(host, pcList.contains("(\"$host\", \"$path\")"))
    }

    @Test fun moreEverydayWaysOfAskingAreWeatherQuestions() {
        assertEquals(Router.Need.Weather("Portland"), Router.need("what's the weather in Portland"))
        assertEquals(Router.Need.Weather("Portland"), Router.need("Portland weather"))
        assertEquals(Router.Need.Weather("Portland"), Router.need("weather Portland?"))
        assertEquals(Router.Need.Weather("Portland"), Router.need("how cold is it in Portland"))
        assertEquals(Router.Need.Weather("Portland"), Router.need("temperature in Portland"))
        assertEquals(Router.Need.Weather(null), Router.need("is it going to rain tomorrow?"))
        assertEquals(Router.Need.Weather(null), Router.need("what's the weather like"))
        assertEquals(Router.Need.Weather(null), Router.need("how's the weather today"))
        assertTrue(Router.need("what temperature in the oven for chicken") !is Router.Need.Weather)
    }

    private fun waitFor(station: FakeStation, ok: (ChatEntry.Reply) -> Boolean): ChatEntry.Reply {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            station.state.value.chat.filterIsInstance<ChatEntry.Reply>().lastOrNull()?.takeIf(ok)?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("no such answer: ${station.state.value.chat.lastOrNull()}")
    }
}
