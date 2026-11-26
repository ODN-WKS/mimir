package io.github.odnwks.mimir.online

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Web search through DuckDuckGo's plain results page (lite.duckduckgo.com), done honestly: the
 * app says who it is, asks once per question, keeps results for a while so the same question isn't
 * searched twice, and never gets around a robot check. When DuckDuckGo asks for one, the search
 * stops and the owner can open the results in the app's own browser.
 */
object Search {
    const val SITE = "duckduckgo.com"
    /** The owner accepted this once (the one-time warning). */
    const val WARNING = "$AI_NAME_FOR_WEB searches the web and reads an online encyclopedia for you. Only the few words needed are sent, " +
        "and a site may sometimes block it. Use it anyway?"

    /** How many results the phone asks the PC's search for (an older PC gives at most 10). */
    const val PC_MAX = 20

    data class Result(val title: String, val url: String, val snippet: String, val engines: List<String> = emptyList()) {
        val site: String get() = Net.hostOf(url)
    }

    /**
     * Search through the owner's own SearXNG on the PC (set by the app while paired): the results,
     * or null when the PC can't be asked right now. Checks swap it.
     */
    @Volatile var viaPc: ((String) -> List<Result>?)? = null

    /** The PC's answer (a list of `WebResult`) as results. */
    fun fromPc(a: org.json.JSONArray): List<Result> = (0 until a.length()).map { i ->
        a.getJSONObject(i).let { o ->
            Result(o.optString("title"), o.optString("url"), o.optString("snippet"),
                o.optJSONArray("engines")?.let { e -> (0 until e.length()).map { e.getString(it) } }.orEmpty())
        }
    }.filter { (it.url.startsWith("https://") || it.url.startsWith("http://")) && Net.hostOf(it.url).isNotEmpty() }

    /** "Brave, Google CSE": the engines behind results from the PC, as names. */
    fun engineNames(results: List<Result>): String = results.flatMap { it.engines }.distinct().take(4)
        .joinToString(", ") { e -> e.split(' ').joinToString(" ") { w -> if (w.length <= 3) w.uppercase() else w.replaceFirstChar { it.uppercase() } } }

    /**
     * Through the PC (a normal-mode chat only: a Whisper never goes near the PC), PC-first
     * (PHW-04, PHE-14): the PC's SearXNG is asked first and waited on for up to [PC_FIRST_MS].
     * Only when the PC is away or doesn't answer in time does the caller fall back to DuckDuckGo.
     * Returns the PC's results (listed in the Privacy check as "via your PC"), or null when
     * the PC can't be asked or didn't answer in time: [search] then tries DuckDuckGo.
     * Bounded by the lookup's time limit.
     */
    fun searchViaPc(words: String, mode: ChatMode): List<Result>? {
        if (mode != ChatMode.Normal || !Online.allowed(mode)) return null
        val pc = viaPc ?: return null
        val key = "pc:" + words.trim().lowercase()
        synchronized(this) {
            cache[key]?.let { (at, r) -> if (clock() - at < KEEP_MS) return r }
        }
        val end = minOf(System.currentTimeMillis() + PC_FIRST_MS, Net.endOfBudget())
        val future = Net.async { runCatching { pc(words) } }
        val left = end - System.currentTimeMillis()
        val r = if (left > 0) runCatching { future.get(left, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull() else null
        val list = r?.getOrNull() ?: return null  // PC away or timed out: fall back to DuckDuckGo.
        LookupLog.add(LookupLog.Entry(clock(), "web search", words, LookupLog.Route.Pc, 0), mode)
        synchronized(this) { cache[key] = clock() to list }
        return list
    }

    /** How long to wait for the PC's SearXNG before falling back to DuckDuckGo (PHW-04). */
    const val PC_FIRST_MS = 3_000L

    /** DuckDuckGo answered with a robot check (or an error): the owner can open [results] instead. */
    class Blocked(val results: String, why: String) : Exception(why)

    /** The results page for [words], as a person would open it. */
    fun resultsPage(words: String) = "https://lite.duckduckgo.com/lite/?q=" + URLEncoder.encode(words, "UTF-8")

    private const val KEEP_MS = 30 * 60_000L
    private val cache = object : LinkedHashMap<String, Pair<Long, List<Result>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<Result>>>?) = size > 30
    }

    /** Swapped in checks. */
    var clock: () -> Long = System::currentTimeMillis

    /** Forgets the kept results (a Whisper's never outlive it; checks start clean). */
    @Synchronized fun forget() { cache.clear(); failures.clear() }

    /** A DuckDuckGo search that failed a moment ago, so a second look at the same words doesn't send it again. */
    private val failures = HashMap<String, Triple<Long, Net.Refused?, Blocked?>>()

    /** Searches for [words] (once: a repeat within half an hour uses the kept results). */
    fun search(words: String, mode: ChatMode): List<Result> {
        val key = words.trim().lowercase()
        synchronized(this) {
            cache[key]?.let { (at, r) -> if (clock() - at < KEEP_MS) return r }
            failures[key]?.let { (at, refused, blocked) -> if (clock() - at < 20_000) { refused?.let { throw it }; blocked?.let { throw it } } }
        }
        return duckResults(words, mode)
    }

    /** The DuckDuckGo search itself: kept when it works, remembered for a moment when it doesn't. */
    private fun duckResults(words: String, mode: ChatMode): List<Result> {
        val key = words.trim().lowercase()
        try {
            val results = duck(words, mode)
            synchronized(this) { cache[key] = clock() to results; failures.remove(key) }
            return results
        } catch (e: Net.Refused) {
            synchronized(this) { failures[key] = Triple(clock(), e, null) }
            throw e
        } catch (e: Blocked) {
            synchronized(this) { failures[key] = Triple(clock(), null, e) }
            throw e
        }
    }

    private fun duck(words: String, mode: ChatMode): List<Result> {
        val page = resultsPage(words)
        val got = try {
            Net.get(page, mode, words = words, site = SITE, accept = setOf("text/html"), maxBytes = 500_000)
        } catch (e: Net.Refused) {
            throw e
        } catch (e: Exception) {
            throw Blocked(page, e.message ?: "no answer")
        }
        if (robotCheck(got.text)) throw Blocked(page, "DuckDuckGo asked for a robot check")
        return parse(got.text)
    }

    fun robotCheck(html: String): Boolean = html.contains("anomaly-modal") || html.contains("anomaly.js") || html.contains("bots use DuckDuckGo too")

    private val LINK = Regex("""<a[^>]*href="([^"]+)"[^>]*class=['"]result-link['"][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val SNIPPET = Regex("""<td[^>]*class=['"]result-snippet['"][^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)

    /** The results on DuckDuckGo's plain page, ads left out, at most [max]. */
    fun parse(html: String, max: Int = 5): List<Result> {
        val links = LINK.findAll(html).toList()
        val snippets = SNIPPET.findAll(html).map { text(it.groupValues[1]) }.toList()
        return links.mapIndexedNotNull { i, m ->
            val url = target(m.groupValues[1].replace("&amp;", "&")) ?: return@mapIndexedNotNull null
            Result(text(m.groupValues[2]), url, snippets.getOrElse(i) { "" })
        }.take(max)
    }

    /** The real address behind DuckDuckGo's link (ads, which go through y.js, are dropped). */
    private fun target(href: String): String? {
        if ("y.js" in href || "ad_domain" in href) return null
        val u = href.substringAfter("uddg=", "").substringBefore('&')
        val real = if (u.isNotEmpty()) URLDecoder.decode(u, "UTF-8") else href
        return real.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    fun text(html: String): String = PageText.decode(html.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()

    /** How many results the model reads, and how long each snippet is (the phone reads its prompt slowly). */
    const val TOP = 3
    const val SNIPPET_CHARS = 160

    /** The results as words for the model: title, site and snippet of each of the top [TOP]. */
    fun describe(words: String, results: List<Result>, from: String = "DuckDuckGo"): String = buildString {
        append("Web search results for \"").append(words).append("\" (from ").append(from).append("):")
        if (results.isEmpty()) append("\nNo results.")
        results.take(TOP).forEachIndexed { i, r -> append("\n").append(i + 1).append(". ").append(r.title).append(" (").append(r.site).append("): ").append(shorten(r.snippet)) }
    }

    /** [s] cut at a word near [SNIPPET_CHARS], with an ellipsis when something was left out. */
    fun shorten(s: String, max: Int = SNIPPET_CHARS): String {
        if (s.length <= max) return s
        val part = s.take(max)
        val space = part.lastIndexOf(' ')
        return (if (space > max / 2) part.take(space) else part).trimEnd(' ', ',', ';', ':', '-') + "…"
    }
}

/** The AI's name, for the online parts' words (they live outside the screens' package). */
const val AI_NAME_FOR_WEB = "Mímir"
