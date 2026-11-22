package io.github.odnwks.mimir.online

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

/**
 * The one way Mímir reaches the internet. Every lookup goes through [get], which refuses in an
 * offline Whisper (and when lookups are off), refuses anything but HTTPS, refuses addresses on the
 * home network or the phone itself (checked after every redirect), sends no cookies, no Referer
 * and an honest app name, and stops at a size and time limit. Each lookup is listed in [LookupLog].
 */
object Net {
    class Refused(why: String) : Exception(why)

    /** What came back: the text, how many bytes were received, and the type. */
    data class Got(val text: String, val bytes: Long, val type: String)

    /** One request as the transport sees it (no body: it can only read). */
    data class Request(val url: URL, val headers: Map<String, String>, val timeoutMs: Int, val maxBytes: Int)

    /** A reply before it's checked: a redirect's next address, or the body. */
    data class Reply(val status: Int, val location: String?, val type: String, val body: ByteArray, val connectMs: Long = -1)

    const val TAG = "MimirLookup"

    /** The app name sites see: honest, with nothing about the owner. */
    const val AGENT = "Mimir (Mímir phone app; no personal data)"

    /** Swapped in checks: makes the request. */
    var transport: (Request) -> Reply = ::httpGet

    /** Swapped in checks: the addresses a name stands for. */
    var resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }

    /**
     * Reads [address] for a chat in [mode], counting it in [LookupLog] as a lookup of [words] on
     * [site]. Throws [Refused] when it may not, or [Failed] (with the cause in plain words) when it
     * can't. In a normal chat, an address the PC may also fetch ([pcMay]: the weather and the
     * encyclopedias) is asked both ways at once, from this phone and through the PC, and the first
     * good answer is used: one slow or failed way is never the answer. Everything together stays
     * within [BUDGET_MS] (or a shorter [within]): even the site's name lookup, which can stall for
     * a long time on a bad network, is done in the way's own thread and never waited on past that.
     */
    fun get(
        address: String, mode: ChatMode, words: String, site: String = hostOf(address),
        accept: Set<String> = setOf("application/json", "text/html", "text/plain"),
        maxBytes: Int = 2_000_000, timeoutMs: Int = READ_MS,
    ): Got {
        if (!Online.allowed(mode)) throw Refused(if (mode == ChatMode.Whisper) "a Whisper is offline" else "looking things up is off")
        // Kept answers: only in a normal chat (a Whisper keeps nothing).
        val keep = if (mode == ChatMode.Normal) keepFor(address) else 0L
        if (keep > 0) synchronized(cache) { cache[address]?.let { (at, got) -> if (clock() - at < keep && got.type in accept) return got } }
        var url = URL(address)
        var hops = 0
        // A Whisper with internet goes through Tor only, never directly: no Tor, no lookup.
        val tor = mode == ChatMode.WhisperWeb
        val end = minOf(now() + BUDGET_MS, deadline.get() ?: Long.MAX_VALUE)
        while (true) {
            if (tor) checkForTor(url) else checkShape(url)
            val req = Request(url, mapOf("User-Agent" to AGENT, "Accept-Language" to "en", "Accept" to accept.joinToString()), if (tor) timeoutMs * 3 else timeoutMs, maxBytes)
            val (r, route, asked) = if (tor) Triple(torTransport(req), LookupLog.Route.Tor, listOf(LookupLog.Route.Tor)) else fetch(req, mode, end)
            if (r.status in 300..399 && r.location != null) {
                if (++hops > 2) throw Failed("the site sent the app round in circles")
                url = URL(url, r.location)
                continue
            }
            if (r.status !in 200..299) throw Failed(if (r.status >= 500) "the site is having trouble just now" else "the site wouldn't show that page")
            val type = r.type.substringBefore(';').trim().lowercase()
            if (type !in accept) throw Refused("not a page that can be read ($type)")
            val text = String(r.body, Charsets.UTF_8)
            // Both ways were asked: both are listed in the Privacy check, honestly.
            asked.filter { it != route }.forEach { LookupLog.add(LookupLog.Entry(clock(), site, words, it, 0), mode) }
            LookupLog.add(LookupLog.Entry(clock(), site, words, route, r.body.size.toLong()), mode)
            val got = Got(text, r.body.size.toLong(), type)
            if (keep > 0) synchronized(cache) { cache[address] = clock() to got }
            return got
        }
    }

    /**
     * About 2.5 s to reach a site and 6 s for a whole lookup, both ways and a retry included
     * (owner, 2026-11-19: fast, 6 s at most, never hanging). A request reads for what is left.
     */
    const val CONNECT_MS = 2_500
    const val READ_MS = 6_000
    const val BUDGET_MS = 6_000L

    /** The message of a timeout inside the app (never shown: [plain] words it). */
    private const val SLOW = "slow"

    /** A lookup that couldn't be done, with the cause in plain words ("this phone has no signal"). */
    class Failed(why: String) : java.io.IOException(why)

    /** The PC can't be asked right now (not connected, or it didn't answer). */
    class PcAway(why: String = "your PC is away") : java.io.IOException(why)

    /**
     * Through the PC (set by the app while paired; checks swap it): the PC fetches the address
     * itself, only ever from its own fixed list of sites ([pcMay]). Throws when it can't.
     */
    @Volatile var viaPc: ((Request) -> Reply)? = null

    /** Whether this phone has a network at all (set by the app; checks swap it). */
    @Volatile var phoneOnline: () -> Boolean = { true }

    /** Swapped in checks: the time, for the budget and the kept answers. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    /**
     * The sites the PC fetches for the phone, and the start of the addresses it may ask there:
     * the same list as the PC's own (crates/core/src/lookup.rs), so the PC is never an open proxy.
     */
    val PC_SITES = listOf(
        "geocoding-api.open-meteo.com" to "/v1/search",
        "api.open-meteo.com" to "/v1/forecast",
        "api.met.no" to "/weatherapi/locationforecast/2.0/",
        "api.weather.gov" to "/points/",
        "api.weather.gov" to "/gridpoints/",
        "en.wikipedia.org" to "/w/api.php",
        "grokipedia.com" to "/page/",
        "huggingface.co" to "/api/models",
    )

    fun pcMay(url: URL): Boolean = url.protocol == "https" && url.port == -1 && url.userInfo == null &&
        PC_SITES.any { (host, path) -> url.host.equals(host, ignoreCase = true) && url.path.startsWith(path) }

    /** Everything in [f] together takes at most [ms] (each lookup inside gets what's left). */
    fun <T> within(ms: Long, f: () -> T): T {
        val before = deadline.get()
        deadline.set(minOf(now() + ms, before ?: Long.MAX_VALUE))
        try { return f() } finally { deadline.set(before) }
    }

    private val deadline = ThreadLocal<Long?>()

    /** The real time, for budgets (the swappable [clock] is for kept answers only). */
    private fun now() = System.currentTimeMillis()

    /**
     * How long an answer from [address] is kept: places and encyclopedias a day. (The weather is
     * kept 15 min by [Weather] itself, under the place, whichever source answered.)
     */
    fun keepFor(address: String): Long = when {
        address.startsWith("https://geocoding-api.open-meteo.com/") || address.startsWith("https://nominatim.openstreetmap.org/") -> 24 * 3_600_000L
        address.startsWith("https://en.wikipedia.org/") || address.startsWith("https://grokipedia.com/") -> 24 * 3_600_000L
        else -> 0L
    }

    private val cache = object : LinkedHashMap<String, Pair<Long, Got>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Got>>?) = size > 60
    }

    /** Forgets the kept answers (checks start clean). */
    fun forget() {
        synchronized(cache) { cache.clear() }
        synchronized(kept) { kept.clear() }
    }

    private val kept = object : LinkedHashMap<String, Pair<Long, Got>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Got>>?) = size > 60
    }

    /** An answer kept under [key] (not an address) by a lookup that combines several sites, if still fresh. */
    fun recall(key: String): Got? = synchronized(kept) { kept[key]?.takeIf { clock() < it.first }?.second }

    /** Keeps [got] under [key] for [ttlMs] (a normal chat only: a Whisper keeps nothing). */
    fun remember(key: String, got: Got, ttlMs: Long) = synchronized(kept) { kept[key] = clock() + ttlMs to got }

    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "mimir-lookup").apply { isDaemon = true } }

    /** When this lookup must be over: [BUDGET_MS] from now, or sooner when a [within] is running. */
    fun endOfBudget(): Long = minOf(now() + BUDGET_MS, deadline.get() ?: Long.MAX_VALUE)

    /** Whether lookups are allowed for [mode] at all; throws [Refused] in plain words when not. */
    fun requireAllowed(mode: ChatMode) {
        if (!Online.allowed(mode)) throw Refused(if (mode == ChatMode.Whisper) "a Whisper is offline" else "looking things up is off")
    }

    /** Runs [f] on a lookup thread that keeps the caller's time limit ([within]). */
    fun launch(f: () -> Unit) {
        val d = deadline.get()
        pool.execute {
            deadline.set(d)
            try { f() } finally { deadline.remove() }
        }
    }

    /** [f] on a lookup thread, with the caller's time limit; read it with [await]. */
    fun <T> async(f: () -> T): java.util.concurrent.Future<T> {
        val task = java.util.concurrent.FutureTask(f)
        launch { task.run() }
        return task
    }

    /** The result of [async], waiting no longer than the lookup's time limit allows. */
    fun <T> await(f: java.util.concurrent.Future<T>): T {
        val left = endOfBudget() - now()
        try {
            return if (left > 0) f.get(left, java.util.concurrent.TimeUnit.MILLISECONDS) else throw java.util.concurrent.TimeoutException()
        } catch (e: java.util.concurrent.TimeoutException) {
            throw java.net.SocketTimeoutException(SLOW)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Several ways to get the same thing, all asked at once: the first answer that has something
     * is used, and the others are left to finish by themselves. A way that answers "nothing
     * there" (null) only counts when no other way has something. When every way failed, the
     * causes are said in plain words ([Failed]); a [Refused] is passed on at once.
     */
    fun <T : Any> firstGood(ways: List<() -> T?>): T? {
        val end = endOfBudget()
        val answers = java.util.concurrent.LinkedBlockingQueue<Result<T?>>()
        ways.forEach { way -> launch { answers.put(runCatching(way)) } }
        var waiting = ways.size
        var nothing = false
        val problems = mutableListOf<Throwable>()
        while (waiting > 0) {
            val left = end - now()
            val r = (if (left > 0) answers.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) else null)
            if (r == null) {
                problems += java.net.SocketTimeoutException(SLOW)
                break
            }
            waiting--
            val v = r.getOrElse { e ->
                if (e is Refused) throw e
                problems += e
                null
            }
            if (v != null) return v
            if (r.isSuccess) nothing = true
        }
        if (nothing) return null
        throw Failed(plainAll(problems))
    }

    /** Causes in plain words, once each ("this phone has no signal" when that is the whole story). */
    fun plainAll(problems: List<Throwable>): String {
        val noSignal = !phoneOnline()
        if (noSignal) return "this phone has no signal"
        return problems.map { plain(it, false) }.distinct().joinToString(", and ").ifEmpty { "it didn't answer" }
    }

    /**
     * One request, the fastest way that works: from this phone and, when the PC may fetch it,
     * through the PC at the same time. Each way does everything itself in its own thread (the
     * site's name lookup too), so the waiting here is always bounded by the time left. With only
     * one way, a failure is tried once more if time is left. Throws [Failed] with every way's
     * cause in plain words.
     */
    private fun fetch(req: Request, mode: ChatMode, end: Long): Triple<Reply, LookupLog.Route, List<LookupLog.Route>> {
        val pc = viaPc.takeIf { mode == ChatMode.Normal && pcMay(req.url) }
        val ways = listOfNotNull(LookupLog.Route.Phone, pc?.let { LookupLog.Route.Pc })
        val answers = java.util.concurrent.LinkedBlockingQueue<Pair<LookupLog.Route, Result<Reply>>>()
        /** What this request may still take, from now. */
        fun room(): Request {
            val left = end - now()
            if (left <= 0) throw java.net.SocketTimeoutException(SLOW)
            return req.copy(timeoutMs = minOf(req.timeoutMs.toLong(), left).toInt())
        }
        fun start(way: LookupLog.Route) {
            pool.execute {
                val t0 = now()
                var dnsMs = -1L
                val got = runCatching {
                    if (way == LookupLog.Route.Pc) pc!!(room())
                    else {
                        checkAddress(req.url)
                        dnsMs = now() - t0
                        transport(room())
                    }
                }
                // Normal chats only: a Whisper logs nothing about where it went. Never the words looked up.
                if (mode == ChatMode.Normal) runCatching {
                    val total = now() - t0
                    android.util.Log.i(TAG, "${req.url.host} ${way.words}: dns ${if (dnsMs >= 0) "$dnsMs ms" else "-"}, " +
                        "connect ${got.getOrNull()?.connectMs?.takeIf { it >= 0 }?.let { "$it ms" } ?: "-"}, total $total ms, " +
                        got.fold({ "status ${it.status}, ${it.body.size} bytes" }, { "failed (${it.javaClass.simpleName}: ${safe(it.message)})" }))
                }
                answers.put(way to got)
            }
        }
        ways.forEach(::start)
        var waiting = ways.size
        var retried = false
        val problems = LinkedHashMap<LookupLog.Route, Throwable>()
        var badAnswer: Pair<Reply, LookupLog.Route>? = null
        while (waiting > 0) {
            val left = end - now()
            val got = if (left > 0) answers.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) else null
            if (got == null) {
                // Out of time: the ways still out are counted as too slow.
                ways.filter { it !in problems }.forEach { problems[it] = java.net.SocketTimeoutException(SLOW) }
                break
            }
            waiting--
            val (way, result) = got
            val reply = result.getOrNull()
            // The home network or the phone itself: refused, whatever the other way says.
            (result.exceptionOrNull() as? Refused)?.let { throw it }
            // A site that's down on one way may answer on the other.
            if (reply != null && reply.status < 500) return Triple(reply, way, ways)
            if (reply != null) badAnswer = reply to way
            problems[way] = result.exceptionOrNull() ?: Failed("the site is having trouble just now")
            // Every way failed: this phone tries once more, if there's time for it.
            if (waiting == 0 && !retried && end - now() > 2_000 && problems[LookupLog.Route.Phone] != null && badAnswer == null) {
                retried = true
                problems.remove(LookupLog.Route.Phone)
                start(LookupLog.Route.Phone)
                waiting++
            }
        }
        badAnswer?.let { return Triple(it.first, it.second, ways) }
        throw Failed(why(problems))
    }

    /** An error's words for the log: no web addresses (they hold the coordinates or the words looked up), and short. */
    private fun safe(m: String?): String = m.orEmpty().replace(Regex("https?://\\S+"), "<address>").take(80)

    /** The answer's words when a lookup in the paired chat failed (with "Try again" under it). */
    fun sorry(e: Throwable): String = when (e) {
        is Failed, is PcAway -> "Couldn't look that up just now (${said(e)}). Try again, or answer without looking it up."
        is Refused -> "That isn't looked up (${said(e)}). Answer without looking it up, or ask in another way."
        else -> "Couldn't look that up just now (${plain(e, !phoneOnline())}). Try again, or answer without looking it up."
    }

    /** A lookup problem's own words: [Failed], [Refused] and [PcAway] carry plain sentences, never a system message. */
    private fun said(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: "it gave no answer"

    /** What went wrong, in plain words: "this phone has no signal, and your PC is away". */
    fun why(problems: Map<LookupLog.Route, Throwable>): String {
        val noSignal = !phoneOnline()
        // With no signal here, that's the whole story: the PC is fine, the phone just can't reach it (QA I1).
        if (noSignal) return "this phone has no signal"
        // This phone's own problem first, then the PC's.
        val parts = problems.entries.sortedBy { it.key != LookupLog.Route.Phone }.map { (way, e) ->
            when {
                way == LookupLog.Route.Pc -> when {
                    e is PcAway -> e.message ?: "your PC is away"
                    e is java.net.SocketTimeoutException -> "your PC didn't get an answer in time either"
                    else -> "your PC had no luck either (${plain(e, false)})"
                }
                else -> plain(e, noSignal)
            }
        }.distinct()
        return parts.joinToString(", and ")
    }

    /** One way's problem in plain words. */
    fun plain(e: Throwable, noSignal: Boolean): String = when {
        noSignal -> "this phone has no signal"
        e is Failed || e is PcAway || e is Refused -> e.message ?: "it gave no answer"
        e is java.net.UnknownHostException -> "this phone has no internet just now (a weak signal, or no site by that name)"
        e is java.net.SocketTimeoutException || e.message?.contains("timeout", true) == true -> "the connection is too slow just now (a weak signal?)"
        e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "the site is out of reach just now"
        e is javax.net.ssl.SSLException -> "the site's secure link broke (a Wi-Fi sign-in page may be in the way)"
        else -> "it gave no answer"
    }

    /** Through Tor (set by the app; checks swap it). It throws when Tor can't be reached. */
    var torTransport: (Request) -> Reply = { throw Refused("the hidden route is off just now") }

    /**
     * Through Tor the site's name is looked up by Tor, never by the phone (that would tell the
     * phone's name lookup service where the Whisper goes): only addresses written as numbers or
     * local names are checked here.
     */
    private fun checkForTor(url: URL) {
        if (url.protocol != "https") throw Refused("only secure (https) addresses are read")
        val host = url.host.orEmpty().trim('[', ']')
        if (host.isEmpty() || host.equals("localhost", true) || host.endsWith(".local", true) || host.endsWith(".lan", true) || host.endsWith(".home.arpa", true)) {
            throw Refused("that address is on this phone or the home network")
        }
        val literal = host.matches(Regex("[0-9.]+")) || host.contains(':')
        if (literal && runCatching { private(InetAddress.getByName(host)) }.getOrDefault(true)) throw Refused("that address is on this phone or the home network")
    }

    /** HTTPS only, and no local names (no name lookup: that is [checkAddress], in the way's own thread). */
    private fun checkShape(url: URL) {
        if (url.protocol != "https") throw Refused("only secure (https) addresses are read")
        val host = url.host.orEmpty()
        if (host.isEmpty() || host.equals("localhost", true) || host.endsWith(".local", true)) throw Refused("that address is on this phone or the home network")
    }

    /**
     * Never the home network or the phone itself: the site's name is looked up and every address it
     * stands for checked. Runs in a way's own thread, because a name lookup on a bad network can
     * stall for a long time. Where the phone can't look the name up, the other way (the PC) may still get it.
     */
    private fun checkAddress(url: URL) {
        val host = url.host
        val all = runCatching { resolve(host) }.getOrElse { throw java.net.UnknownHostException("no site by the name $host") }
        if (all.isEmpty() || all.any { private(it) }) throw Refused("that address is on this phone or the home network")
    }

    fun private(a: InetAddress): Boolean =
        a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress ||
            (a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc) ||
            // 100.64.0.0/10 (carrier networks) and 169.254 are not the open internet either.
            (a.address.size == 4 && a.address[0].toInt() and 0xff == 100 && (a.address[1].toInt() and 0xc0) == 64)

    /**
     * The hosts a chat (and the owner's own key) may be sent to, for one purpose only: an online
     * model the owner chose and pasted a key for. Nothing but the chat itself goes there. The
     * owner's own service is added when it is set up ([allowAnswerHost]).
     */
    val ANSWER_HOSTS = setOf("openrouter.ai", "api.groq.com")
    private val ownAnswerHosts = java.util.Collections.synchronizedSet(HashSet<String>())

    fun allowAnswerHost(host: String) { ownAnswerHosts += host.lowercase() }

    /** The owner's own service is no longer set up (or its key failed the check): its address may not be used any more. */
    fun forgetAnswerHost(host: String) { ownAnswerHosts -= host.lowercase() }

    fun answerHostAllowed(host: String) = host.lowercase() in ANSWER_HOSTS || host.lowercase() in ownAnswerHosts

    /** Throws [Refused] unless [url] is a secure address of an allowed online-model host (and, for the owner's own, not on the home network). */
    fun answerGuard(url: URL) {
        val host = url.host.orEmpty().lowercase()
        val builtIn = host in ANSWER_HOSTS
        if (!builtIn && host !in ownAnswerHosts) throw Refused("that service is not set up")
        if (url.protocol != "https") throw Refused("only secure (https) addresses are used")
        if (!builtIn) { checkForTor(url); checkAddress(url) }
    }

    fun hostOf(address: String): String = runCatching { URL(address).host.removePrefix("www.") }.getOrDefault(address)

    private fun httpGet(r: Request): Reply {
        val c = r.url.openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.useCaches = false
            // A fresh connection each time: a kept one can go quiet on mobile data (the network
            // moved on), and reading from it only ends at the read time limit ("Read timed out").
            c.setRequestProperty("Connection", "close")
            c.connectTimeout = minOf(CONNECT_MS, r.timeoutMs)
            c.readTimeout = r.timeoutMs
            r.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val t0 = System.currentTimeMillis()
            c.connect()
            val connectMs = System.currentTimeMillis() - t0
            val status = c.responseCode
            val location = c.getHeaderField("Location")
            val type = c.contentType.orEmpty()
            if (status !in 200..299) return Reply(status, location, type, ByteArray(0), connectMs)
            val out = ByteArrayOutputStream()
            val until = System.currentTimeMillis() + r.timeoutMs
            c.inputStream.use { inp ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > r.maxBytes) throw java.io.IOException("the page is too big")
                    if (System.currentTimeMillis() > until) throw java.net.SocketTimeoutException("the site was too slow")
                }
            }
            return Reply(status, location, type, out.toByteArray(), connectMs)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Fetched words as they go to the model: anything that looks like the chat's own markers (which
     * could make a page pose as a message) is taken out.
     */
    fun clean(text: String): String {
        var t = text
        for (m in MARKERS) t = t.replace(m, "", ignoreCase = true)
        return t.replace(Regex("<\\|[^>]{0,20}>|<[^>]{0,20}\\|>"), "")
    }

    private val MARKERS = listOf("<|im_start|>", "<|im_end|>", "<|turn>", "<turn|>", "<__media__>", "<|endoftext|>", "<think>", "</think>", "<start_of_turn>", "<end_of_turn>")
}
