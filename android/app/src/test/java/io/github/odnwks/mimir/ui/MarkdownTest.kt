package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
}
