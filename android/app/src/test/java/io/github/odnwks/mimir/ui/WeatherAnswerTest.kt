package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.online.Weather
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** To: Auto's weather answer starts with the Yes, Maybe or No for a rain question. */
@RunWith(RobolectricTestRunner::class)
class WeatherAnswerTest {
    private val forecast = JSONObject(
        """{"current":{"temperature_2m":60,"apparent_temperature":58,"weather_code":3,"wind_speed_10m":5,"precipitation":0},
        "daily":{"time":["2026-11-19","2026-11-20","2026-11-21"],"weather_code":[3,61,2],"temperature_2m_max":[62,58,60],
        "temperature_2m_min":[45,44,43],"precipitation_probability_max":[10,70,35],"precipitation_sum":[0,0.4,0.1],"wind_speed_10m_max":[8,12,6]}}""",
    )
    private fun answer(ask: String) = answerFirst(Weather.describe(forecast, "Seattle", true, 0, ask).lines().filter { it.isNotBlank() })

    @Test fun aRainQuestionIsAnsweredFirst() {
        assertEquals("Yes: a 70% chance of rain tomorrow.", answer("Will it rain tomorrow?").first())
        assertEquals("No: a 10% chance of rain today.", answer("will it rain today").first())
        // Only once, and the forecast's details follow.
        assertEquals(1, answer("Will it rain tomorrow?").count { it.startsWith("Yes") || it.startsWith("Start the answer") })
        assert(answer("Will it rain tomorrow?").any { it.startsWith("Tomorrow") })
    }

    @Test fun otherWeatherIsLeftAsItIs() {
        val plain = Weather.describe(forecast, "Seattle", true, 0, "weather tomorrow").lines().filter { it.isNotBlank() }
        assertEquals(plain, answerFirst(plain))
    }
}
