package io.github.odnwks.mimir.online

/**
 * Reads the top pages a search found, all at once, inside the lookup's time limit (P21 stage 1).
 * Normal chats only, and never with Android's Data Saver on. A page that is slow, refused or
 * broken never costs the answer: it comes back without text, and its search snippet is used.
 */
object Reader {
    /** How much of one page is read (a longer one is cut there, not refused). */
    const val PAGE_BYTES = 400_000

    /** How long one page may take. */
    const val PAGE_MS = 2_500

    /** A page's HTML is looked at up to this many characters. */
    const val HTML_CHARS = 200_000

    /** Reading stops this long before the lookup's time limit, so the answer still has time. */
    const val SPARE_MS = 700L

    private val ACCEPT = setOf("text/html", "text/plain")

    /** Each of [picked] with its main text, or with null where it wasn't read (in the same order). */
    fun read(picked: List<Search.Result>, mode: ChatMode): List<PageRead> {
        if (mode != ChatMode.Normal || picked.isEmpty() || Online.dataSaver()) return picked.map { PageRead(it, null) }
        val stop = Net.endOfBudget() - SPARE_MS
        val jobs = picked.map { r -> Net.async { runCatching { one(r, mode) }.getOrNull() } }
        val reads = picked.mapIndexed { i, r ->
            val job = jobs[i]
            val left = stop - System.currentTimeMillis()
            val got = if (left > 0) runCatching { job.get(left, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
                else if (job.isDone) runCatching { job.get() }.getOrNull() else null
            if (got == null) job.cancel(true)
            got ?: PageRead(r, null)
        }
        val read = reads.filter { it.text != null }
        val sites = read.map { it.result.site }.distinct()
        if (sites.isNotEmpty()) LookupLog.add(LookupLog.Entry(Net.clock(), "read pages", sites.joinToString(", "), LookupLog.Route.Phone, 0), mode)
        return reads
    }

    /** One page as text, or null when it couldn't be read. */
    private fun one(r: Search.Result, mode: ChatMode): PageRead? {
        val site = r.site
        val got = Net.get(r.url, mode, words = site, site = site, accept = ACCEPT, maxBytes = PAGE_BYTES, cut = true, timeoutMs = PAGE_MS)
        val raw = got.text.take(HTML_CHARS)
        if (got.type == "text/plain") return raw.trim().takeIf { it.isNotEmpty() }?.let { PageRead(r, it) }
        val text = PageText.main(raw).trim()
        if (text.isEmpty()) return null
        return PageRead(r, text, PageText.date(raw))
    }
}
