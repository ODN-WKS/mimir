package io.github.odnwks.mimir.online

/** A piece of a page's text, 50 to 90 words, with its nearest heading. */
data class Passage(val text: String, val heading: String, val index: Int)

/** A candidate for the evidence: [text] is the page's main text, or null when the page wasn't read (its snippet is used). */
data class PageRead(val result: Search.Result, val text: String?, val date: String? = null)

/** A passage chosen as evidence, numbered [n] by site. */
data class Chosen(val n: Int, val site: String, val url: String, val title: String, val date: String?, val quote: String)

/** Picks the passages the model reads (P21 stage 1). STUB: piece A replaces the body. */
object Rank {
    const val EVIDENCE_WORDS = 200

    fun choose(question: String, words: String, pages: List<PageRead>, budgetWords: Int = EVIDENCE_WORDS): List<Chosen> {
        val out = ArrayList<Chosen>()
        val numbers = LinkedHashMap<String, Int>()
        var used = 0
        for (p in pages) {
            val text = p.text?.let { PageText.passages(it).firstOrNull()?.text } ?: p.result.snippet
            if (text.isBlank()) continue
            val count = text.split(Regex("\\s+")).size
            if (used + count > budgetWords && out.isNotEmpty()) break
            used += count
            val n = numbers.getOrPut(p.result.site) { numbers.size + 1 }
            out += Chosen(n, p.result.site, p.result.url, p.result.title, p.date, text)
        }
        return out
    }
}
