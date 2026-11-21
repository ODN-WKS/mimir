package io.github.odnwks.mimir.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The paired chat's To: Auto (owner's screenshot, 2026-11-19): everyday messages are answered
 * directly; only clear project work goes to the PC to be filed.
 */
class EverydayTest {
    private val projects = listOf("Recipe-Box", "Recipe box", "S8 unlock", "General")

    @Test fun theOwnersTwoWeatherMessagesAreWeatherNotAProjectQuestion() {
        assertEquals(Everyday.Route.Weather(null), Everyday.route("good morning what's the weather forecast for today", projects))
        assertEquals(Everyday.Route.Weather("Portland"), Everyday.route("what is the weather forecast for Portland", projects))
    }

    @Test fun questionsAndHellosAreAnsweredDirectly() {
        for (m in listOf("who wrote Hamlet?", "Good morning!", "what is a black hole", "how long do I boil an egg", "Give me three easy dinner ideas", "give me a random fact", "search for cheap flights to Oslo", "Is it a holiday on Monday?")) {
            assertEquals(m, Everyday.Route.Chat, Everyday.route(m, projects))
        }
    }

    @Test fun projectWorkStillGoesToThePc() {
        for (m in listOf(
            "What's the status?", "status", "Idea: a spoken summary every Sunday", "Fix the crash when I open Updates",
            "Recipe-Box: why is the tablet layout slow?", "how is Recipe box doing", "can you make the S8 unlock faster?",
            "Add a dark mode switch to Garden planner's settings", "why does the build fail?", "release 0.21", "@Recipe box what changed?",
            "General: what is a black hole",
        )) {
            assertNull(m, Everyday.route(m, projects))
        }
    }

    @Test fun aProjectNameWithASpaceIsStillAProject() {
        assertNull(Everyday.route("what's left to do in recipe box?", projects))
    }
}
