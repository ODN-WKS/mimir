package io.github.odnwks.mimir.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Choosing the passages the model reads, by what the pages say. */
class RankTest {
    private fun page(site: String, text: String?, title: String = "A page", snippet: String = "", date: String? = null) =
        PageRead(Search.Result(title, "https://$site/page", snippet, listOf("brave")), text, date)

    private fun words(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }

    /** About 60 words on a theme, no answer terms, ending at a sentence end. */
    private fun filler(topic: String, seed: Int) = (1..4).joinToString(" ") { i ->
        "Paragraph $seed part $i talks about $topic in general terms without the details anyone wanted here today."
    }

    private val answer = "The Garden planner app costs nine euros a month for the family plan, and the yearly plan " +
        "brings the monthly price down for families who keep it longer than a season. Prices were set in spring and " +
        "have not changed since then, according to the makers of the planner."

    @Test fun thePassageWithTheAnswerTermsComesFirst() {
        val text = filler("weather", 1) + "\n" + filler("soil", 2) + "\n" + answer + "\n" + filler("seeds", 3)
        val chosen = Rank.choose("what does the garden planner family plan cost?", "garden planner family plan price",
            listOf(page("planner.example", text)))
        assertTrue(chosen.isNotEmpty())
        assertTrue(chosen.first().quote, chosen.first().quote.contains("nine euros"))
        assertEquals(1, chosen.first().n)
        assertEquals("planner.example", chosen.first().site)
        assertEquals("https://planner.example/page", chosen.first().url)
    }

    @Test fun aNearCopyFromASecondSiteIsSkipped() {
        val copy = answer.replace("according to the makers of the planner.", "according to the makers of this planner.")
        val other = "Families pick the garden planner plan for its shared lists and reminders, and many use it with children " +
            "who like ticking off jobs. The family plan adds up to six people, and each gets their own list of garden jobs " +
            "for the week ahead without any extra cost."
        val chosen = Rank.choose("garden planner family plan price", "garden planner family plan price",
            listOf(page("a.example", answer), page("b.example", copy), page("c.example", other)), budgetWords = 400)
        assertEquals(setOf("a.example", "c.example"), chosen.map { it.site }.toSet())
        assertEquals(listOf(1, 2), chosen.map { it.n })
    }

    @Test fun atMostTwoPassagesFromOneSite() {
        // Five different notes (made-up words keep them from looking like copies of each other).
        val text = (1..5).joinToString("\n") { i ->
            "The garden planner family plan helps. " + (1..50).joinToString(" ") { k -> "w${i}x$k" } + "."
        }
        val chosen = Rank.choose("garden planner family plan", "garden planner family plan",
            listOf(page("one.example", text), page("two.example", null, snippet = "The garden planner family plan, reviewed.")), budgetWords = 2000)
        assertEquals(2, chosen.count { it.site == "one.example" })
        assertTrue(chosen.any { it.site == "two.example" })
        // One site keeps one number, and its passages sit together.
        assertEquals(chosen.sortedBy { it.n }, chosen)
        assertEquals(1, chosen.filter { it.site == "one.example" }.map { it.n }.toSet().size)
    }

    @Test fun theWordBudgetIsRespected() {
        val pages = (1..6).map { i ->
            page("s$i.example", "Garden planner review $i: the family plan $i works well for site $i readers who plan beds $i. " +
                "Reviewer $i liked reminders $i, lists $i and the calendar $i for jobs $i. " + filler("planner $i", i))
        }
        for (budget in listOf(80, 150, 200)) {
            val chosen = Rank.choose("garden planner family plan", "garden planner", pages, budgetWords = budget)
            assertTrue(chosen.isNotEmpty())
            assertTrue("$budget", chosen.sumOf { words(it.quote) } <= budget)
        }
    }

    @Test fun anUnreadPagesSnippetStillCounts() {
        val chosen = Rank.choose("recipe box sync price", "recipe box price",
            listOf(page("read.example", filler("cooking", 1)), page("unread.example", null, title = "Recipe box",
                snippet = "Recipe box costs 3 euros a month.", date = "2026-11-02")))
        assertEquals("unread.example", chosen.first().site)
        assertEquals("Recipe box costs 3 euros a month.", chosen.first().quote)
        assertEquals("2026-11-02", chosen.first().date)
        assertEquals("Recipe box", chosen.first().title)
    }

    @Test fun aNumbersQuestionPrefersThePassageWithDigits() {
        val withDigits = "The recipe box subscription renews each month at 4 euros, billed on the first day. Cancel any time from the app settings page."
        val noDigits = "The recipe box subscription renews each month at four euros, billed on the first day. Cancel any time from the app settings page."
        val pages = listOf(page("a.example", noDigits), page("b.example", withDigits))
        assertEquals("b.example", Rank.choose("how much is the recipe box subscription?", "recipe box subscription", pages).first().site)
        // Without a numbers question the list order stands (equal text scores).
        assertEquals("a.example", Rank.choose("tell me about the recipe box subscription", "recipe box subscription", pages).first().site)
    }

    @Test fun nothingMatchingGivesNothing() {
        assertTrue(Rank.choose("quantum gravity", "quantum gravity", listOf(page("a.example", filler("cooking", 1)))).isEmpty())
        assertFalse(Rank.choose("cooking", "cooking", listOf(page("a.example", filler("cooking", 1)))).isEmpty())
    }
}
