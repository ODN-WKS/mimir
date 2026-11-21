package io.github.odnwks.mimir.online

/**
 * The paired chat's "To: Auto": an everyday message (the weather, a question, a hello) is answered
 * directly instead of being filed in a project; only clear project work goes to the PC to be
 * filed. Decided from the typed words only.
 */
object Everyday {
    sealed interface Route {
        /** The weather: for [place] named in the message, or the saved place (null). */
        data class Weather(val place: String?) : Route
        /** A question or a chat that isn't about a project: Mímir or a general chat answers it. */
        data object Chat : Route
    }

    /** The PC's project for general chats (the owner's home for anything that fits no project). */
    const val GENERAL = "General"

    /** Status questions the PC answers itself ("what's going on?"). */
    private val STATUS = Regex(
        "\\b(status|what needs me|what's going on|what is going on|what's up|how's it going|anything for me|what's waiting)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Words that make a message project work, whatever else it says. */
    private val WORK = Regex(
        "\\b(fix(es|ed|ing)?|bugs?|build(s|ing)?|implement\\w*|refactor\\w*|releas(e|es|ed|ing)|deploy\\w*|commit\\w*|merge\\w*|" +
            "push(es|ed|ing)?|branch(es)?|compil\\w*|crash\\w*|errors?|tests?|testing|features?|repo(sitory)?|pull request|prs?|" +
            "code|coding|project|projects|task|tasks|screens?|buttons?|brief|install\\w*|rename\\w*|add|change|update the|make it)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** How a question or a hello starts. */
    private val ASKS = Regex(
        "^(?:(?:hi|hello|hey|good (?:morning|afternoon|evening|night)|morning|thanks|thank you)\\b[,.!]*\\s*|" +
            "(?:what|what's|whats|who|who's|whom|whose|when|where|where's|why|how|how's|which|is|are|was|were|do|does|did|" +
            "can|could|should|would|will|tell me|explain|define|describe|translate|recommend|suggest|remind me what|" +
            "give me|write me|help me|search(?: the web| online)? for|look up|google|find out)\\b)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Where [text] goes: answered directly ([Route]) or, for null, to the PC to be filed in a
     * project. [projects] are the PC's project names (a message naming one is project work).
     */
    fun route(text: String, projects: List<String>): Route? {
        val t = text.trim().replace('’', '\'')
        if (t.isEmpty() || t.length > 300) return null
        if (namesProject(t, projects, prefixOnly = true)) return null
        val first = t.split(Regex("\\s+")).take(3).joinToString(" ") { it.lowercase().trim(':', ',', '.') }
        if (first.split(' ').contains("idea")) return null
        Router.need(t)?.let { need ->
            if (need is Router.Need.Weather) return Route.Weather(need.place)
        }
        if (STATUS.containsMatchIn(t)) return null
        if (namesProject(t, projects, prefixOnly = false)) return null
        if (WORK.containsMatchIn(t)) return null
        if (ASKS.containsMatchIn(t) || t.endsWith("?")) return Route.Chat
        return null
    }

    /** "Name: …", "@Name …" or (unless [prefixOnly]) the project's name as words in the message. */
    private fun namesProject(t: String, projects: List<String>, prefixOnly: Boolean): Boolean {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val head = when {
            t.startsWith('@') -> t.drop(1).substringBefore(' ')
            ':' in t && t.substringBefore(':').split(' ').size <= 3 -> t.substringBefore(':')
            else -> null
        }
        if (head != null && projects.any { norm(it) == norm(head) && norm(it).isNotEmpty() }) return true
        if (prefixOnly) return false
        val lower = t.lowercase()
        // The general chat's own project is an everyday word ("a random fact"): only its prefix counts.
        return projects.filterNot { it.equals(GENERAL, ignoreCase = true) }.any { p ->
            val n = p.lowercase().trim()
            n.length >= 3 && Regex("(?<![\\p{L}\\d])" + Regex.escape(n) + "(?![\\p{L}\\d])").containsMatchIn(lower) ||
                // legacy name aliases.
                n.contains('-') && (lower.contains(n.replace('-', ' ')) || lower.contains(n.replace("-", "")))
        }
    }
}
