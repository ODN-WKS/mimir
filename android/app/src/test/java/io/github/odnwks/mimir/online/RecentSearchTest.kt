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
            // A note about a person is not a question about the world (PHE-05), pronoun or not.
            "Notes from Dr. Patel visit: new treatment developments for stage 3 lymphoma, follow up Tuesday",
            "Biopsy came back, what happened at the clinic today was scary",
            "Jane Doe HIV positive latest research on PrEP for partner",
            "Who died in the crash on Route 9 last night",
            "Notes from Dr. Patel: new research on lymphoma. Any questions?",
            "Can you keep this: Jane Doe, latest research on PrEP for partner",
        ).forEach { assertNull(it, Router.need(it)) }
    }
}
