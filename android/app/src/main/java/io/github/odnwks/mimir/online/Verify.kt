package io.github.odnwks.mimir.online

/** Checks on a finished answer against its evidence (P21 stage 1). */
object Verify {
    private val CITATION = Regex("[ \\t]*\\[\\s*(\\d+(?:\\s*,\\s*\\d+)*)\\s*]")

    /**
     * [answer] with every [n] outside 1..[count] removed: "[9]" goes (with the space before it),
     * "[1, 7]" becomes "[1]". Nothing else changed.
     */
    fun citations(answer: String, count: Int): String = CITATION.replace(answer) { m ->
        val numbers = m.groupValues[1].split(',').map { it.trim() }
        val kept = numbers.filter { n -> n.toIntOrNull()?.let { it in 1..count } == true }
        when {
            kept.size == numbers.size -> m.value
            kept.isEmpty() -> ""
            else -> m.value.substringBefore('[') + "[" + kept.joinToString(", ") + "]"
        }
    }
}
