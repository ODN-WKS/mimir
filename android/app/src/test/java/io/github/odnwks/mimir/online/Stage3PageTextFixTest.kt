package io.github.odnwks.mimir.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P21 stage 3, piece A: page reading fixes left from the reviews, each held by a check. */
class Stage3PageTextFixTest {
    private val story = "The council voted on Tuesday to keep the library open on Sundays. " +
        "Readers had asked for longer hours for years, and the new budget pays for two more staff."

    @Test fun aPageWrappedInOneBigFormKeepsItsText() {
        val page = """
            <html><body><form method="post" action="./Default.aspx" id="form1">
            <input type="hidden" name="__VIEWSTATE" value="abc" />
            <div class="content"><h1>Library stays open</h1><div>$story</div><div>$story</div></div>
            </form></body></html>
        """.trimIndent()
        val text = PageText.main(page)
        assertTrue(text, text.contains("Library stays open"))
        assertTrue(text, text.contains("The council voted on Tuesday"))
        // A form with a paragraph is kept even when short.
        assertTrue(PageText.main("<body><form><p>Short words kept.</p></form></body>").contains("Short words kept."))
        // The tapped-page reader keeps it too.
        assertTrue(PageText.of(page).text.contains("The council voted on Tuesday"))
    }

    @Test fun aSmallFormIsStillDropped() {
        val page = "<body><form><label>Email</label><input name=e><button>Sign up</button> Get our newsletter</form><p>$story</p></body>"
        val text = PageText.main(page)
        assertFalse(text, text.contains("newsletter"))
        assertFalse(text, text.contains("Email"))
        assertTrue(text, text.contains("The council voted"))
        assertFalse(PageText.of(page).text.contains("newsletter"))
    }

    @Test fun anArticlesOwnHeaderIsKeptAndThePagesHeaderIsNot() {
        val page = """
            <body><header><p>Site banner text</p><nav><a href="/">Home</a></nav></header>
            <article><header><h1>Library stays open</h1><p class="lead">Sunday hours are safe for another year.</p>
            <nav>Share this</nav></header><p>$story</p></article></body>
        """.trimIndent()
        val text = PageText.main(page)
        assertTrue(text, text.lines().contains("Library stays open"))
        assertTrue(text, text.lines().contains("Sunday hours are safe for another year."))
        assertTrue(text, text.contains("The council voted"))
        assertFalse(text, text.contains("Site banner"))
        assertFalse(text, text.contains("Share this"))
        // A hidden header inside the article still goes.
        assertFalse(PageText.main("<article><header hidden><p>Secret lead.</p></header><p>$story</p></article>").contains("Secret lead"))
    }

    @Test fun aQuotedGreaterThanSignCannotUnhideText() {
        val text = PageText.main("""<body><div data-a="x>" style="display:none">hidden words</div><p>Shown words.</p></body>""")
        assertEquals("Shown words.", text)
        assertEquals("Shown words.", PageText.main("""<body><div title='a > b' hidden>hidden words</div><p>Shown words.</p></body>"""))
        assertEquals("Shown words.", PageText.main("""<body><span data-x="1>2" aria-hidden="true">hidden words</span><p>Shown words.</p></body>"""))
    }

    @Test fun manyUnclosedTagsAreReadInOnePass() {
        PageText.main("<body><nav><nav><p>warm</p></body>")
        val html = "<body><p>Start words.</p>" + "<nav>".repeat(40_000) + "</body>"
        val start = System.nanoTime()
        PageText.main(html)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 300)
        // Unclosed divs and articles walk in one pass too.
        val divs = "<body>" + "<div>".repeat(20_000) + "<article>".repeat(20_000) + "<p>Deep words.</p></body>"
        val t2 = System.nanoTime()
        assertTrue(PageText.main(divs).contains("Deep words."))
        assertTrue("took ${(System.nanoTime() - t2) / 1_000_000} ms", (System.nanoTime() - t2) / 1_000_000 < 300)
    }

    @Test fun theDateComesFromTheArticleNotTheSidebar() {
        val page = """
            <body><aside><time datetime="2024-03-01">Most read</time></aside>
            <div class="related"><time datetime="2023-05-05">Old story</time></div>
            <article><h1>Library stays open</h1><p>Published <time datetime="2026-11-20T09:00:00Z">20 November</time></p><p>$story</p></article></body>
        """.trimIndent()
        assertEquals("2026-11-20", PageText.date(page))
        // With no article, the first time on the page still counts.
        assertEquals("2025-01-09", PageText.date("""<p>Posted <time datetime="2025-01-09">9 January</time></p><time datetime="2024-01-01">old</time>"""))
    }

    @Test fun impossibleDatesAreRejected() {
        assertNull(PageText.date("""<script>{"datePublished":"2026-02-31"}</script>"""))
        assertNull(PageText.date("""<meta property="article:published_time" content="2025-02-29">"""))
        assertNull(PageText.date("""<time datetime="2026-04-31">x</time>"""))
        assertEquals("2024-02-29", PageText.date("""<time datetime="2024-02-29">leap day</time>"""))
        // An impossible first form gives way to the next one.
        assertEquals("2026-11-02", PageText.date("""<script>{"datePublished":"2026-02-31"}</script><time datetime="2026-11-02">x</time>"""))
    }

    @Test fun unitConversionsAnswerAndPricesSearch() {
        assertNull(Router.need("How much is a kilometre in miles?"))
        assertNull(Router.need("how much are 3 dozen eggs in grams"))
        assertNull(Router.need("how much is a cup in millilitres"))
        assertNull(Router.need("how much is 3 kg in pounds"))
        assertTrue(Router.need("how much is a Tesla Model 3") is Router.Need.Search)
        assertTrue(Router.need("how much is a gallon of gas today") is Router.Need.Search)
        // Money changes with the day: those still search.
        assertTrue(Router.need("how much is 100 dollars in pounds") is Router.Need.Search)
        assertTrue(Router.need("how much is 50 euros in dollars") is Router.Need.Search)
        assertTrue(Router.need("how much is a litre of milk in Canada") is Router.Need.Search)
    }
}
