package io.github.odnwks.mimir.online

import io.github.odnwks.mimir.data.GroundRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.09 item 25: product, rating and price questions go to the web search; encyclopedia ones stay. */
class P09Item25LookupTest {
    private val shopping = listOf(
        "what is highest rated shaving replacement razor blades",
        "what are the best running shoes",
        "which laptop should I buy",
        "what's the top rated vacuum cleaner",
        "is it worth buying an air fryer",
        "can you recommend a good tent",
        "what is the cheapest 4k monitor",
        "best budget headphones?",
        "what are the reviews of the Pixel 9",
        "who sells the most popular espresso machine",
        "what is the price of a new kettle",
    )
    private val encyclopedia = listOf(
        "what is a black hole",
        "who was Ada Lovelace",
        "tell me about the Roman Empire",
        "what is photosynthesis",
        "who was Napoleon",
        "what are tardigrades",
        "what is the Pythagorean theorem",
        "who were the Vikings",
        "what is jazz",
        "what is the Great Barrier Reef",
    )

    @Test fun p09Item25ProductQuestionsGoToTheWebSearchFirst() {
        for (q in shopping) {
            val need = Router.need(q)
            assertTrue("'$q' should be a web search, was $need", need is Router.Need.Search)
        }
        assertEquals(
            "what is highest rated shaving replacement razor blades",
            (Router.need("what is highest rated shaving replacement razor blades") as Router.Need.Search).words,
        )
    }

    @Test fun p09Item25EncyclopediaQuestionsStayWithTheEncyclopedia() {
        for (q in encyclopedia) {
            val need = Router.need(q)
            assertTrue("'$q' should stay an encyclopedia question, was $need", need is Router.Need.Encyclopedia)
        }
    }

    @Test fun p09Item25PrivateShoppingIsNotSearched() {
        assertEquals(null, Router.need("which razor is best for my skin"))
    }

    @Test fun p09Item25TheRulesTellTheModelsMimirLooksThingsUp() {
        assertTrue(GroundRules.DEFAULT.contains("Mímir looks things up for you"))
        assertTrue(GroundRules.DEFAULT.contains("Never say you cannot look things up"))
        assertTrue(GroundRules.isOldDefault(GroundRules.DEFAULT.removeSuffix(" " + GroundRules.LOOKUPS)))
    }
}
