package io.github.odnwks.mimir.online

/**
 * A web page as plain words: its title, headings and paragraphs, with scripts, styles, menus,
 * headers, footers and small forms left out. Nothing on the page is run.
 */
object PageText {
    data class Page(val title: String, val text: String)

    private val DROP = Regex(
        "<(script|style|noscript|svg|nav|header|footer|aside|iframe|template|button|select)\\b[^>]*>.*?</\\1\\s*>|<!--.*?-->",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val BLOCK = Regex("</?(p|div|br|li|h[1-6]|tr|section|article|blockquote|pre|table|ul|ol|dd|dt)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val FORM = Regex("<form\\b[^>]*>(.*?)</form\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val PARA = Regex("<p\\b", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** A page is read to about this long (the phone reads its prompt slowly: about 50 words a second). */
    const val PAGE_CHARS = 2_500

    /** [html] as plain words, cut to about [maxChars]. */
    fun of(html: String, maxChars: Int = PAGE_CHARS): Page {
        val title = TITLE.find(html)?.groupValues?.get(1)?.let { decode(it.replace(TAG, "")).trim() }.orEmpty()
        // The main part when the page marks one; otherwise the body.
        val main = Regex("<(main|article)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(2)
            ?: html.substringAfter("<body", html)
        // A small form (a search box, a sign-up) goes; one wrapping the page's text stays.
        val kept = main.replace(DROP, " ").replace(FORM) { f ->
            val inside = f.groupValues[1]
            if (PARA.containsMatchIn(inside) || untagged(inside).count { !it.isWhitespace() } >= FORM_TEXT) " $inside " else " "
        }
        val text = decode(kept.replace(BLOCK, "\n").replace(TAG, " "))
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

    /**
     * Every tag in [html], in order. Quoted attribute values are skipped whole, so a ">" inside one
     * (`data-a="x>"`) does not end the tag. One pass, no regex backtracking.
     */
    private fun tags(html: String): List<Tag> {
        val out = ArrayList<Tag>()
        val n = html.length
        var i = html.indexOf('<')
        while (i in 0 until n) {
            var k = i + 1
            val close = k < n && html[k] == '/'
            if (close) k++
            val nameStart = k
            if (k < n && html[k].isAsciiLetter()) {
                k++
                while (k < n && (html[k].isAsciiLetter() || html[k] in '0'..'9' || html[k] == '-')) k++
                val nameEnd = k
                // The attributes, up to the first ">" outside a quoted value. A quote opens a value only
                // after "=", as in a browser; one never closed swallows the rest of the page, as in a browser.
                var end = -1
                var afterEquals = false
                while (k < n) {
                    val c = html[k]
                    if (c == '>') { end = k; break }
                    if (afterEquals && (c == '"' || c == '\'')) {
                        val q = html.indexOf(c, k + 1)
                        if (q < 0) { end = n - 1; break }
                        k = q + 1
                        afterEquals = false
                    } else {
                        if (c == '=') afterEquals = true else if (!c.isWhitespace()) afterEquals = false
                        k++
                    }
                }
                if (end >= 0) {
                    out += Tag(close, html.substring(nameStart, nameEnd).lowercase(), html.substring(nameEnd, maxOf(nameEnd, end)), i, end + 1)
                    i = if (end + 1 < n) html.indexOf('<', end + 1) else -1
                    continue
                }
            }
            i = html.indexOf('<', i + 1)
        }
        return out
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    /** [html] with its tags taken out. */
    private fun untagged(html: String): String {
        val out = StringBuilder(html.length)
        var pos = 0
        for (t in tags(html)) { out.append(html, pos, t.start); pos = t.end }
        return out.append(html, pos, html.length).toString()
    }

    /**
     * For each tag, the index of the tag that closes it, or -1 (a closing tag, or one never closed).
     * One pass with a stack of open tags per name, so a page of unclosed tags stays fast.
     */
    private fun closers(tags: List<Tag>): IntArray {
        val match = IntArray(tags.size) { -1 }
        val open = HashMap<String, ArrayList<Int>>()
        for ((j, t) in tags.withIndex()) {
            if (!t.close) {
                if (!t.selfClosing) open.getOrPut(t.name) { ArrayList() }.add(j)
            } else {
                val stack = open[t.name] ?: continue
                if (stack.isNotEmpty()) match[stack.removeAt(stack.size - 1)] = j
            }
        }
        return match
    }

    /** A form is page furniture (a search box, a sign-up) when it holds no paragraph and under this much text. */
    private const val FORM_TEXT = 200

    /**
     * [html] without the parts never read: menus, page headers, footers, side parts, small forms,
     * hidden parts. An article's own `<header>` (its title and lead) stays, and so does a form that
     * wraps the whole page (some sites put every page inside one).
     */
    private fun withoutFurniture(html: String): String {
        val tags = tags(html)
        val match = closers(tags)
        // Text characters (not spaces) before each tag, and paragraphs opened before it: a form's insides in O(1).
        val textBefore = IntArray(tags.size + 1)
        val parasBefore = IntArray(tags.size + 1)
        var prevEnd = 0
        for ((k, t) in tags.withIndex()) {
            var c = 0
            for (x in prevEnd until t.start) if (!html[x].isWhitespace()) c++
            textBefore[k + 1] = textBefore[k] + c
            parasBefore[k + 1] = parasBefore[k] + if (!t.close && t.name == "p") 1 else 0
            prevEnd = t.end
        }
        fun small(i: Int, j: Int) = parasBefore[j] - parasBefore[i + 1] == 0 && textBefore[j + 1] - textBefore[i + 1] < FORM_TEXT

        val out = StringBuilder(html.length)
        var pos = 0
        var i = 0
        var inArticle = 0
        while (i < tags.size) {
            val t = tags[i]
            val j = if (t.close || t.name in VOID || t.selfClosing) -1 else match[i]
            val drop = !t.close && when {
                hidden(t.attrs) -> true
                t.name == "header" -> inArticle == 0
                t.name == "form" -> j >= 0 && small(i, j)
                else -> t.name in NOT_TEXT
            }
            if (!drop) {
                if (t.name == "article" && !t.selfClosing) { if (!t.close) inArticle++ else if (inArticle > 0) inArticle-- }
                i++
                continue
            }
            out.append(html, pos, t.start).append(' ')
            if (j < 0) { pos = t.end; i++ } else { pos = tags[j].end; i = j + 1 }
        }
        out.append(html, pos, html.length)
        return out.toString()
    }

    /** The insides of each top-level [name] element in [html]. */
    private fun insides(html: String, name: String): List<String> {
        val tags = tags(html)
        val match = closers(tags)
        val out = ArrayList<String>()
        var i = 0
        while (i < tags.size) {
            val t = tags[i]
            if (t.close || t.name != name) { i++; continue }
            val j = match[i]
            if (j < 0) { out += html.substring(t.end); break }
            out += html.substring(t.end, tags[j].start)
            i = j + 1
        }
        return out
    }

    /** The part of the page its words come from: the longest `<article>`, else `<main>`, else the body; furniture and hidden parts gone. */
    private fun region(html: String): String {
        val clean = withoutFurniture(html.replace(RAW, " "))
        return insides(clean, "article").maxByOrNull { untagged(it).trim().length }
            ?: insides(clean, "main").firstOrNull()
            ?: clean.substringAfter("<body", clean).let { if (it === clean) it else it.substringAfter('>') }
    }

    /**
     * The page's main text: `<article>` (the longest) or `<main>` when the page marks one, else the
     * body; menus, headers, footers, side parts, forms, scripts and hidden parts dropped; headings,
     * list items and table rows each on their own line; lines mostly made of link text dropped.
     */
    fun main(html: String): String = lines(region(html)).joinToString("\n")

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

    /**
     * When the page says it was published, as "2026-11-02": JSON-LD datePublished, then
     * article:published_time, then a `<time datetime>` inside the article [main] reads (not a
     * sidebar's), then the first one on the page; a date that can't exist (2026-02-31) is skipped; null if none.
     */
    fun date(html: String): String? {
        val found = sequenceOf(
            { LD_DATE.find(html)?.groupValues?.get(1) },
            { META.findAll(html).firstOrNull { META_PUBLISHED.containsMatchIn(it.value) }?.let { META_CONTENT.find(it.value)?.groupValues?.get(1) } },
            { TIME.find(region(html))?.groupValues?.get(1) },
            { TIME.find(html)?.groupValues?.get(1) },
        ).mapNotNull { it()?.let(::day) }
        return found.firstOrNull()
    }

    /** The "2026-11-02" in [value], or null when there is none or it is not a real day. */
    private fun day(value: String): String? {
        val m = ISO_DAY.find(value) ?: return null
        val (y, month, d) = m.destructured
        return m.value.takeIf { runCatching { java.time.LocalDate.of(y.toInt(), month.toInt(), d.toInt()) }.isSuccess }
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
