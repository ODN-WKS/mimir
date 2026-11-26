package io.github.odnwks.mimir.online

/**
 * A web page as plain words: its title, headings and paragraphs, with scripts, styles, menus,
 * headers, footers and forms left out. Nothing on the page is run.
 */
object PageText {
    data class Page(val title: String, val text: String)

    private val DROP = Regex(
        "<(script|style|noscript|svg|nav|header|footer|form|aside|iframe|template|button|select)\\b[^>]*>.*?</\\1\\s*>|<!--.*?-->",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val BLOCK = Regex("</?(p|div|br|li|h[1-6]|tr|section|article|blockquote|pre|table|ul|ol|dd|dt)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** A page is read to about this long (the phone reads its prompt slowly: about 50 words a second). */
    const val PAGE_CHARS = 2_500

    /** [html] as plain words, cut to about [maxChars]. */
    fun of(html: String, maxChars: Int = PAGE_CHARS): Page {
        val title = TITLE.find(html)?.groupValues?.get(1)?.let { decode(it.replace(TAG, "")).trim() }.orEmpty()
        // The main part when the page marks one; otherwise the body.
        val main = Regex("<(main|article)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(2)
            ?: html.substringAfter("<body", html)
        val text = decode(main.replace(DROP, " ").replace(BLOCK, "\n").replace(TAG, " "))
            .lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
            .filter { it.length > 1 }
            .joinToString("\n")
        return Page(title, cut(text, maxChars))
    }

    // ---- P21 stage 1: the main text, its date, and passages ----

    /** Parts never read, whose insides may hold a stray "<" (dropped before the tags are walked). */
    private val RAW = Regex(
        "<(script|style|noscript|template|svg|textarea)\\b[^>]*>.*?</\\1\\s*>|<!--.*?-->|<!\\[CDATA\\[.*?]]>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val ANY_TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*)>")
    private val NOT_TEXT = setOf(
        "head", "title", "script", "style", "noscript", "template", "svg", "nav", "header", "footer", "aside",
        "form", "iframe", "button", "select", "textarea", "dialog", "menu",
    )
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val LINE_TAGS = setOf(
        "p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "section", "article", "main", "blockquote", "pre",
        "table", "thead", "tbody", "tfoot", "ul", "ol", "dl", "dd", "dt", "figure", "figcaption", "hr", "details", "summary",
        "caption", "address", "center",
    )
    private val INLINE = setOf("a", "b", "i", "em", "strong", "span", "sup", "sub", "small", "u", "mark", "abbr", "code", "s", "q", "cite", "time", "font", "bdi", "bdo", "kbd", "var", "data")
    private val HIDDEN_ATTR = Regex("(^|\\s)hidden(\\s|=|/|$)", RegexOption.IGNORE_CASE)
    private val ARIA_HIDDEN = Regex("aria-hidden\\s*=\\s*[\"']?true", RegexOption.IGNORE_CASE)
    private val VALUE = Regex("=\\s*(\"[^\"]*\"|'[^']*')")
    private val STYLE = Regex("style\\s*=\\s*(\"[^\"]*\"|'[^']*')", RegexOption.IGNORE_CASE)
    private val DISPLAY_NONE = Regex("display\\s*:\\s*none", RegexOption.IGNORE_CASE)
    private val ZERO_FONT = Regex("font-size\\s*:\\s*0*\\.?0+(px|em|rem|pt|%)?\\s*(;|!|$)", RegexOption.IGNORE_CASE)

    /** The tag says its part is hidden from people (hidden, aria-hidden="true", display:none, zero font size). */
    private fun hidden(attrs: String): Boolean {
        if (HIDDEN_ATTR.containsMatchIn(attrs.replace(VALUE, "="))) return true
        if (ARIA_HIDDEN.containsMatchIn(attrs)) return true
        val style = STYLE.find(attrs)?.groupValues?.get(1)?.trim('"', '\'') ?: return false
        return DISPLAY_NONE.containsMatchIn(style) || ZERO_FONT.containsMatchIn(style)
    }

    private class Tag(val close: Boolean, val name: String, val attrs: String, val start: Int, val end: Int) {
        val selfClosing get() = attrs.trimEnd().endsWith("/")
    }

    private fun tags(html: String) = ANY_TAG.findAll(html).map {
        Tag(it.groupValues[1] == "/", it.groupValues[2].lowercase(), it.groupValues[3], it.range.first, it.range.last + 1)
    }.toList()

    /** Index (in [tags]) of the tag closing the one at [open], or -1 when it is never closed. */
    private fun closing(tags: List<Tag>, open: Int): Int {
        val name = tags[open].name
        var depth = 1
        for (j in open + 1 until tags.size) {
            val t = tags[j]
            if (t.name != name) continue
            if (t.close) depth-- else if (!t.selfClosing) depth++
            if (depth == 0) return j
        }
        return -1
    }

    /** [html] without the elements (and their insides) that [drop] picks. */
    private fun without(html: String, drop: (Tag) -> Boolean): String {
        val tags = tags(html)
        val out = StringBuilder(html.length)
        var pos = 0
        var i = 0
        while (i < tags.size) {
            val t = tags[i]
            if (t.close || t.start < pos || !drop(t)) { i++; continue }
            out.append(html, pos, t.start).append(' ')
            val j = if (t.name in VOID || t.selfClosing) -1 else closing(tags, i)
            if (j < 0) { pos = t.end; i++ } else { pos = tags[j].end; i = j + 1 }
        }
        out.append(html, pos, html.length)
        return out.toString()
    }

    /** The insides of each top-level [name] element in [html]. */
    private fun insides(html: String, name: String): List<String> {
        val tags = tags(html)
        val out = ArrayList<String>()
        var i = 0
        while (i < tags.size) {
            val t = tags[i]
            if (t.close || t.name != name) { i++; continue }
            val j = closing(tags, i)
            if (j < 0) { out += html.substring(t.end); break }
            out += html.substring(t.end, tags[j].start)
            i = j + 1
        }
        return out
    }

    /**
     * The page's main text: `<article>` (the longest) or `<main>` when the page marks one, else the
     * body; menus, headers, footers, side parts, forms, scripts and hidden parts dropped; headings,
     * list items and table rows each on their own line; lines mostly made of link text dropped.
     */
    fun main(html: String): String {
        val clean = without(html.replace(RAW, " ")) { it.name in NOT_TEXT || hidden(it.attrs) }
        val region = insides(clean, "article").maxByOrNull { it.replace(ANY_TAG, "").trim().length }
            ?: insides(clean, "main").firstOrNull()
            ?: clean.substringAfter("<body", clean).let { if (it === clean) it else it.substringAfter('>') }
        return lines(region).joinToString("\n")
    }

    /** [html] as lines of text, each block its own line, mostly-link lines left out. */
    private fun lines(html: String): List<String> {
        val out = ArrayList<String>()
        val line = StringBuilder()
        val link = StringBuilder()
        var inLink = 0
        fun flush() {
            val text = decode(line.toString()).replace(Regex("[\\s\\u00a0]+"), " ").trim()
            val linked = decode(link.toString()).replace(Regex("[\\s\\u00a0]+"), " ").trim()
            if (text.length > 1 && linked.length * 2 <= text.length) out += text
            line.setLength(0)
            link.setLength(0)
        }
        var pos = 0
        for (t in tags(html)) {
            val words = html.substring(pos, t.start)
            line.append(words)
            if (inLink > 0) link.append(words)
            pos = t.end
            when {
                t.name == "a" -> if (t.close) inLink = maxOf(0, inLink - 1) else if (!t.selfClosing) inLink++
                t.name in LINE_TAGS -> flush()
                t.name in INLINE -> {}
                else -> { line.append(' '); if (inLink > 0) link.append(' ') }
            }
        }
        val rest = html.substring(pos)
        line.append(rest)
        if (inLink > 0) link.append(rest)
        flush()
        return out
    }

    private val ISO_DAY = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
    private val LD_DATE = Regex("\"datePublished\"\\s*:\\s*\"([^\"]+)\"")
    private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val META_PUBLISHED = Regex("(property|name)\\s*=\\s*[\"']article:published_time[\"']", RegexOption.IGNORE_CASE)
    private val META_CONTENT = Regex("content\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val TIME = Regex("<time\\b[^>]*\\bdatetime\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** When the page says it was published, as "2026-11-02": JSON-LD datePublished, then article:published_time, then the first `<time datetime>`; null if none. */
    fun date(html: String): String? {
        val found = sequenceOf(
            { LD_DATE.find(html)?.groupValues?.get(1) },
            { META.findAll(html).firstOrNull { META_PUBLISHED.containsMatchIn(it.value) }?.let { META_CONTENT.find(it.value)?.groupValues?.get(1) } },
            { TIME.find(html)?.groupValues?.get(1) },
        ).mapNotNull { it()?.let(::day) }
        return found.firstOrNull()
    }

    private fun day(value: String): String? {
        val m = ISO_DAY.find(value) ?: return null
        val month = m.groupValues[2].toInt()
        val d = m.groupValues[3].toInt()
        return m.value.takeIf { month in 1..12 && d in 1..31 }
    }

    const val PASSAGE_MIN = 50
    const val PASSAGE_MAX = 90

    private val SENTENCE_END = Regex("(?<=[.!?…][\"'”’)\\]]{0,2})\\s+")
    private val ENDS_SENTENCE = Regex("[.!?…:;,\"'”’)\\]]$")

    private fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }

    /** A line that reads as a heading: short, no end mark, and followed by a longer line of sentences. */
    private fun heading(line: String, next: String?): Boolean {
        val n = wordCount(line)
        if (n == 0 || n > 12 || ENDS_SENTENCE.containsMatchIn(line)) return false
        return next != null && wordCount(next) > n && ENDS_SENTENCE.containsMatchIn(next.trimEnd())
    }

    /** [text] in pieces of 50 to 90 words, cut at sentence ends, each with its nearest heading line (index from 0). */
    fun passages(text: String): List<Passage> {
        val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val pieces = ArrayList<Pair<String, String>>()  // text, heading
        var heading = ""
        var pieceHeading = ""
        val cur = ArrayList<String>()
        var count = 0
        fun emit() {
            if (cur.isEmpty()) return
            pieces += cur.joinToString(" ") to pieceHeading
            cur.clear()
            count = 0
        }
        rows.forEachIndexed { i, row ->
            if (heading(row, rows.getOrNull(i + 1))) { heading = row; return@forEachIndexed }
            // A line without an end mark (a list item, a table row) is one unit; a paragraph splits into sentences.
            for (s in row.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }) {
                val n = wordCount(s)
                if (count >= PASSAGE_MIN && count + n > PASSAGE_MAX) emit()
                if (cur.isEmpty()) pieceHeading = heading
                cur += s
                count += n
            }
        }
        emit()
        // The last piece, when short, joins the one before if they fit together.
        if (pieces.size >= 2 && wordCount(pieces.last().first) < PASSAGE_MIN &&
            wordCount(pieces[pieces.size - 2].first) + wordCount(pieces.last().first) <= PASSAGE_MAX
        ) {
            val last = pieces.removeAt(pieces.size - 1)
            val prev = pieces.removeAt(pieces.size - 1)
            pieces += (prev.first + " " + last.first) to prev.second
        }
        // A piece over the most (one very long sentence) is cut into even parts at words.
        return pieces.flatMap { (t, h) ->
            val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size <= PASSAGE_MAX) {
                listOf(t to h)
            } else {
                val parts = (words.size + PASSAGE_MAX - 1) / PASSAGE_MAX
                val size = (words.size + parts - 1) / parts
                words.chunked(size).map { it.joinToString(" ") to h }
            }
        }.mapIndexed { i, (t, h) -> Passage(t, h, i) }
    }

    /** Cut at a line or sentence end near [max]. */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val part = text.take(max)
        val end = maxOf(part.lastIndexOf('\n'), part.lastIndexOf(". "))
        return (if (end > max / 2) part.take(end + 1) else part).trimEnd() + "\n[…the rest of the page was left out]"
    }

    /** The first web address in [text] ("https://…" or "http://…"), without the punctuation after it. */
    fun linkIn(text: String): String? = Regex("https?://[^\\s<>\"]+").find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', ':', '\'', '"')

    private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "copy" to "©", "deg" to "°", "pound" to "£", "euro" to "€")

    fun decode(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
            else -> NAMED[e.lowercase()] ?: m.value
        }
    }
}
