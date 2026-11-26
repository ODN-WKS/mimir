package io.github.odnwks.mimir.online

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** A kind of question that an open database answers well, beside the web search (P21 stage 3). */
enum class Lane { Science, Code, PeopleSay, Books }

/**
 * Open databases read beside the web search from this phone alone (P21 stage 3, normal chats
 * only): research papers for a science or health question, Stack Exchange for a coding one,
 * Hacker News for "what are people saying", Open Library for a book. Each asks one service with
 * the search words only (no account, no cookies, the app's honest name), at most [MAX] results,
 * at most [LANE_MS] each, all at once. A service that is slow or down just gives nothing.
 */
object Lanes {
    /** At most this many results from one service. */
    const val MAX = 5

    /** How long one service may take (PubMed's two steps together). */
    const val LANE_MS = 2_000L

    /** The services' names, as engines: Fuse counts each as its own index family. */
    const val OPENALEX = "OpenAlex"
    const val CROSSREF = "Crossref"
    const val PUBMED = "PubMed"
    // An engine's id inside the app, never shown on a screen. lint:log-only
    const val STACK_EXCHANGE = "Stack Exchange"
    const val HACKER_NEWS = "Hacker News"
    const val OPEN_LIBRARY = "Open Library"
    val ENGINES = setOf(OPENALEX, CROSSREF, PUBMED, STACK_EXCHANGE, HACKER_NEWS, OPEN_LIBRARY)

    /** The sites the services are asked on (nothing else is sent anywhere by a lane). */
    val HOSTS = setOf(
        "api.openalex.org", "api.crossref.org", "eutils.ncbi.nlm.nih.gov",
        "api.stackexchange.com", "hn.algolia.com", "openlibrary.org",
    )

    /** How much of an abstract or a post is kept as the result's snippet. */
    const val SNIPPET_CHARS = 600

    // ---- Which lanes a question has ----

    /** Words that hold "paper" but aren't about research. */
    private val NOT_SCIENCE = Regex("(?i)\\b(toilet|tissue|kitchen|wrapping|baking|parchment|rice|sand|tracing|printer|news|crepe|tissue) ?papers?\\b|\\bpaper (towels?|plates?|cups?|planes?|bags?|clips?|straws?|money|round)\\b")
    private val SCIENCE = listOf(
        Regex("(?i)\\b(stud(y|ies)|research(ers)?|scientific(ally)?|papers?|evidence|clinical(ly)?|trials?|meta-?analys[ie]s|peer[- ]reviewed|journals?|randomi[sz]ed|placebo)\\b"),
        Regex("(?i)\\bdoes\\b.{1,60}\\bcause\\b"),
        Regex("(?i)\\b(is|are)\\b.{1,40}\\blinked (to|with)\\b"),
        Regex("(?i)\\b(causes?|cures?|prevents?|treats?)\\s+(cancer|dementia|autism|diabetes|heart disease|depression|alzheimer'?s|asthma|arthritis|obesity|strokes?)\\b"),
        Regex("(?i)\\b(side effects?|health (benefits?|risks?|claims?|effects?)|(good|bad) for (you|your health))\\b"),
    )

    private val NOT_CODE = Regex("(?i)\\b(margin of|human|trial and|standard|medical|spelling|typing|clerical) errors?\\b")
    private val LANGUAGE = "python|java|kotlin|javascript|js|typescript|ts|rust|go|golang|c|c\\+\\+|c#|swift|ruby|php|bash|shell|sql|haskell|scala|perl|lua|dart|r|matlab|powershell|elixir|julia"
    private val CODE = listOf(
        Regex("(?i)\\b(errors?|exceptions?|stack ?traces?|traceback|segfault|segmentation fault|compil(e|er|es|ed|ing|ation)|functions?|syntax|null ?pointer|nullpointerexception|undefined reference|regex|stack overflow|stackoverflow)\\b"),
        Regex("(?i)\\bhow (do|can|to|would|should)\\b.{0,80}\\b(in|with|using)\\s+($LANGUAGE)(?=$|[^\\w+#])"),
    )

    private val PEOPLE = listOf(
        Regex("(?i)\\bwhat (are|do|did|is) (people|folks|users|others|developers|programmers|the internet|everyone|reddit)\\b.{0,20}\\b(say|saying|think|thinking|said|feel|feeling)\\b"),
        Regex("(?i)\\b(opinions?|thoughts) (on|about|of)\\b"),
        Regex("(?i)\\breviews? (of|for|on)\\b"),
        Regex("(?i)\\b(reddit|subreddit|forums?|hacker news)\\b"),
    )

    /** "Book a table" is not about a book. */
    private val NOT_BOOKS = Regex("(?i)\\bbook(ing|ed|s)? (a|an|the|my|me|us|tickets?|flights?|hotels?|tables?|appointments?|rooms?|seats?|trains?|online|now|in)\\b")
    private val BOOKS = listOf(
        Regex("(?i)\\b(books?|novels?|novella|paperback|hardcover|hardback|isbn|audiobooks?|memoir)\\b"),
        Regex("(?i)\\bauthor of\\b"),
        Regex("(?i)\\bwho wrote\\b"),
    )

    /** The lanes for a question: from the owner's [question] and the [words] that will be searched. Empty for a general question. */
    fun lanesFor(question: String, words: String): Set<Lane> {
        val text = "$question\n$words"
        val lanes = LinkedHashSet<Lane>()
        if (SCIENCE.any { it.containsMatchIn(text.replace(NOT_SCIENCE, " ")) }) lanes += Lane.Science
        if (CODE.any { it.containsMatchIn(text.replace(NOT_CODE, " ")) }) lanes += Lane.Code
        if (PEOPLE.any { it.containsMatchIn(text) }) lanes += Lane.PeopleSay
        if (BOOKS.any { it.containsMatchIn(text.replace(NOT_BOOKS, " ")) }) lanes += Lane.Books
        return lanes
    }

    // ---- Asking the services ----

    /** One service: its name and how it is asked. */
    class Adapter(val name: String, val ask: (words: String, mode: ChatMode) -> List<Search.Result>)

    val ADAPTERS: Map<Lane, List<Adapter>> = mapOf(
        Lane.Science to listOf(Adapter(PUBMED, ::pubMed), Adapter(OPENALEX, ::openAlex), Adapter(CROSSREF, ::crossref)),
        Lane.Code to listOf(Adapter(STACK_EXCHANGE, ::stackExchange)),
        Lane.PeopleSay to listOf(Adapter(HACKER_NEWS, ::hackerNews)),
        Lane.Books to listOf(Adapter(OPEN_LIBRARY, ::openLibrary)),
    )

    /**
     * Every service for [lanes], all at once, each with at most [LANE_MS] and never past the
     * lookup's own time limit: one list per service (in [ADAPTERS] order), empty where it gave
     * nothing. Nothing at all in a Whisper, with lookups off or with Data Saver on.
     */
    fun search(lanes: Set<Lane>, words: String, mode: ChatMode): List<List<Search.Result>> {
        if (lanes.isEmpty() || words.isBlank() || mode != ChatMode.Normal || !Online.allowed(mode) || Online.dataSaver()) return emptyList()
        val adapters = lanes.flatMap { ADAPTERS[it].orEmpty() }.distinctBy { it.name }
        val end = minOf(System.currentTimeMillis() + LANE_MS, Net.endOfBudget())
        val jobs = adapters.map { a -> Net.async { runCatching { Net.within(LANE_MS) { a.ask(words, mode) } }.getOrDefault(emptyList()) } }
        return jobs.map { job ->
            val left = end - System.currentTimeMillis()
            val got = try {
                if (left > 0) job.get(left, java.util.concurrent.TimeUnit.MILLISECONDS) else if (job.isDone) job.get() else null
            } catch (e: InterruptedException) {
                jobs.forEach { it.cancel(true) }
                Thread.currentThread().interrupt()
                return emptyList()
            } catch (e: Exception) {
                null
            }
            if (got == null) job.cancel(true)
            got.orEmpty().take(MAX)
        }
    }

    /**
     * The web's results and the services' lists made into one list for Fuse, taking turns (the
     * web's first, each service's first, the web's second, ...), so a service's best result has
     * about the same rank as the web's best, as each list would on its own.
     */
    fun join(web: List<Search.Result>, lanes: List<List<Search.Result>>): List<Search.Result> {
        val lists = listOf(web) + lanes.filter { it.isNotEmpty() }
        if (lists.size == 1) return web
        val out = ArrayList<Search.Result>()
        val longest = lists.maxOf { it.size }
        for (i in 0 until longest) for (l in lists) l.getOrNull(i)?.let { out += it }
        return out
    }

    /** A result found by a service here on the phone (and by nothing else). */
    fun found(r: Search.Result): Boolean = r.engines.isNotEmpty() && r.engines.all { it in ENGINES }

    /** A result whose snippet already holds what its page would say (the page itself is built by script, so reading it gives nothing). */
    fun snippetOnly(r: Search.Result): Boolean = found(r) && r.engines.all { it == OPENALEX || it == CROSSREF }

    private fun q(words: String) = URLEncoder.encode(words.trim(), "UTF-8")

    private fun json(address: String, words: String, mode: ChatMode): String =
        Net.get(address, mode, words = words, accept = setOf("application/json"), maxBytes = 500_000, timeoutMs = LANE_MS.toInt()).text

    // OpenAlex: research papers of every field, with their abstracts.
    fun openAlexAddress(words: String) =
        "https://api.openalex.org/works?search=${q(words)}&per-page=$MAX&select=id,display_name,publication_year,abstract_inverted_index"

    fun openAlex(words: String, mode: ChatMode): List<Search.Result> = parseOpenAlex(json(openAlexAddress(words), words, mode))

    fun parseOpenAlex(body: String): List<Search.Result> {
        val a = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return objects(a).mapNotNull { o ->
            val url = o.optString("id").takeIf { it.startsWith("https://openalex.org/") } ?: return@mapNotNull null
            val title = Search.text(o.optString("display_name"))
            if (title.isEmpty()) return@mapNotNull null
            val year = o.optInt("publication_year", 0).takeIf { it > 0 }
            val abstract = o.optJSONObject("abstract_inverted_index")?.let(::abstractOf).orEmpty()
            Search.Result(title, url, snippet(listOfNotNull(year?.let { "Published $it." }, abstract.ifEmpty { null })), listOf(OPENALEX))
        }.take(MAX)
    }

    /** An abstract kept as word to places, put back in order. */
    private fun abstractOf(index: JSONObject): String {
        val at = java.util.TreeMap<Int, String>()
        for (word in index.keys()) {
            val places = index.optJSONArray(word) ?: continue
            for (i in 0 until places.length()) at[places.optInt(i)] = word
            if (at.size > 400) break
        }
        return at.values.joinToString(" ")
    }

    // Crossref: the record of almost every journal article.
    fun crossrefAddress(words: String) =
        "https://api.crossref.org/works?query=${q(words)}&rows=$MAX&filter=type:journal-article&select=DOI,title,abstract,published,container-title"

    fun crossref(words: String, mode: ChatMode): List<Search.Result> = parseCrossref(json(crossrefAddress(words), words, mode))

    fun parseCrossref(body: String): List<Search.Result> {
        val a = JSONObject(body).optJSONObject("message")?.optJSONArray("items") ?: return emptyList()
        return objects(a).mapNotNull { o ->
            val doi = o.optString("DOI").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = Search.text(o.optJSONArray("title")?.optString(0).orEmpty())
            if (title.isEmpty()) return@mapNotNull null
            val journal = Search.text(o.optJSONArray("container-title")?.optString(0).orEmpty())
            val year = o.optJSONObject("published")?.optJSONArray("date-parts")?.optJSONArray(0)?.optInt(0, 0)?.takeIf { it > 0 }
            val where = listOfNotNull(journal.ifEmpty { null }, year?.toString()).joinToString(", ")
            Search.Result(
                title, "https://search.crossref.org/search/works?q=${q(doi)}&from_ui=yes",
                snippet(listOfNotNull(where.ifEmpty { null }?.let { "$it." }, Search.text(o.optString("abstract")).removePrefix("Abstract ").ifEmpty { null })),
                listOf(CROSSREF),
            )
        }.take(MAX)
    }

    // PubMed: medicine and health research (a search for the ids, then their titles).
    fun pubMedSearchAddress(words: String) =
        "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&retmode=json&retmax=$MAX&sort=relevance&term=${q(words)}"

    fun pubMedSummaryAddress(ids: List<String>) =
        "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi?db=pubmed&retmode=json&id=${ids.joinToString(",")}"

    fun pubMed(words: String, mode: ChatMode): List<Search.Result> {
        val ids = parsePubMedIds(json(pubMedSearchAddress(words), words, mode))
        if (ids.isEmpty()) return emptyList()
        return parsePubMedSummary(json(pubMedSummaryAddress(ids), words, mode))
    }

    fun parsePubMedIds(body: String): List<String> {
        val a = JSONObject(body).optJSONObject("esearchresult")?.optJSONArray("idlist") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() && it.all(Char::isDigit) }.take(MAX)
    }

    fun parsePubMedSummary(body: String): List<Search.Result> {
        val r = JSONObject(body).optJSONObject("result") ?: return emptyList()
        val ids = r.optJSONArray("uids") ?: return emptyList()
        return (0 until ids.length()).mapNotNull { i ->
            val id = ids.optString(i).takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@mapNotNull null
            val o = r.optJSONObject(id) ?: return@mapNotNull null
            val title = Search.text(o.optString("title")).trimEnd('.')
            if (title.isEmpty()) return@mapNotNull null
            val journal = Search.text(o.optString("fulljournalname").ifEmpty { o.optString("source") })
            val date = o.optString("pubdate").trim()
            val where = listOf(journal, date).filter { it.isNotEmpty() }.joinToString(", ")
            Search.Result(title, "https://pubmed.ncbi.nlm.nih.gov/$id/", if (where.isEmpty()) "" else "$where.", listOf(PUBMED))
        }.take(MAX)
    }

    // Stack Exchange: programming questions and their answers (Stack Overflow).
    fun stackExchangeAddress(words: String) =
        "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&site=stackoverflow&pagesize=$MAX&q=${q(words)}"

    fun stackExchange(words: String, mode: ChatMode): List<Search.Result> = parseStackExchange(json(stackExchangeAddress(words), words, mode))

    fun parseStackExchange(body: String): List<Search.Result> {
        val a = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return objects(a).mapNotNull { o ->
            val url = o.optString("link").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val title = Search.text(o.optString("title"))
            if (title.isEmpty()) return@mapNotNull null
            val answers = o.optInt("answer_count")
            val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty()
            val parts = listOfNotNull(
                "Score ${o.optInt("score")}, $answers ${if (answers == 1) "answer" else "answers"}" +
                    (if (o.has("accepted_answer_id")) ", one accepted." else "."),
                tags.takeIf { it.isNotEmpty() }?.let { "Tags: ${it.joinToString(", ")}." },
            )
            Search.Result(title, url, parts.joinToString(" "), listOf(STACK_EXCHANGE))
        }.take(MAX)
    }

    // Hacker News: what people are saying (through Algolia's search of it).
    fun hackerNewsAddress(words: String) = "https://hn.algolia.com/api/v1/search?tags=story&hitsPerPage=$MAX&query=${q(words)}"

    fun hackerNews(words: String, mode: ChatMode): List<Search.Result> = parseHackerNews(json(hackerNewsAddress(words), words, mode))

    fun parseHackerNews(body: String): List<Search.Result> {
        val a = JSONObject(body).optJSONArray("hits") ?: return emptyList()
        return objects(a).mapNotNull { o ->
            val id = o.optString("objectID").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@mapNotNull null
            val title = Search.text(o.optString("title"))
            if (title.isEmpty()) return@mapNotNull null
            val comments = o.optInt("num_comments")
            val day = o.optString("created_at").take(10).takeIf { it.length == 10 }
            val link = o.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { Net.hostOf(it) }
            val text = Search.text(o.optString("story_text")).ifEmpty { null }
            val parts = listOfNotNull(
                "${o.optInt("points")} points, $comments ${if (comments == 1) "comment" else "comments"}" + (day?.let { ", $it." } ?: "."),
                link?.let { "Links to $it." }, text,
            )
            Search.Result(title, "https://news.ycombinator.com/item?id=$id", snippet(parts), listOf(HACKER_NEWS))
        }.take(MAX)
    }

    // Open Library: books, their authors and when they first came out.
    fun openLibraryAddress(words: String) = "https://openlibrary.org/search.json?q=${q(words)}&limit=$MAX&fields=key,title,author_name,first_publish_year"

    fun openLibrary(words: String, mode: ChatMode): List<Search.Result> = parseOpenLibrary(json(openLibraryAddress(words), words, mode))

    fun parseOpenLibrary(body: String): List<Search.Result> {
        val a = JSONObject(body).optJSONArray("docs") ?: return emptyList()
        return objects(a).mapNotNull { o ->
            val key = o.optString("key").takeIf { it.startsWith("/works/") && it.drop(7).all { c -> c.isLetterOrDigit() } } ?: return@mapNotNull null
            val title = Search.text(o.optString("title"))
            if (title.isEmpty()) return@mapNotNull null
            val authors = o.optJSONArray("author_name")?.let { n -> (0 until n.length()).map { Search.text(n.optString(it)) } }.orEmpty().filter { it.isNotEmpty() }.take(3)
            val year = o.optInt("first_publish_year", 0).takeIf { it > 0 }
            val parts = listOfNotNull(authors.takeIf { it.isNotEmpty() }?.let { "By ${it.joinToString(", ")}." }, year?.let { "First published $it." })
            Search.Result(title, "https://openlibrary.org$key", parts.joinToString(" "), listOf(OPEN_LIBRARY))
        }.take(MAX)
    }

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun snippet(parts: List<String>) = Search.shorten(parts.joinToString(" ").replace(Regex("\\s+"), " ").trim(), SNIPPET_CHARS)
}
