package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.online.Router

/**
 * A skill picked for one message (the bubble beside Model · Effort): an instruction added to the
 * request, and for the [lookup] ones a lookup that happens even when the message wouldn't ask for
 * one. In the order the sheet lists them. [id] is what the chat keeps with the message.
 */
enum class Skill(
    val id: String, val title: String, val about: String,
    /** The bubble's shorter words when room is short. */
    val short: String,
    val lookup: Boolean, private val how: String,
) {
    Deep(
        "deep_research", "Deep research", "Many sites, reads the pages, with sources", "Research", true,
        "Write a short report. One section for each sub-question. Number the sources and cite them. End with a \"Bottom line\" of 3 to 5 points.",
    ),
    Shopping(
        "shopping", "Shopping research", "Best options and prices", "Shopping", true,
        "List the best options. For each, give the price, the shop and the date exactly as the fetched pages show them. Never invent a price; say when a page gives none.",
    ),
    Writing(
        "fix_writing", "Fix my writing", "Edit, shorten, change tone, translate", "Writing", false,
        "Work on the person's own text, in the message or the attached file. Fix, shorten, change the tone or translate as asked. Give the finished text first.",
    ),
    Teach(
        "teach", "Teach me", "Step by step, in plain words", "Teach", false,
        "Explain step by step in plain words. End with one question that checks understanding.",
    ),
    People(
        "people_saying", "What people are saying", "Forums and posts on a topic, now", "People say", true,
        "Report what people are saying in the forums, question sites and news found, newest first. Quote their own words and cite each source.",
    ),
    Summarize(
        "summarize", "Summarize", "A page, file or pasted text", "Summarize", false,
        "Summarize the attached file or picture, the page given to you, or the pasted text. Short points first. " +
            "If a link's page was not given to you, say to tap \"Read\" under the message.",
    ),
    Trace(
        "trace_claim", "Trace a claim", "Follows it to the original source, both sides", "Trace", true,
        "Follow the claim to its original source. Show the evidence on each side in the sources' own words, with citations. Give no verdict words.",
    );

    /** What the answerer is told, for this message only. */
    val instruction: String
        get() = "For this message only, the person picked the skill \"$title\". $how Answer literally, with no unasked advice or warnings." +
            if (lookup) " If nothing was fetched for this message, say you could not look it up and do not invent sources." else ""

    /**
     * What a lookup skill searches for: the words the model asked for ([asked]), else the
     * person's own words; null for the other skills or with no words to search.
     */
    fun need(text: String, asked: Router.Need?): Router.Need.Search? {
        if (!lookup) return null
        val words = (asked as? Router.Need.Search)?.words ?: Router.lookUp(text)?.words ?: return null
        val extra = when (this) {
            Shopping -> "price"
            People -> "forum discussion news"
            Trace -> "original source"
            else -> null
        } ?: return Router.Need.Search(words)
        // At most 12 words go out, as for every search.
        return Router.Need.Search((words.split(' ').take(12 - extra.split(' ').size) + extra).joinToString(" "))
    }

    companion object {
        fun of(id: String?): Skill? = entries.firstOrNull { it.id == id }

        /** A PC Whisper's message takes its skill inside the words. */
        fun withWords(text: String, skill: Skill?): String = if (skill == null) text else "$text\n\n[${skill.instruction}]"

        /** Those words as the person sees them: the instruction reads as the skill's name. */
        fun shown(text: String): String = entries.firstOrNull { text.endsWith("\n\n[${it.instruction}]") }
            ?.let { text.removeSuffix("\n\n[${it.instruction}]") + "\n\nSkill: ${it.title}" } ?: text
    }
}
