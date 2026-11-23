package io.github.odnwks.mimir.online

import org.json.JSONObject
import java.net.URLEncoder

/** Which encyclopedias a "what is / who was" question reads (Settings › Who answers › Looking things up). */
enum class Encyclopedias(val label: String) {
    /** Both, answered from together: exactly what was asked; compared only if the person asked. */
    Both("Both"),
    Wikipedia("Wikipedia"),
    Grokipedia("Grokipedia"),
}

/**
 * Encyclopedias for "what is / who was" questions (owner, 2026-11-19): neutral sources, neither
 * treated as the authority. Wikipedia's own search (its API, which apps may use) finds the
 * article's exact title; the same title is then read on Grokipedia, never guessed. Grokipedia's
 * rules forbid apps, so it is read only after the same honest one-time warning as search, under the
 * app's own name.
 */
object Encyclopedia {
    const val WIKIPEDIA = "wikipedia.org"
    const val GROKIPEDIA = "grokipedia.com"

    /** Each article is read to about this long (the phone reads its prompt slowly). */
    const val ARTICLE_CHARS = 700

    /** One article: its title, its page, and its opening as plain words. */
    data class Article(val title: String, val url: String, val text: String)

    /** What was found: Wikipedia's article and Grokipedia's (null: not read, missing or blocked, with [grokProblem]). */
    data class Found(val wikipedia: Article?, val grokipedia: Article?, val grokProblem: String?)

    /** The article's exact title by Wikipedia's search, or null when nothing fits. */
    fun title(words: String, mode: ChatMode): String? {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=1&srprop=&format=json&srsearch=" + URLEncoder.encode(words, "UTF-8")
        val o = JSONObject(Net.get(url, mode, words = words, site = WIKIPEDIA, accept = setOf("application/json")).text)
        return o.optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title")?.ifEmpty { null }
    }

    /** Wikipedia's article opening for [title], as plain words. */
    fun wikipedia(title: String, words: String, mode: ChatMode, maxChars: Int = ARTICLE_CHARS): Article? {
        val url = "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&redirects=1&format=json&titles=" + URLEncoder.encode(title, "UTF-8")
        val pages = JSONObject(Net.get(url, mode, words = words, site = WIKIPEDIA, accept = setOf("application/json")).text)
            .optJSONObject("query")?.optJSONObject("pages") ?: return null
        val page = pages.keys().asSequence().map { pages.getJSONObject(it) }.firstOrNull { !it.has("missing") } ?: return null
        val text = page.optString("extract").trim().ifEmpty { return null }
        val t = page.optString("title", title)
        return Article(t, "https://en.wikipedia.org/wiki/" + pagePath(t), PageText.cut(text, maxChars))
    }

    /** Grokipedia's article with exactly [title], as plain words; null when it has no such page. */
    fun grokipedia(title: String, words: String, mode: ChatMode, maxChars: Int = ARTICLE_CHARS): Article? {
        val url = "https://grokipedia.com/page/" + pagePath(title)
        val got = try {
            Net.get(url, mode, words = words, site = GROKIPEDIA, accept = setOf("text/html"))
        } catch (e: java.io.IOException) {
            if (e.message?.contains("404") == true) return null
            throw e
        }
        val article = Regex("<article\\b[^>]*>(.*?)</article>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(got.text)?.groupValues?.get(1)
            ?: return null
        val text = PageText.of("<main>$article</main>", maxChars).text.lines()
            // Its own title line and edit buttons aren't the article.
            .filterNot { it.equals(title, ignoreCase = true) || it.length < 3 }
            .joinToString("\n") { it.replace(Regex("\\s*\\[\\d+\\]"), "") }.trim()
        return text.ifEmpty { null }?.let { Article(title, url, it) }
    }

    /** The title as both sites write it in a page's address ("Black hole" → "Black_hole"). */
    fun pagePath(title: String): String = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("%2F", "/").replace("%3A", ":").replace("%28", "(").replace("%29", ")").replace("%2C", ",")

    /**
     * Reads what [which] says about [words]. [grok] false leaves Grokipedia out (its warning wasn't
     * accepted). Returns null when Wikipedia's search finds nothing.
     */
    fun look(words: String, which: Encyclopedias, grok: Boolean, mode: ChatMode): Found? {
        val title = title(words, mode) ?: return null
        // Both articles are read at the same time, once the exact title is known.
        val wikiWay = if (which != Encyclopedias.Grokipedia || !grok) Net.async { wikipedia(title, words, mode) } else null
        val grokWay = if (which != Encyclopedias.Wikipedia && grok) Net.async { grokipedia(title, words, mode) } else null
        val wiki = wikiWay?.let { Net.await(it) }
        if (grokWay == null) return Found(wiki, null, null)
        return try {
            val g = Net.await(grokWay)
            Found(wiki ?: if (g == null) wikipedia(title, words, mode) else null, g, if (g == null) "Grokipedia has no page titled \"$title\"" else null)
        } catch (e: Exception) {
            val why = if (e is Net.Refused) e.message ?: "refused" else "Grokipedia isn't answering just now, so Wikipedia was used instead"
            Found(wiki ?: wikipedia(title, words, mode), null, why)
        }
    }

    /** What was found, as words for the model, with how to treat the two. */
    fun describe(f: Found): String = buildString {
        f.wikipedia?.let { append("From Wikipedia, \"").append(it.title).append("\":\n").append(it.text) }
        f.grokipedia?.let { if (isNotEmpty()) append("\n\n"); append("From Grokipedia, \"").append(it.title).append("\":\n").append(it.text) }
        if (f.wikipedia != null && f.grokipedia != null) append(
            "\n\nUse both, treating neither as the authority, and answer exactly what was asked from them. " +
                "Only compare them if asked to.",
        )
        f.grokProblem?.let { append("\n\n(").append(it).append(", so this is from Wikipedia only. Say so in one short sentence.)") }
    }

    /** The sources line under the answer. */
    fun sources(f: Found, words: String): List<Source> = listOfNotNull(
        f.wikipedia?.let { Source("Wikipedia", it.url, words) },
        f.grokipedia?.let { Source("Grokipedia", it.url, words) },
    )
}
