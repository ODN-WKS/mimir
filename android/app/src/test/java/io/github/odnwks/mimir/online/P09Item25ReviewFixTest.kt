package io.github.odnwks.mimir.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.09 item 25, review fixes: private questions stay off the web; bare shopping words do not skip the encyclopedia. */
class P09Item25ReviewFixTest {
    @Test fun privateQuestionsWithMeOrIAreNeverSearched() {
        for (q in listOf(
            "what is the best medication for me, I have depression",
            "can you recommend a good divorce lawyer for me",
            "which laptop should I buy for my kids",
            "what is the best treatment, I have a rash",
            "recommend a good lawyer for us",
        )) {
            assertEquals("'$q' is personal and must not be looked up", null, Router.need(q))
        }
    }

    @Test fun encyclopediaQuestionsWithShoppingWordsStayWithTheEncyclopedia() {
        for (q in listOf(
            "what is the highest mountain in the world",
            "what is the top speed of a cheetah",
            "what is Napoleon vs Wellington",
            "who was the top general of Rome",
            "what is a deal breaker",
        )) {
            val need = Router.need(q)
            assertTrue("'$q' should stay an encyclopedia question, was $need", need is Router.Need.Encyclopedia)
        }
        // "price" is on the not-encyclopedia list: it is at least never sent to the web.
        assertTrue(Router.need("what is a price floor") !is Router.Need.Search)
    }

    @Test fun buyingQuestionsStillGoToTheWeb() {
        for (q in listOf(
            "what is highest rated shaving replacement razor blades",
            "what are the top selling headphones",
            "what is the price of a new kettle",
            "what is the cost of a new roof",
            "where are the best deals on laptops",
            "what is better to buy, iPhone vs Pixel",
        )) {
            assertTrue("'$q' should be a web search, was ${Router.need(q)}", Router.need(q) is Router.Need.Search)
        }
    }
}
