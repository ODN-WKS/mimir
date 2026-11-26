package io.github.odnwks.mimir.online

import kotlin.math.ln

/** A piece of a page's text, 50 to 90 words, with its nearest heading. */
data class Passage(val text: String, val heading: String, val index: Int)

/** A candidate for the evidence: [text] is the page's main text, or null when the page wasn't read (its snippet is used). */
data class PageRead(val result: Search.Result, val text: String?, val date: String? = null, val archived: Boolean = false)

/** A passage chosen as evidence, numbered [n] by site. */
data class Chosen(val n: Int, val site: String, val url: String, val title: String, val date: String?, val quote: String, val archived: Boolean = false)

/**
 * Picks the passages the model reads (P21 stage 1), by what the pages say: BM25 over every
 * passage, plus small bonuses. Every weight lives here, each held by a test.
 */
object Rank {
    const val EVIDENCE_WORDS = 200

    /** BM25's settings. */
    const val K1 = 1.2
    const val B = 0.75

    /** Bonus for the page title holding the query terms (times the share of terms it holds). */
    const val TITLE_BONUS = 0.5
    /** Bonus for one of a page's first passages (index 0 to 2). */
    const val EARLY_BONUS = 0.25
    /** Bonus for digits in the passage when the question asks for a number or a date. */
    const val DIGITS_BONUS = 1.0
    /** Word-set overlap (Jaccard) above which a passage counts as a copy of one already chosen. */
    const val COPY_OVERLAP = 0.6
    /** At most this many passages from one site. */
    const val PER_SITE = 2

    private val NUMBER_QUESTION = listOf("how much", "how many", "when", "what year", "price", "score")

    private val STOP = setOf(
        "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "by", "for", "with", "about", "from", "into",
        "is", "are", "was", "were", "be", "been", "being", "am", "do", "does", "did", "done", "have", "has", "had",
        "i", "me", "my", "you", "your", "he", "she", "it", "its", "we", "our", "they", "them", "their", "this", "that", "these", "those",
        "what", "which", "who", "whom", "whose", "where", "when", "why", "how", "there", "here",
        "can", "could", "would", "should", "will", "shall", "may", "might", "must",
        "not", "no", "so", "than", "then", "too", "very", "just", "also", "as", "up", "out", "any", "some", "all",
        "tell", "please", "know", "find", "search", "look", "s", "t", "much", "many", "get", "got",
    )

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** Lower-case words, a plural "s" taken off, so "prices" meets "price". */
    private fun terms(text: String): List<String> = WORD.findAll(text.lowercase()).map { stem(it.value) }.toList()

    private fun stem(w: String): String =
        if (w.length > 3 && w.endsWith('s') && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) w.dropLast(1) else w

    private fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }

    private class Doc(val page: PageRead, val text: String, val index: Int, val terms: List<String>) {
        val site = page.result.site
        val words = wordCount(text)
        val set = terms.toSet()
        var score = 0.0
    }

    /**
     * BM25 (k1 1.2, b 0.75) over all passages of [pages] plus the snippets of unread pages. Query
     * terms: [words] plus the content words of [question]. Bonuses: title term match, early passage,
     * digits when the question asks for a number or date. A near-copy of a chosen passage is skipped;
     * at most 2 passages a site; at most [budgetWords] words in all. n = 1.. by order of first
     * appearance of each site. Empty when nothing holds any query term.
     */
    fun choose(question: String, words: String, pages: List<PageRead>, budgetWords: Int = EVIDENCE_WORDS): List<Chosen> {
        val query = (terms(words) + terms(question)).filter { it !in STOP }.distinct()
            .ifEmpty { (terms(words) + terms(question)).distinct() }
        if (query.isEmpty() || budgetWords <= 0) return emptyList()

        val docs = pages.flatMap { p ->
            val passages = p.text?.takeIf { it.isNotBlank() }?.let { PageText.passages(it) }.orEmpty()
            if (passages.isNotEmpty()) {
                passages.map { Doc(p, it.text, it.index, terms(it.heading + " " + it.text)) }
            } else {
                listOf(Doc(p, p.result.snippet.trim(), 0, terms(p.result.snippet)))
            }
        }.filter { it.text.isNotBlank() }
        if (docs.isEmpty()) return emptyList()

        val avg = docs.sumOf { it.terms.size }.toDouble() / docs.size
        val df = query.associateWith { q -> docs.count { q in it.set } }
        val numbers = question.lowercase().let { q -> NUMBER_QUESTION.any { it in q } }
        for (d in docs) {
            val tf = d.terms.groupingBy { it }.eachCount()
            var s = 0.0
            for (q in query) {
                val f = tf[q] ?: continue
                val n = df.getValue(q)
                val idf = ln(1 + (docs.size - n + 0.5) / (n + 0.5))
                s += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * d.terms.size / avg))
            }
            if (s <= 0.0) continue
            val title = terms(d.page.result.title).toSet()
            s += TITLE_BONUS * query.count { it in title } / query.size
            if (d.index <= 2) s += EARLY_BONUS
            if (numbers && d.text.any { it.isDigit() }) s += DIGITS_BONUS
            d.score = s
        }

        val picked = ArrayList<Doc>()
        val perSite = HashMap<String, Int>()
        var used = 0
        for (d in docs.filter { it.score > 0.0 }.sortedByDescending { it.score }) {
            if ((perSite[d.site] ?: 0) >= PER_SITE) continue
            if (picked.any { jaccard(it.set, d.set) > COPY_OVERLAP }) continue
            if (used + d.words > budgetWords) continue
            picked += d
            perSite[d.site] = (perSite[d.site] ?: 0) + 1
            used += d.words
        }

        val numberOf = LinkedHashMap<String, Int>()
        return picked.map { d ->
            val n = numberOf.getOrPut(d.site) { numberOf.size + 1 }
            Chosen(n, d.site, d.page.result.url, d.page.result.title, d.page.date, d.text, d.page.archived)
        }.sortedBy { it.n }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }
}
