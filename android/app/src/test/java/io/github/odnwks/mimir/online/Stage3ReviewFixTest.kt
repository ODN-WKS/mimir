package io.github.odnwks.mimir.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search stage 3 review (2026-11-26): hostile pages stay fast, prices still search, "in go" alone is not code. */
class Stage3ReviewFixTest {
    private fun hostile(piece: String): String {
        val body = StringBuilder("<html><body><main><p>Real words here.</p>")
        while (body.length < 100_000) body.append(piece)
        return body.toString()
    }

    private fun timed(what: String, block: () -> Unit) {
        val t0 = System.nanoTime()
        block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$what took $ms ms", ms < 1_500)
    }

    @Test fun manyTagsThatNeverCloseAreFast() {
        timed("main() on '<a ' x 33k") { PageText.main(hostile("<a ")) }
        timed("main() on '<a x=\"1\"' x 10k") { PageText.main(hostile("<a x=\"1\"")) }
        timed("date() on '<a ' x 33k") { PageText.date(hostile("<a ")) }
    }

    @Test fun manyFormsThatNeverCloseAreFast() {
        timed("of() on '<form ' x 16k") { PageText.of(hostile("<form ")) }
        timed("of() on '<form>' x 16k") { PageText.of(hostile("<form>")) }
    }

    /** Every kind of part that never closes, through all three readers (the first fix covered "<a " and "<form" only). */
    @Test fun partsThatNeverCloseAreFast() {
        val pieces = listOf(
            "<nav>", "<nav ", "<script>", "<style>x", "<p ", "<p>", "<div ", "<title>", "<main>", "<article>", "<!--", "<![CDATA[",
            "<time ", "<time>", "<meta ", "</nav", "</nav x", "<form><a ", "<b", "< ", "<button>ok", "<header><nav>",
        )
        for (piece in pieces) {
            val page = hostile(piece)
            timed("of() on '$piece'") { PageText.of(page) }
            timed("main() on '$piece'") { PageText.main(page) }
            timed("date() on '$piece'") { PageText.date(page) }
            // The same piece with one ">" at the very end: every "<" now has a ">" after it.
            timed("of() on '$piece' then '>'") { PageText.of("$page>") }
            timed("date() on '$piece' then '>'") { PageText.date("$page>") }
        }
    }

    @Test fun theOnePassReadersKeepWhatThePatternsKept() {
        fun text(html: String) = PageText.of(html).text
        // Menus, scripts and comments go; a closing tag may hold a space; a longer name is not the short one.
        val page = text("<html><body><p>Kept one.</p><nav><a href=x>Menu</a></nav ><script>var a = '<p>no</p>';</script><!-- note --><navigation>Kept two.</navigation><p>Kept three.</p></body></html>")
        assertTrue(page, page.contains("Kept one.") && page.contains("Kept two.") && page.contains("Kept three."))
        assertFalse(page, page.contains("Menu") || page.contains("var a") || page.contains("note"))
        // A menu that never closes stays as text, as before; the one after it that closes still goes.
        val open = text("<html><body><p>Lead.</p><aside>Side words <p>More.</p><footer>Foot</footer></body></html>")
        assertTrue(open, open.contains("Side words") && open.contains("More."))
        assertFalse(open, open.contains("Foot"))
        // The first main or article that closes is the one read; the title loses its tags.
        val marked = PageText.of("<html><head><title>A <b>bold</b> title</title></head><body><main>Never closed <article><p>The article text.</p></article><p>Outside.</p></body></html>")
        assertTrue(marked.text, marked.text.contains("The article text."))
        assertFalse(marked.text, marked.text.contains("Outside."))
        assertTrue(marked.title, marked.title == "A bold title")
        // A stray "<" after the last tag stays as text.
        val tail = text("<html><body><p>A <b>bold</b> word.</p> 1 < 2 < 3")
        assertTrue(tail, tail.contains("A bold word.") && tail.contains("1 < 2 < 3"))
    }

    @Test fun thePageDateIsStillFound() {
        assertTrue(PageText.date("<html><head><meta property=\"article:published_time\" content=\"2026-11-02T08:00:00Z\"></head><body><p>Text.</p></body></html>") == "2026-11-02")
        assertTrue(PageText.date("<html><body><aside><time datetime=\"2020-01-01\">old</time></aside><article><p>Text.</p><time class=\"d\" datetime='2026-10-30'>30 Oct</time></article></body></html>") == "2026-10-30")
        assertTrue(PageText.date("<html><body><p>Text.</p><time>no date</time><TIME DateTime=\"2026-09-09\">x</TIME></body></html>") == "2026-09-09")
        assertTrue(PageText.date("<html><body><script>var t = '<time datetime=\"2001-01-01\">';</script><p>Text.</p></body></html>") == null)
    }

    @Test fun formsAreStillHandledByTheTappedPageReader() {
        val small = PageText.of("<html><body><p>Kept text for the reader.</p><form><input> Search this site</form></body></html>").text
        assertTrue(small, small.contains("Kept text"))
        assertFalse(small, small.contains("Search this site"))
        val big = "<html><body><form id=\"aspnet\"><p>" + "The whole article sits inside one form. ".repeat(10) + "</p></form></body></html>"
        assertTrue(PageText.of(big).text.contains("The whole article sits inside one form."))
        val near = PageText.of("<html><body><formation>Rock formation notes.</formation><p>More.</p></body></html>").text
        assertTrue(near, near.contains("Rock formation notes."))
    }

    @Test fun pricesInUnitsStillSearch() {
        assertTrue(Router.need("how much is a kilo of gold in pounds") is Router.Need.Search)
        assertTrue(Router.need("how much is an ounce of silver in grams") is Router.Need.Search)
        assertTrue(Router.need("how much is a barrel of oil in litres") is Router.Need.Search)
        assertTrue(Router.need("How much is a kilometre in miles?") == null)
    }

    @Test fun shortLanguageNamesNeedAProgrammingWord() {
        assertFalse(Lane.Code in Lanes.lanesFor("how can I get there in go", "get there in go"))
        assertFalse(Lane.Code in Lanes.lanesFor("how to say hello in r", "say hello in r"))
        assertTrue(Lane.Code in Lanes.lanesFor("how do I parse json in go", "parse json in go"))
        assertTrue(Lane.Code in Lanes.lanesFor("how do I read a file in c", "read a file in c"))
        assertTrue(Lane.Code in Lanes.lanesFor("how do I sort a list in python", "sort a list in python"))
    }
}
