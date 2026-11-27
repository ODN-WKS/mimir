package io.github.odnwks.mimir.online

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Questions about recent studies or recent events are searched, whoever answers. */
class RecentSearchTest {
    @Test fun recentStudiesAndEventsAreSearched() {
        listOf(
            "what do recent studies say about creatine and memory",
            "What does recent research say about intermittent fasting?",
            "Are there any new studies on coffee and sleep?",
            "tell me about recent studies on magnesium",
            "can you summarize the research on sleep from this year",
            "what do studies show about cold showers",
            "what happened in the election last night",
            "who won the game yesterday",
            "what happened in Paris this week",
            "any news from the space station",
            "what has changed recently in EU battery law",
        ).forEach { assertTrue(it, Router.need(it) is Router.Need.Search) }
    }

    @Test fun privateAndEverydayMessagesStayUnsearched() {
        listOf(
            "I was recently thinking about my job",
            "my recent studies are going badly",
            "what did I do yesterday",
            "write a poem about last night",
            "yesterday was a long day",
            "can you fix this recent change in my code",
        ).forEach { assertNull(it, Router.need(it)) }
    }
}
