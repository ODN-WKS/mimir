package io.github.odnwks.mimir.standalone

/** What is in the message box of one chat: words, files and pictures not sent yet. */
data class Draft(val text: String, val files: List<TurnFile>, val pictures: List<TurnPicture>) {
    val isEmpty: Boolean get() = text.isEmpty() && files.isEmpty() && pictures.isEmpty()
}

/**
 * Each chat keeps its own box, held outside the chat screen so that Settings or History in between loses nothing.
 * Words are also written to the phone ([onTextChange] says when); files and pictures are kept while the app runs.
 */
class ChatDrafts(initial: Map<String, String> = emptyMap(), private val onTextChange: () -> Unit = {}) {
    private val kept = HashMap<String, Draft>().apply {
        initial.forEach { (id, text) -> if (text.isNotEmpty()) put(id, Draft(text, emptyList(), emptyList())) }
    }

    private val empty = Draft("", emptyList(), emptyList())

    @Synchronized fun get(id: String): Draft = kept[id] ?: empty

    @Synchronized fun put(id: String, draft: Draft) {
        val before = kept[id]?.text.orEmpty()
        if (draft.isEmpty) kept.remove(id) else kept[id] = draft
        if (before != draft.text) onTextChange()
    }

    @Synchronized fun remove(id: String) { if (kept.remove(id) != null) onTextChange() }

    @Synchronized fun clear() { if (kept.isNotEmpty()) { kept.clear(); onTextChange() } }

    /** The words of every draft, for keeping on the phone. */
    @Synchronized fun texts(): Map<String, String> = kept.filterValues { it.text.isNotEmpty() }.mapValues { it.value.text }

    /** Leaving [from] with [current] in the box: it is kept for that chat, and what [to] had is returned. */
    fun swap(from: String, to: String, current: Draft): Draft { put(from, current); return get(to) }
}
