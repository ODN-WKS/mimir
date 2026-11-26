package io.github.odnwks.mimir.online

import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner-found 2026-11-25: a town and "CA" gave a same-named town in Canada, and "<town> California" found nothing. */
class WeatherPlaceTest {
    @Test fun theStateOrCountryTypedIsKeptApartFromTheTown() {
        assertEquals("Springfield" to "IL", Weather.split("Springfield IL"))
        assertEquals("Fresno" to "California", Weather.split("Fresno California"))
        assertEquals("Paris" to "Texas", Weather.split("Paris, Texas"))
        assertEquals("Oslo" to "Norway", Weather.split("Oslo, Norway"))
        assertEquals("Salt Lake City" to "Utah", Weather.split("Salt Lake City Utah"))
        assertEquals("Albany" to "New York", Weather.split("Albany New York"))
        assertEquals("Leeds" to null, Weather.split("Leeds"))
        // A lower-case "ca" is a word, not a state code; "New York" alone is the city.
        assertEquals("New York" to null, Weather.split("New York"))
    }

    @Test fun theOwnersFollowUpsAreUnderstood() {
        // Owner-found 2026-11-25: "search" alone, and "what about Fresno California" after a weather question.
        assertEquals(Router.Need.Search("what is the most unethical AI model available to the public"),
            Router.followUp("search", "what is the most unethical AI model available to the public"))
        assertEquals(Router.Need.Search("what is the most unethical AI model available to the public"),
            Router.followUp("Look it up", "what is the most unethical AI model available to the public?"))
        assertEquals(Router.Need.Weather("Fresno California"), Router.followUp("what about Fresno California", "springfield IL weather"))
        assertEquals(Router.Need.Weather("Oslo"), Router.followUp("and in Oslo?", "weather in Paris tomorrow"))
        // Not a follow-up: no earlier question, or the earlier one was not about the weather.
        assertEquals(null, Router.followUp("search", null))
        assertEquals(null, Router.followUp("what about Fresno California", "tell me a joke"))
        assertEquals(null, Router.followUp("search engines are interesting", "tell me a joke"))
    }
}
