package io.github.odnwks.mimir.online

/** Checks on a finished answer against its evidence (P21 stage 1). */
object Verify {
    /** A source number as the model writes one: one or two digits (never [2024]). */
    private val CITATION = Regex("[ \\t]*\\[\\s*(\\d{1,2}(?:\\s*,\\s*\\d{1,2})*)\\s*]")
    private const val MOST = 20

    /** Code (a ``` block or `inline`), where brackets are the code's own (arr[0]) and never touched. */
    private val CODE = Regex("```.*?(```|$)|`[^`\\n]*`", RegexOption.DOT_MATCHES_ALL)

    /**
     * [answer] with every [n] outside 1..[count] removed: "[9]" goes (with the space before it),
     * "[1, 7]" becomes "[1]". Code and numbers over 20 are left alone. Nothing else changed.
     */
    fun citations(answer: String, count: Int): String {
        val out = StringBuilder(answer.length)
        var pos = 0
        for (c in CODE.findAll(answer)) {
            out.append(prose(answer.substring(pos, c.range.first), count)).append(c.value)
            pos = c.range.last + 1
        }
        return out.append(prose(answer.substring(pos), count)).toString()
    }

    private fun prose(text: String, count: Int): String = CITATION.replace(text) { m ->
        val numbers = m.groupValues[1].split(',').map { it.trim() }
        if (numbers.any { (it.toIntOrNull() ?: 0) > MOST }) return@replace m.value
        val kept = numbers.filter { n -> n.toIntOrNull()?.let { it in 1..count } == true }
        when {
            kept.size == numbers.size -> m.value
            kept.isEmpty() -> ""
            else -> m.value.substringBefore('[') + "[" + kept.joinToString(", ") + "]"
        }
    }
}
