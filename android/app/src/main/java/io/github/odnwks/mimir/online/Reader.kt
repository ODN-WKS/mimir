package io.github.odnwks.mimir.online

/**
 * Reads the top pages a search found, all at once, inside the lookup's time limit (P21 stage 1).
 * Normal chats only, and never with Android's Data Saver on. A page that is slow, refused or
 * broken never costs the answer: it comes back without text, and its search snippet is used.
 * A page that is gone, or whose site doesn't answer, gets one try at the Wayback Machine's copy
 * (stage 3); a page that blocks the app never does, as that would get round the block.
 */
object Reader {
    /** How much of one page is read (a longer one is cut there, not refused). */
    const val PAGE_BYTES = 400_000

    /** How long one page may take. */
    const val PAGE_MS = 2_500

    /** A page's HTML is looked at up to this many characters. */
    const val HTML_CHARS = 100_000

    /** Reading stops this long before the lookup's time limit, so the answer still has time. */
    const val SPARE_MS = 700L

    /**
     * How long the page's own site may take, a second try included: a silent site is given up on
     * early enough that its archived copy can still be read.
     */
    const val SITE_MS = 4_500L

    /** Where the Wayback Machine's copy of a page is (the page's address goes after it). */
    private const val ARCHIVE = "https://web.archive.org/web/2026/"

    /** The only answers from a site that mean the page is gone (a refusal is not one). */
    private val GONE = setOf(404, 410)

    private val ACCEPT = setOf("text/html", "text/plain")

    /** Each of [picked] with its main text, or with null where it wasn't read (in the same order). */
    fun read(picked: List<Search.Result>, mode: ChatMode): List<PageRead> {
        if (mode != ChatMode.Normal || picked.isEmpty() || Online.dataSaver()) return picked.map { PageRead(it, null) }
        val stop = Net.endOfBudget() - SPARE_MS
        val jobs = picked.map { r -> Net.async { runCatching { one(r, mode) }.getOrNull() } }
        val reads = picked.mapIndexed { i, r ->
            val job = jobs[i]
            val left = stop - System.currentTimeMillis()
            val got = try {
                if (left > 0) job.get(left, java.util.concurrent.TimeUnit.MILLISECONDS) else if (job.isDone) job.get() else null
            } catch (e: InterruptedException) {
                // Stop: nothing more is read or waited for.
                jobs.forEach { it.cancel(true) }
                Thread.currentThread().interrupt()
                return picked.map { PageRead(it, null) }
            } catch (e: Exception) {
                null
            }
            if (got == null) job.cancel(true)
            got ?: PageRead(r, null)
        }
        val read = reads.filter { it.text != null }
        val sites = read.map { it.result.site }.distinct()
        if (sites.isNotEmpty()) LookupLog.add(LookupLog.Entry(Net.clock(), "read pages", sites.joinToString(", "), LookupLog.Route.Phone, 0), mode)
        return reads
    }

    /** One page as text, from its site or else the archive, or null when it couldn't be read. */
    private fun one(r: Search.Result, mode: ChatMode): PageRead? = try {
        Net.within(SITE_MS) { page(r, r.url, r.site, mode) }
    } catch (e: Net.Failed) {
        if ((e.status != null && e.status !in GONE) || !mayAskArchive(mode)) throw e
        page(r, ARCHIVE + r.url, "web.archive.org", mode)?.copy(archived = true)
    }

    private fun mayAskArchive(mode: ChatMode) = mode == ChatMode.Normal && !Online.dataSaver() && Online.settings.value.search

    /** [r]'s page as read at [address] on [site], or null when it holds no text. */
    private fun page(r: Search.Result, address: String, site: String, mode: ChatMode): PageRead? {
        val got = Net.get(address, mode, words = r.site, site = site, accept = ACCEPT, maxBytes = PAGE_BYTES, cut = true, timeoutMs = PAGE_MS)
        val raw = got.text.take(HTML_CHARS)
        if (got.type == "text/plain") return raw.trim().takeIf { it.isNotEmpty() }?.let { PageRead(r, it) }
        val text = PageText.main(raw).trim()
        if (text.isEmpty()) return null
        return PageRead(r, text, PageText.date(raw))
    }
}
