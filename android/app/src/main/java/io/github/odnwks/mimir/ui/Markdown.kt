package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/**
 * The Markdown models write (bold, italics, lists, code, headings, links, quotes), shown as such
 * instead of with its marks. Read aloud and Copy use [markdownPlain], the words without the marks.
 */
object Markdown {
    /** What a [text](https://…) link does: opens through the system, opens in the app's own browser, or is only text (a Whisper). */
    sealed interface Links {
        data object System : Links
        data object Plain : Links
        class InApp(val open: (String) -> Unit) : Links
    }

    sealed interface Block {
        data class Para(val text: String) : Block
        data class Heading(val level: Int, val text: String) : Block
        /** One list item; [number] is null for a bullet. */
        data class Item(val number: String?, val depth: Int, val text: String) : Block
        data class Quote(val text: String) : Block
        data class Code(val text: String, val lang: String = "") : Block
        /** A table: the header cells and the rows, each row padded or cut to the header's width. */
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block
        data object Rule : Block
    }

    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < t.length) {
            if (t[i] == '\\' && i + 1 < t.length && t[i + 1] == '|') { cur.append('|'); i += 2; continue }
            if (t[i] == '|') { out += cur.toString().trim(); cur.clear() } else cur.append(t[i])
            i++
        }
        out += cur.toString().trim()
        return out
    }

    /** A line that can't be a table row: another kind of block, or a short line with no leading bar. */
    private fun endsTable(line: String, width: Int): Boolean {
        val t = line.trim()
        return t.startsWith("```") || t.startsWith(">") || heading.matches(t) || bullet.matches(line) || numbered.matches(line) || rule.matches(line) ||
            (!t.startsWith("|") && cells(line).size < width)
    }

    private fun isTableStart(lines: List<String>, i: Int): Boolean =
        i + 1 < lines.size && lines[i].contains('|') && lines[i + 1].contains('-') && lines[i + 1].contains('|') && tableSep.matches(lines[i + 1]) &&
            cells(lines[i]).size == cells(lines[i + 1]).size

    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    /** The text's blocks, in order. An unclosed code fence (a reply still arriving) runs to the end. */
    fun blocks(md: String): List<Block> {
        val out = mutableListOf<Block>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
        val lines = md.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> {
                    flush()
                    val lang = t.removePrefix("```").trim().takeWhile { !it.isWhitespace() }
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { if (code.isNotEmpty()) code.append('\n'); code.append(lines[i]); i++ }
                    out += Block.Code(code.toString(), lang)
                }
                t.isEmpty() -> flush()
                isTableStart(lines, i) -> {
                    flush()
                    val head = cells(line)
                    val rows = mutableListOf<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|') && !endsTable(lines[i], head.size)) {
                        rows += cells(lines[i]).let { r -> List(head.size) { c -> r.getOrElse(c) { "" } } }
                        i++
                    }
                    out += Block.Table(head, rows)
                    continue
                }
                rule.matches(line) && para.isEmpty() -> { flush(); out += Block.Rule }
                heading.matches(t) -> { flush(); heading.find(t)!!.let { out += Block.Heading(it.groupValues[1].length, it.groupValues[2]) } }
                bullet.matches(line) -> { flush(); bullet.find(line)!!.let { out += Block.Item(null, it.groupValues[1].length / 2, it.groupValues[2]) } }
                numbered.matches(line) -> { flush(); numbered.find(line)!!.let { out += Block.Item(it.groupValues[2], it.groupValues[1].length / 2, it.groupValues[3]) } }
                // A run of quote lines is one quote (a table inside it stays readable as its own lines).
                t.startsWith(">") -> {
                    flush()
                    val q = t.removePrefix(">").trim()
                    val last = out.lastOrNull()
                    if (last is Block.Quote && lines.getOrNull(i - 1)?.trim()?.startsWith(">") == true) out[out.lastIndex] = Block.Quote(last.text + "\n" + q)
                    else out += Block.Quote(q)
                }
                // A line under a list item that isn't a new item carries on that item.
                out.lastOrNull() is Block.Item && para.isEmpty() && line.startsWith(" ") ->
                    (out.last() as Block.Item).let { out[out.lastIndex] = it.copy(text = it.text + " " + t) }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    private val linkPattern = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")

    /**
     * One line's inline marks: **bold**, __bold__, *italic*, _italic_, ~~struck~~, `code` and
     * [links](https://...). A mark with no partner is shown as written.
     */
    fun inline(text: String, code: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace), link: SpanStyle = SpanStyle(textDecoration = TextDecoration.Underline), links: Links = Links.System): AnnotatedString = buildAnnotatedString {
        var i = 0
        fun closes(mark: String, from: Int): Int {
            val end = text.indexOf(mark, from)
            return if (end > from) end else -1
        }
        while (i < text.length) {
            val rest = text.substring(i)
            val m = linkPattern.matchAt(text, i)
            when {
                m != null && (m.groupValues[2].startsWith("http://") || m.groupValues[2].startsWith("https://")) -> {
                    val url = m.groupValues[2]
                    when (links) {
                        // In a Whisper nothing opens: the words and the address, as plain text.
                        Links.Plain -> append(inline(m.groupValues[1], code, link, links)).also { append(" ($url)") }
                        is Links.InApp -> withLink(LinkAnnotation.Clickable(url, TextLinkStyles(link)) { links.open(url) }) { append(inline(m.groupValues[1], code, link, links)) }
                        Links.System -> withLink(LinkAnnotation.Url(url, TextLinkStyles(link))) { append(inline(m.groupValues[1], code, link, links)) }
                    }
                    i += m.value.length
                }
                rest.startsWith("`") && closes("`", i + 1) > 0 -> {
                    val end = closes("`", i + 1)
                    withStyle(code) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
                (rest.startsWith("**") || rest.startsWith("__")) && closes(rest.take(2), i + 2) > 0 -> {
                    val end = closes(rest.take(2), i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, end), code, link, links)) }
                    i = end + 2
                }
                rest.startsWith("~~") && closes("~~", i + 2) > 0 -> {
                    val end = closes("~~", i + 2)
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inline(text.substring(i + 2, end), code, link, links)) }
                    i = end + 2
                }
                (rest.startsWith("*") || (rest.startsWith("_") && (i == 0 || !text[i - 1].isLetterOrDigit()))) &&
                    rest.length > 1 && !rest[1].isWhitespace() && closes(rest.take(1), i + 1) > 0 -> {
                    val mark = rest.take(1)
                    val end = closes(mark, i + 1)
                    // A snake_case word isn't italics.
                    if (mark == "_" && end + 1 < text.length && text[end + 1].isLetterOrDigit()) { append(text[i]); i++ }
                    else {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(text.substring(i + 1, end), code, link, links)) }
                        i = end + 1
                    }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }

    /** The words without the marks: for read aloud and Copy. */
    fun plain(md: String): String = blocks(md).joinToString("\n") { b ->
        when (b) {
            is Block.Para -> inline(b.text).text
            is Block.Heading -> inline(b.text).text
            is Block.Item -> "  ".repeat(b.depth) + (if (b.number != null) "${b.number}. " else "• ") + inline(b.text).text
            is Block.Quote -> inline(b.text).text
            is Block.Code -> b.text
            is Block.Table -> b.rows.joinToString("\n") { r -> b.header.indices.joinToString(", ") { c -> inline(b.header[c]).text + ": " + inline(r[c]).text } }
            Block.Rule -> ""
        }
    }.trim()
}

/** The words of a reply, without its Markdown marks (read aloud and Copy). */
fun markdownPlain(md: String): String = Markdown.plain(md)

/** A reply's Markdown, shown with its bold, lists, code and links. */
@Composable
fun MarkdownText(md: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, linksOpen: Boolean = true) {
    // A link opens in the app's own browser (never the outside one); in a Whisper it is only text (PHU-08).
    val open = LocalOpenPage.current
    val links = when {
        !linksOpen -> Markdown.Links.Plain
        open != null -> Markdown.Links.InApp(open)
        else -> Markdown.Links.System
    }
    val blocks = remember(md) { Markdown.blocks(md) }
    val codeSpan = SpanStyle(fontFamily = FontFamily.Monospace, background = Palette.Bar)
    val link = SpanStyle(color = Palette.BlueText, textDecoration = TextDecoration.Underline)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Markdown.Block.Para -> Text(Markdown.inline(b.text, codeSpan, link, links), style = style, color = color)
                is Markdown.Block.Heading -> Text(
                    Markdown.inline(b.text, codeSpan, link, links),
                    style = style.copy(fontWeight = FontWeight.Bold, fontSize = style.fontSize * (if (b.level <= 1) 1.2f else if (b.level == 2) 1.1f else 1f)),
                    color = color, modifier = Modifier.semantics { heading() },
                )
                // A fixed gutter for the number or bullet, so "9." and "10." line up and never touch the words.
                is Markdown.Block.Item -> Row(Modifier.padding(start = (b.depth * 20).dp)) {
                    Text(
                        if (b.number != null) "${b.number}." else "•", style = style, color = color,
                        softWrap = false, modifier = if (b.number != null) Modifier.widthIn(min = 36.dp) else Modifier.width(20.dp), textAlign = if (b.number != null) TextAlign.End else TextAlign.Start,
                    )
                    Spacer(Modifier.width(if (b.number != null) 8.dp else 0.dp))
                    Text(Markdown.inline(b.text, codeSpan, link, links), style = style, color = color, modifier = Modifier.weight(1f))
                }
                is Markdown.Block.Quote -> Row {
                    Box(Modifier.width(3.dp).padding(vertical = 2.dp).background(Palette.Outline))
                    Text(Markdown.inline(b.text, codeSpan, link, links), style = style.copy(fontStyle = FontStyle.Italic), color = color, modifier = Modifier.padding(start = 10.dp))
                }
                is Markdown.Block.Code -> CodeBlock(b, style, color)
                is Markdown.Block.Table -> TableBlock(b, style, color, codeSpan, link, links)
                Markdown.Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.Line).padding(top = 1.dp))
            }
        }
    }
}

/** A code block: a small language label and Copy on top, the code below, scrolling sideways if long. */
@Composable
private fun CodeBlock(b: Markdown.Block.Code, style: TextStyle, color: Color) {
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Bar)) {
        // The block's own label and button are not part of the words a selection takes.
        DisableSelection {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(b.lang.ifEmpty { "code" }, style = style.copy(fontSize = 12.sp), color = Palette.TextDim, modifier = Modifier.weight(1f))
                Box(
                    Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { clip.setText(AnnotatedString(b.text)) }
                        .semantics { contentDescription = "Copy this code" }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Copy", style = style.copy(fontSize = 13.sp), color = Palette.BlueText) }
            }
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
            Text(b.text, style = style.copy(fontFamily = FontFamily.Monospace, fontSize = style.fontSize * 0.9f), color = color, softWrap = false)
        }
    }
}

/** A table as a grid: a bold header row, thin lines. Columns share the width and cells wrap; only a table with too many columns scrolls sideways, and it shows a hint and a faded edge. */
@Composable
private fun TableBlock(b: Markdown.Block.Table, style: TextStyle, color: Color, code: SpanStyle, link: SpanStyle, links: Markdown.Links) {
    val fontScale = LocalDensity.current.fontScale
    val scroll = rememberScrollState()
    val cols = remember(b) {
        b.header.indices.map { c ->
            val cells = (listOf(b.header[c]) + b.rows.map { it[c] }).map { Markdown.inline(it).text }
            val longestWord = cells.maxOf { t -> t.split(' ', '\n').maxOfOrNull { it.length } ?: 0 }
            val natural = cells.maxOf { it.length }
            longestWord to natural
        }
    }
    // Rough width of one character at the table's text size, plus the cell padding.
    val perChar = 9f * fontScale
    val pad = 22f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val avail = maxWidth.value - 1f
        val mins = cols.map { it.first * perChar + pad }
        val naturals = cols.mapIndexed { i, c -> maxOf(mins[i], c.second * perChar + pad) }
        val fits = mins.sum() <= avail
        val widths: List<Dp> = if (!fits) mins.map { it.dp } else if (naturals.sum() <= avail) {
            val extra = (avail - naturals.sum()) / naturals.size
            naturals.map { (it + extra).dp }
        } else {
            val spare = avail - mins.sum()
            val wants = naturals.indices.map { naturals[it] - mins[it] }
            val total = wants.sum().coerceAtLeast(1f)
            mins.indices.map { (mins[it] + spare * wants[it] / total).dp }
        }
        Column {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, Palette.Line, RoundedCornerShape(8.dp))) {
                Column(Modifier.testTag("md-table").then(if (fits) Modifier else Modifier.horizontalScroll(scroll))) {
                    (listOf(b.header) + b.rows).forEachIndexed { r, row ->
                        Row(Modifier.height(IntrinsicSize.Min).then(if (r == 0) Modifier.background(Palette.Bar) else Modifier)) {
                            row.forEachIndexed { c, cell ->
                                Text(
                                    Markdown.inline(cell, code, link, links),
                                    style = style.copy(fontWeight = if (r == 0) FontWeight.Bold else style.fontWeight, fontSize = style.fontSize * 0.95f),
                                    color = color,
                                    modifier = Modifier.width(widths[c]).fillMaxHeight().border(0.5.dp, Palette.Line).padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                if (!fits && scroll.canScrollForward) {
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.width(28.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color.Transparent, Palette.Bar))))
                    }
                }
            }
            if (!fits) DisableSelection { Text("Scroll sideways to see more", style = Type.Small, color = Palette.TextDim, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("md-table-hint")) }
        }
    }
}
