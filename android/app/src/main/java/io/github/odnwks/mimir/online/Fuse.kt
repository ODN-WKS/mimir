package io.github.odnwks.mimir.online

import java.net.URL

/**
 * Search results from several engines made into one list (P21 stage 1). An engine's order is only
 * a hint: engines fed by the same index count once, so no one company's ranking decides.
 */
object Fuse {
    /** Reciprocal rank fusion's constant: a result's share is 1 / (RRF_K + its rank). */
    const val RRF_K = 60

    private val TRACKING = setOf("fbclid", "gclid")

    /** Lower-case host, no fragment, no utm_*, fbclid, gclid; null when not https or no host. */
    fun normalize(url: String): String? {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) return null
        val parsed = runCatching { URL(u) }.getOrNull() ?: return null
        val host = parsed.host.orEmpty().lowercase().trimEnd('.')
        if (host.isEmpty()) return null
        val port = if (parsed.port == -1 || parsed.port == 443) "" else ":${parsed.port}"
        val path = parsed.path.orEmpty().ifEmpty { "/" }
        val query = parsed.query.orEmpty().split('&').filter { part ->
            val key = part.substringBefore('=').lowercase()
            part.isNotEmpty() && !key.startsWith("utm_") && key !in TRACKING
        }.joinToString("&")
        return "https://$host$port$path" + if (query.isEmpty()) "" else "?$query"
    }

    private val LINEAGES = mapOf(
        "google" to "google", "google cse" to "google", "startpage" to "google",
        "bing" to "bing", "duckduckgo" to "bing", "yahoo" to "bing", "qwant" to "bing", "ecosia" to "bing",
        "brave" to "brave", "yandex" to "yandex", "yep" to "yep", "mwmbl" to "mwmbl", "mojeek" to "mojeek", "marginalia" to "marginalia",
    )

    /** The index family an engine's results come from; any other engine is its own. Case-insensitive. */
    fun lineageOf(engine: String): String {
        val e = engine.trim().lowercase().replace(Regex("\\s+"), " ")
        return LINEAGES[e] ?: e
    }

    /**
     * Results in fused order, https only, one per site, at most [max]. Rank = position in the list
     * (from 1); score = sum over the result's distinct lineages of 1 / (60 + rank); a result with no
     * engines counts as lineage "bing"; ties keep list order. Duplicates merge their engines.
     */
    fun pick(results: List<Search.Result>, max: Int = 4): List<Search.Result> {
        class Entry(var result: Search.Result, val rank: Int, val engines: LinkedHashSet<String>)
        val byUrl = LinkedHashMap<String, Entry>()
        results.forEachIndexed { i, r ->
            val key = normalize(r.url) ?: return@forEachIndexed
            val e = byUrl[key]
            if (e == null) {
                byUrl[key] = Entry(r, i + 1, LinkedHashSet(r.engines))
            } else {
                e.engines += r.engines
                if (e.result.snippet.isBlank() && r.snippet.isNotBlank()) e.result = e.result.copy(snippet = r.snippet)
            }
        }
        fun score(e: Entry): Double {
            val lineages = e.engines.map(::lineageOf).toSet().ifEmpty { setOf("bing") }
            return lineages.size / (RRF_K + e.rank).toDouble()
        }
        val sites = HashSet<String>()
        return byUrl.values.sortedByDescending(::score)
            .filter { sites.add(Net.hostOf(it.result.url).lowercase()) }
            .take(maxOf(0, max))
            .map { it.result.copy(engines = it.engines.toList()) }
    }
}
