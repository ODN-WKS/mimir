package io.github.odnwks.mimir.online

/** The evidence block the model reads (P21 stage 1). STUB: piece A replaces the bodies. */
object Evidence {
    const val INSTRUCTION = "Answer from these sources only. After each fact put its source number like [1]. " +
        "If they don't answer the question, say so. If they disagree, say what each says."

    /** One line of page text with nothing in it that looks like the app's own frame or a chat marker. */
    fun clean(passage: String): String = Net.clean(passage).replace(Regex("\\s+"), " ").trim()

    fun block(words: String, chosen: List<Chosen>, pagesRead: Int): String = buildString {
        append("Search: \"").append(words).append("\" · ")
        append(if (pagesRead > 0) "$pagesRead pages read" else "from search results")
        var last = -1
        for (c in chosen) {
            if (c.n != last) {
                append("\n[").append(c.n).append("] ").append(c.site).append(" · ").append(clean(c.title))
                c.date?.let { append(" · ").append(it) }
                last = c.n
            }
            append("\n\"").append(clean(c.quote)).append('"')
        }
        append('\n').append(INSTRUCTION)
    }
}
