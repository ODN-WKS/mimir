package io.github.odnwks.mimir.online

/**
 * Search results from several engines made into one list (P21 stage 1). An engine's order is only
 * a hint: engines fed by the same index count once. STUB: piece A replaces the bodies.
 */
object Fuse {
    /** Lower-case host, no fragment, no utm_*, fbclid, gclid; null when not https or no host. */
    fun normalize(url: String): String? {
        if (!url.startsWith("https://")) return null
        val host = Net.hostOf(url)
        if (host.isEmpty()) return null
        return url.substringBefore('#')
    }

    /** The index family an engine's results come from. */
    fun lineageOf(engine: String): String = engine.lowercase().trim()

    /** Results in fused order, https only, one per site, at most [max]. */
    fun pick(results: List<Search.Result>, max: Int = 4): List<Search.Result> =
        results.filter { normalize(it.url) != null }.distinctBy { it.site }.take(max)
}
