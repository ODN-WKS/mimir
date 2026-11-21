package io.github.odnwks.mimir.data

/**
 * Whether a chat message names a project on purpose, as a prefix ("Garden planner: …", "@Garden planner …",
 * "In Garden planner Mod Manager, …"), the same forms the PC's strict router reads (crates/core route.rs).
 * Used only while the PC can't be reached, to say that project work needs it.
 */
object ProjectNamed {
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The project [text] names among [names] (folder names), or null. */
    fun of(text: String, names: List<String>): String? {
        val t = text.trimStart()
        val head = when {
            t.startsWith("@") -> t.drop(1).takeWhile { !it.isWhitespace() }
            t.lowercase().startsWith("in ") || t.lowercase().startsWith("for ") -> {
                val after = t.substringAfter(' ')
                val end = after.indexOfFirst { it == ',' || it == ':' }
                if (end < 0) null else after.substring(0, end).takeIf { it.trim().split(Regex("\\s+")).size <= 4 }
            }
            else -> t.indexOf(':').takeIf { it > 0 }?.let { i -> t.substring(0, i).takeIf { it.trim().split(Regex("\\s+")).size <= 3 } }
        } ?: return null
        val h = norm(head)
        if (h.isEmpty()) return null
        return names.firstOrNull { norm(it.trimEnd('/').substringAfterLast('/')) == h }
    }
}
