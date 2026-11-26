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

/** The words on the reply box's chip: "Model · Effort" ([withEffort] false, a PC too old to take one: the model only). */
fun optionsWords(model: String, effort: String, withEffort: Boolean = true): String =
    listOfNotNull(plainModelName(model).ifBlank { "Auto" }, TaskEffort.of(effort.ifEmpty { null }).words.takeIf { withEffort }).joinToString(" · ")

/** Shorter words for the same chip when room is short (a narrow phone at large text): "Opus 5.5 · Default", then the model alone. */
fun optionsShorter(model: String, effort: String, withEffort: Boolean = true): List<String> {
    val name = chipShort(plainModelName(model).ifBlank { "Auto" }, 12)
    val e = TaskEffort.of(effort.ifEmpty { null }).let { if (it == TaskEffort.Usual) "Default" else it.words }
    return listOfNotNull("$name · $e".takeIf { withEffort }, name)
}
