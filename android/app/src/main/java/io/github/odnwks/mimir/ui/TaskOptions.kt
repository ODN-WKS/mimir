package io.github.odnwks.mimir.ui

/**
 * The effort levels a task can run at on the PC: the same names and one-line meanings as the PC
 * window's effort list. [wire] is what the PC takes (`null`: the task's own choice).
 */
enum class TaskEffort(val words: String, val wire: String?, val about: String) {
    Usual("Default Effort", null, "Uses your PC's default effort level"),
    Low("Low", "low", "Quick answers"),
    Medium("Medium", "medium", "A little thinking"),
    High("High", "high", "Thinks it through"),
    ExtraHigh("Extra high", "xhigh", "Thinks longer"),
    Max("Max", "max", "Thinks longest, uses the most");

    companion object {
        fun of(wire: String?): TaskEffort = entries.firstOrNull { it.wire == wire } ?: Usual
    }
}

/** What an old PC says in place of the model and effort choice. */
const val UPDATE_PC_FOR_OPTIONS = "Update Mímir on your PC to change this here"

/** The words on the chip beside the reply box: "Model · Effort". */
fun optionsWords(model: String, effort: String): String =
    listOf(plainModelName(model).ifBlank { "Auto" }, TaskEffort.of(effort.ifEmpty { null }).let { it.words }).joinToString(" · ")

/** The next effort level in the list, round to the usual one (one tap on a chip steps through them). */
fun nextEffort(wire: String): String = TaskEffort.entries.let { all -> all[(all.indexOf(TaskEffort.of(wire.ifEmpty { null })) + 1) % all.size] }.wire.orEmpty()
