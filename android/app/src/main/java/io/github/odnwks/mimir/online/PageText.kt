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

    /** The page's main text (P21 stage 1). STUB: piece A replaces the body. */
    fun main(html: String): String = of(html, 200_000).text

    /** When the page says it was published ("2026-11-02"), or null. STUB: piece A replaces the body. */
    fun date(html: String): String? = null

    /** [text] in pieces of 50 to 90 words with their nearest heading. STUB: piece A replaces the body. */
    fun passages(text: String): List<Passage> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.chunked(70).mapIndexed { i, w -> Passage(w.joinToString(" "), "", i) }

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
