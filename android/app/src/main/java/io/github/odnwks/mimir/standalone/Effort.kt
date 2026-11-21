package io.github.odnwks.mimir.standalone

/**
 * How hard Mímir works on an answer, from the effort chip on the message box: Quick is short and
 * straight away, Balanced is the usual, Thorough thinks first (on models that can) and may write
 * more.
 */
enum class Effort(val words: String, val about: String, val wire: String) {
    Quick("Quick", "Short answers, straight away", "low"),
    Balanced("Balanced", "The usual", "medium"),
    Thorough("Thorough", "Thinks it through first: slower to start", "high");

    /** Whether the model thinks before answering. */
    fun thinks(model: LocalModel): Boolean = this == Thorough && model.thinking

    /** The most the answer (and its thinking) may run to. */
    fun maxTokens(model: LocalModel): Int = when (this) {
        Quick -> QUICK_TOKENS
        Balanced -> Engine.MAX_REPLY_TOKENS
        Thorough -> if (thinks(model)) Engine.MAX_THINKING_TOKENS else THOROUGH_TOKENS
    }

    companion object {
        const val QUICK_TOKENS = 384
        const val THOROUGH_TOKENS = 2048

        fun of(name: String?): Effort? = entries.firstOrNull { it.name == name }
    }
}
