package io.github.odnwks.mimir.standalone

/** What is in the message box of one chat: words, files and pictures not sent yet. */
data class Draft(val text: String, val files: List<TurnFile>, val pictures: List<TurnPicture>) {
    val isEmpty: Boolean get() = text.isEmpty() && files.isEmpty() && pictures.isEmpty()
}

/** Each chat keeps its own box: moving to another chat never carries words or pictures along. */
class ChatDrafts {
    private val kept = HashMap<String, Draft>()

    /** Leaving [from] with [current] in the box: it is kept for that chat, and what [to] had is returned (empty when nothing). */
    fun swap(from: String, to: String, current: Draft): Draft {
        if (current.isEmpty) kept.remove(from) else kept[from] = current
        return kept[to] ?: Draft("", emptyList(), emptyList())
    }
}
