package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Replies show their Markdown as bold, lists, code and links; read aloud and Copy get plain words. */
@RunWith(RobolectricTestRunner::class)
class MarkdownTest {
    @get:Rule val compose = createComposeRule()

    @Test fun boldIsBoldAndItsMarksAreGone() {
        val s = Markdown.inline("The file says **teal**, not *blue*.")
        assertEquals("The file says teal, not blue.", s.text)
        val bold = s.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("teal", s.text.substring(bold.start, bold.end))
    }

    @Test fun marksWithNoPartnerStayAndSnakeCaseIsNotItalics() {
        assertEquals("2 * 3 = 6 and my_file_name", Markdown.inline("2 * 3 = 6 and my_file_name").text)
        assertEquals("**not closed", Markdown.inline("**not closed").text)
    }

    @Test fun blocksAreHeadingsListsCodeAndParagraphs() {
        val b = Markdown.blocks("# Dinner\n\nThree ideas:\n\n1. **Pasta**\n2. Rice\n- a bullet\n\n```\ncode here\n```\nEnd.")
        assertEquals(Markdown.Block.Heading(1, "Dinner"), b[0])
        assertEquals(Markdown.Block.Para("Three ideas:"), b[1])
        assertEquals(Markdown.Block.Item("1", 0, "**Pasta**"), b[2])
        assertEquals(Markdown.Block.Item(null, 0, "a bullet"), b[4])
        assertEquals(Markdown.Block.Code("code here"), b[5])
        assertEquals(Markdown.Block.Para("End."), b[6])
    }

    @Test fun readAloudAndCopyGetPlainWords() {
        assertEquals("Dinner\nTry 1. Pasta and see docs.", Markdown.plain("## Dinner\nTry `1. Pasta` and see [docs](https://example.com)."))
        assertTrue(!markdownPlain("**teal**").contains("*"))
    }

    @Test fun aReplyShowsNoStars() {
        compose.setContent { MimirTheme { MarkdownText("It mentions **teal** and a [link](https://example.com).", Type.Body, Palette.Text) } }
        compose.onNodeWithText("It mentions teal and a link.").assertExists()
        compose.onNodeWithText("**", substring = true).assertDoesNotExist()
    }

    @Test fun linksOpenInTheAppsBrowserAndAreOnlyTextInAWhisper() {
        val md = "See [the page](https://example.com/a) now."
        val opened = ArrayList<String>()
        val inApp = Markdown.inline(md, links = Markdown.Links.InApp { opened += it })
        val ann = inApp.getLinkAnnotations(0, inApp.length)
        assertEquals(1, ann.size)
        (ann[0].item as androidx.compose.ui.text.LinkAnnotation.Clickable).linkInteractionListener!!.onClick(ann[0].item)
        assertEquals(listOf("https://example.com/a"), opened)
        val plain = Markdown.inline(md, links = Markdown.Links.Plain)
        assertEquals(0, plain.getLinkAnnotations(0, plain.length).size)
        assertEquals("See the page (https://example.com/a) now.", plain.text)
    }

    @Test fun aTableHasHeaderRowsAndRaggedRowsArePadded() {
        val b = Markdown.blocks("Prices:\n\n| Fruit | Price |\n|:--|--:|\n| Apple | 1 |\n| Pear |\n| Fig | 3 | extra |\n\nDone.")
        assertEquals(Markdown.Block.Table(listOf("Fruit", "Price"), listOf(listOf("Apple", "1"), listOf("Pear", ""), listOf("Fig", "3"))), b[1])
        assertEquals(Markdown.Block.Para("Done."), b[2])
        assertEquals("Fruit: Apple, Price: 1", Markdown.plain("| Fruit | Price |\n|---|---|\n| **Apple** | 1 |"))
    }

    @Test fun aPipeInCodeOrProseIsNotATable() {
        assertEquals(listOf(Markdown.Block.Code("a | b\n--|--")), Markdown.blocks("```\na | b\n--|--\n```"))
        assertEquals(listOf(Markdown.Block.Para("use a | b here\nand - then")), Markdown.blocks("use a | b here\nand - then"))
    }

    @Test fun codeKeepsItsLanguageAndAnUnclosedFenceStillWorks() {
        assertEquals(Markdown.Block.Code("print(1)", "python"), Markdown.blocks("```python\nprint(1)\n```")[0])
        assertEquals(Markdown.Block.Code("x = 1\ny", "py"), Markdown.blocks("```py\nx = 1\ny")[0])
    }

    @Test fun aSingleNewlineStaysInAParagraph() {
        assertEquals(listOf(Markdown.Block.Para("Ingredients: a\nSteps: b")), Markdown.blocks("Ingredients: a\nSteps: b"))
    }

    @Test fun twoDigitNumbersAndNestedItemsAreRead() {
        val b = Markdown.blocks("9. Nine\n10. Cats\n  - nested")
        assertEquals(Markdown.Block.Item("10", 0, "Cats"), b[1])
        assertEquals(Markdown.Block.Item(null, 1, "nested"), b[2])
    }

    @Test fun aTableAndCodeShowAndCopyCopiesExactlyTheCode() {
        compose.setContent { MimirTheme { MarkdownText("| A | B |\n|---|---|\n| one | two |\n\n```python\nprint('hi')\n```", Type.Body, Palette.Text) } }
        compose.onNodeWithText("one").assertExists()
        compose.onNodeWithText("python").assertExists()
        compose.onNodeWithText("print('hi')").assertExists()
        compose.onNodeWithContentDescription("Copy this code").performClick()
        val cm = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("print('hi')", cm.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun aTableEndsAtABulletHeadingOrShortLine() {
        val b = Markdown.blocks("| a | b |\n|---|---|\n| 1 | 2 |\n- note: x | y\n# Head\n| 3 | 4 |")
        assertEquals(Markdown.Block.Table(listOf("a", "b"), listOf(listOf("1", "2"))), b[0])
        assertEquals(Markdown.Block.Item(null, 0, "note: x | y"), b[1])
        assertEquals(Markdown.Block.Heading(1, "Head"), b[2])
        val c = Markdown.blocks("| a | b | c |\n|---|---|---|\n| 1 | 2 | 3 |\nplain x | y")
        assertEquals(Markdown.Block.Para("plain x | y"), c[1])
    }

    @Test fun aTableInAQuoteStaysOneReadableQuote() {
        val b = Markdown.blocks("> | a | b |\n> |---|---|\n> | 1 | 2 |")
        assertEquals(listOf(Markdown.Block.Quote("| a | b |\n|---|---|\n| 1 | 2 |")), b)
    }

    @Test fun aBigNumberStaysOnOneLineAtBigFont() {
        val md = (1..100).joinToString("\n") { "$it. tip" }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2f, 2.0f)) {
                MimirTheme { MarkdownText(md, Type.Body, Palette.Text) }
            }
        }
        val n = compose.onNodeWithText("100.").fetchSemanticsNode()
        assertTrue(n.size.height < 2 * (compose.onNodeWithText("9.").fetchSemanticsNode().size.height))
    }
}
