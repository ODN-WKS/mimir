package io.github.odnwks.mimir.labs

import java.net.URLEncoder

/** One line of "Run checks": what was looked at, how it went and, for a problem, what to do next. */
data class CheckLine(val name: String, val result: Result, val line: String, val next: String? = null) {
    enum class Result { Good, Problem, Skipped }

    val mark: String get() = when (result) { Result.Good -> "✓"; Result.Problem -> "✗"; Result.Skipped -> "–" }
    fun asText(): String = "$mark $name: $line" + (next?.let { " ($it)" } ?: "")
}

/** What the phone knows about itself for a report: nothing here can name the owner or the phone. */
data class ReportFacts(
    val appName: String = "Mímir",
    val version: String,
    val flavor: String,
    val built: String,
    val android: String,
    val phoneFamily: String,
    val screenClass: String,
    val paired: Boolean,
    /** "This phone", "Your PC", "Claude" or "Online": never a model or account name. */
    val whoAnswers: String,
    /** "Phone" or "Tablet": the device the report is from. */
    val deviceLabel: String = "Phone",
)

/** Builds the plain-text report and the GitHub link. Pure, so it is tested without a phone. */
object Report {
    /** Samsung's model numbers say the family ("SM-S9..." is a Galaxy S); everything else is the maker's name only. */
    fun phoneFamily(manufacturer: String, model: String, tablet: Boolean = false): String {
        val device = if (tablet) "tablet" else "phone"
        val maker = manufacturer.trim().lowercase().takeUnless { it == "unknown" }.orEmpty()
        val m = model.trim().uppercase()
        return when {
            maker == "samsung" -> when {
                m.startsWith("SM-S") || m.startsWith("SM-G9") -> "Samsung Galaxy S-series"
                m.startsWith("SM-N") -> "Samsung Galaxy Note-series"
                m.startsWith("SM-A") -> "Samsung Galaxy A-series"
                m.startsWith("SM-F") -> "Samsung Galaxy Z-series"
                m.startsWith("SM-T") || m.startsWith("SM-X") -> "Samsung Galaxy Tab-series"
                else -> "Samsung Galaxy"
            }
            maker == "google" -> if (m.startsWith("PIXEL")) "Google Pixel" else "Google $device"
            maker == "lenovo" -> if (m.startsWith("TB") || m.contains("TAB") || tablet) "Lenovo tablet" else "Lenovo phone"
            maker.isBlank() -> "Android $device"
            else -> maker.replaceFirstChar { it.uppercase() } + " $device"
        }
    }

    /** Compact, medium or expanded, from the screen's smallest width in dp (the Android size classes). */
    fun screenClass(smallestWidthDp: Int): String = when {
        smallestWidthDp < 600 -> "compact ($smallestWidthDp dp wide)"
        smallestWidthDp < 840 -> "medium ($smallestWidthDp dp wide)"
        else -> "expanded ($smallestWidthDp dp wide)"
    }

    /** The report, in the order people read it. [errors] are already scrubbed; null means the log is off. */
    fun build(facts: ReportFacts, checks: List<CheckLine>?, errors: List<String>?, what: String = ""): String = buildString {
        appendLine("${facts.appName} problem report")
        if (what.isNotBlank()) { appendLine("What happened: ${what.trim()}"); appendLine() }
        appendLine("App: ${facts.appName} ${facts.version} (${facts.flavor}, built ${facts.built})")
        appendLine("Android: ${facts.android}")
        appendLine("${facts.deviceLabel}: ${facts.phoneFamily}")
        appendLine("Screen: ${facts.screenClass}")
        appendLine("Paired with a PC: ${if (facts.paired) "yes" else "no"}")
        appendLine("Who answers: ${facts.whoAnswers}")
        if (checks != null) {
            appendLine()
            appendLine("Checks:")
            if (checks.isEmpty()) appendLine("  (not run yet)") else checks.forEach { appendLine("  " + it.asText()) }
        }
        if (errors != null) {
            appendLine()
            appendLine("Recent errors (names, messages and addresses taken out):")
            if (errors.isEmpty()) appendLine("  (none)") else errors.forEach { appendLine("  $it") }
        }
    }.trimEnd()

    /** One line for the form's report box when the whole report is on the clipboard instead. */
    fun summary(facts: ReportFacts): String =
        "${facts.appName} ${facts.version} (${facts.flavor}), Android ${facts.android}, ${facts.phoneFamily}, " +
            "paired ${if (facts.paired) "yes" else "no"}, answers: ${facts.whoAnswers}"
}

/** The link that opens GitHub's form with a few boxes filled in. */
object GithubLink {
    const val FORM = "https://github.com/ODN-WKS/mimir/issues/new?template=report-a-problem.yml"
    /** GitHub refuses very long addresses (it documents no number), so the link stays under this. */
    const val MAX_URL = 2000
    const val PASTE_NOTE = "Paste the copied report below this line."

    /** [title] is cut to 80 characters; [what] is cut until the whole address fits; [summary] is one line. */
    fun build(title: String, what: String, summary: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val report = summary.replace(Regex("""\s+"""), " ").trim().take(300) + "\n\n" + PASTE_NOTE
        val t = title.replace(Regex("""\s+"""), " ").trim().take(80)
        var w = what.trim()
        fun url() = FORM + "&title=" + enc(t) + "&what=" + enc(w) + "&report=" + enc(report)
        while (url().length > MAX_URL && w.isNotEmpty()) w = w.take((w.length * 4 / 5).coerceAtMost(w.length - 1)).trimEnd()
        return url()
    }

    /** A short title from what the person wrote: its first line, or a plain default. */
    fun titleFrom(what: String): String =
        what.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(70) ?: "A problem in Mímir"

    const val COPIED = "Copied"
    const val COPIED_PASTE = "The report is copied: paste it into the form."
}
