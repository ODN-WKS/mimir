package io.github.odnwks.mimir.online

/** Checks on a finished answer against its evidence (P21 stage 1). STUB: piece A replaces the body. */
object Verify {
    /** [answer] with every [n] outside 1..count removed. */
    fun citations(answer: String, count: Int): String =
        answer.replace(Regex("\\[(\\d+)]")) { m -> if (m.groupValues[1].toInt() in 1..count) m.value else "" }
}
