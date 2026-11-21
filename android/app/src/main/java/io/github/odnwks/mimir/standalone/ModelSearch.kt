package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.online.ChatMode
import io.github.odnwks.mimir.online.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Model Store's Refresh: a ready-made search of Hugging Face's public listing for open models
 * that this phone can really run (no typing). It only runs when the owner taps Refresh: nothing here
 * is scheduled. Requests carry no personal data (see [Net]) and go through the PC when it is
 * paired and allows them (`huggingface.co/api/models` is on both lists), else straight from the phone.
 */
object ModelSearch {
    const val SHOW_AT_MOST = 6
    /** How many search hits get their file list read (each is one request). */
    const val LOOK_AT = 20
    const val FREE_EXTRA_BYTES = 1_000_000_000L
    val TERMS = listOf("abliterated", "uncensored", "heretic")
    const val SORRY = "Couldn't look just now. Your list is unchanged: tap Refresh to try again."
    const val NOTHING = "Nothing new that fits this phone right now."

    /** What a search needs to know about this phone. */
    data class Phone(val ramBytes: Long, val freeBytes: Long)

    fun searchUrl(term: String) =
        "https://huggingface.co/api/models?search=$term&filter=gguf&sort=downloads&direction=-1&limit=40"

    fun filesUrl(repo: String) = "https://huggingface.co/api/models/$repo/tree/main"

    /**
     * The biggest download this phone can run: the same rule as [Fit.of] (about 55% of the memory,
     * a model needing its file plus a little room).
     */
    fun limitBytes(ramBytes: Long): Long = if (ramBytes <= 0) 0 else ((ramBytes * 0.55 - 300_000_000) / 1.05).toLong().coerceAtLeast(0)

    /** "It can run models up to about 5.5 GB." */
    fun limitWords(ramBytes: Long): String = "%.1f".format(limitBytes(ramBytes) / 1e9)

    /** A search hit, before its files are read. */
    internal data class Hit(val repo: String, val downloads: Long, val created: String)

    /** The hits of one search answer, in the order given. */
    internal fun hits(json: String): List<Hit> {
        val a = JSONArray(json)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { o.optString("modelId") }
            if (id.count { it == '/' } != 1 || !id.all { it.isLetterOrDigit() || it in "-_./" }) null
            else Hit(id, o.optLong("downloads"), o.optString("createdAt"))
        }
    }

    /** The format its family needs, or null when the phone's engine can't run the family. */
    internal fun formatOf(repo: String): Format? {
        val r = repo.lowercase()
        return when {
            "gemma-4" in r || "gemma4" in r -> Format.Gemma4
            "qwen" in r -> Format.ChatMl
            else -> null
        }
    }

    private data class Blob(val path: String, val bytes: Long, val sha: String)

    private fun blobs(json: String): List<Blob> {
        val a = JSONArray(json)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type") != "file") return@mapNotNull null
            val path = o.optString("path")
            val lfs = o.optJSONObject("lfs")
            val sha = lfs?.optString("oid")?.lowercase().orEmpty()
            val bytes = lfs?.optLong("size", 0) ?: 0
            if (!path.endsWith(".gguf", true) || '/' in path || bytes <= 0 || sha.length != 64 || !sha.all { it in "0123456789abcdef" }) null
            else Blob(path, bytes, sha)
        }
    }

    /** Q4_K_M, else Q4_K_S, else Q4_0; whole files only (no split parts, no vision part). */
    private fun pickModel(all: List<Blob>): Blob? {
        // Not the vision part, not a draft or MTP helper file (it isn't a model alone), not one piece of a split model.
        val plain = all.filter { f -> listOf("mmproj", "mtp", "draft").none { it in f.path.lowercase() } && !Regex("-\\d{5}-of-\\d{5}").containsMatchIn(f.path) }
        for (q in listOf("q4_k_m", "q4_k_s", "q4_0")) plain.firstOrNull { q in it.path.lowercase() }?.let { return it }
        return null
    }

    private fun pickVision(all: List<Blob>): Blob? {
        val m = all.filter { "mmproj" in it.path.lowercase() }
        return m.firstOrNull { "f16" in it.path.lowercase() && "bf16" !in it.path.lowercase() } ?: m.minByOrNull { it.bytes }
    }

    /** Quick, Balanced or Smartest by how much of what the phone can run it takes. */
    internal fun band(totalBytes: Long, limit: Long): String {
        val r = totalBytes.toDouble() / limit.coerceAtLeast(1)
        return when {
            r <= 0.45 -> "Quick"
            r <= 0.75 -> "Balanced"
            else -> "Smartest"
        }
    }

    private fun goodAt(band: String, sees: Boolean) = when (band) {
        "Quick" -> "Fast answers, easy on memory"
        "Balanced" -> "A good all-rounder for everyday questions"
        else -> "The most capable one that fits this phone"
    } + if (sees) "; can look at photos" else ""

    private fun safeId(repo: String, file: String) =
        "found-" + (repo.replace('/', '-') + "-" + file.removeSuffix(".gguf").takeLast(24)).lowercase().map { if (it.isLetterOrDigit() || it in "-_.") it else '-' }.joinToString("")

    /** One model for this phone from [repo]'s files, or null when none fits or the files aren't usable. */
    internal fun modelFrom(repo: String, files: String, phone: Phone, now: Long): LocalModel? {
        val format = formatOf(repo) ?: return null
        val all = blobs(files)
        val m = pickModel(all) ?: return null
        val v = pickVision(all)
        val total = m.bytes + (v?.bytes ?: 0)
        if (total > limitBytes(phone.ramBytes) || phone.freeBytes < total + FREE_EXTRA_BYTES) return null
        val band = band(total, limitBytes(phone.ramBytes))
        val name = repo.substringAfter('/').removeSuffix("-GGUF").removeSuffix("-gguf").replace('-', ' ')
        val base = "https://huggingface.co/$repo/resolve/main/"
        return LocalModel(
            id = safeId(repo, m.path), name = name, note = goodAt(band, v != null),
            url = base + m.path, bytes = m.bytes, sha256 = m.sha, format = format, refusalsRemoved = true,
            vision = v?.let { VisionPart(base + it.path, it.bytes, it.sha) },
            plain = band + if (v != null) " · sees photos" else "", source = repo, foundAt = now,
        )
    }

    /**
     * The search. [fetch] reads one address (the app's [Net]; a recording in checks). Returns up to
     * [SHOW_AT_MOST] models not in [known] yet, most downloaded first and newer first among equals.
     * Throws when no search could be read at all.
     */
    fun search(phone: Phone, known: List<LocalModel>, now: Long, fetch: (String) -> String): List<LocalModel> {
        if (limitBytes(phone.ramBytes) <= 0) return emptyList()
        var asked = 0
        var failed = 0
        val found = LinkedHashMap<String, Hit>()
        for (t in TERMS) {
            asked++
            val list = runCatching { hits(fetch(searchUrl(t))) }.getOrElse { failed++; emptyList() }
            list.forEach { found.putIfAbsent(it.repo, it) }
        }
        if (failed == asked) throw java.io.IOException("no search could be read")
        val have = known.flatMap { listOf(it.source.lowercase(), repoOf(it.url)) }.filter { it.isNotEmpty() }.toSet()
        val look = found.values
            .filter { formatOf(it.repo) != null && it.repo.lowercase() !in have && !notChat(it.repo) && !tooManyParams(it.repo, phone) }
            .sortedWith(compareByDescending<Hit> { it.downloads }.thenByDescending { it.created })
            .take(LOOK_AT)
        val out = ArrayList<LocalModel>()
        for (h in look) {
            if (out.size >= SHOW_AT_MOST) break
            val lm = runCatching { modelFrom(h.repo, fetch(filesUrl(h.repo)), phone, now) }.getOrNull() ?: continue
            if (known.none { it.id == lm.id || it.sha256 == lm.sha256 } && out.none { it.sha256 == lm.sha256 }) out += lm
        }
        return out
    }

    /** Pictures, embeddings and the like, not chat models. */
    private fun notChat(repo: String) = listOf("image", "embed", "rerank", "diffusion").any { it in repo.lowercase() }

    /** "…-27B-…" in a name: a Q4 file of that many billion weights is about 0.62 GB each; far over the limit is skipped without a request. */
    internal fun tooManyParams(repo: String, phone: Phone): Boolean {
        val b = Regex("(?<![A-Za-z0-9.])(\\d+(?:\\.\\d+)?)[bB](?![A-Za-z])").findAll(repo.substringAfter('/')).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull() ?: return false
        return b * 0.62e9 > limitBytes(phone.ramBytes) * 1.15
    }

    private fun repoOf(url: String) = Regex("^https://huggingface.co/([^/]+/[^/]+)/").find(url)?.groupValues?.get(1)?.lowercase().orEmpty()

    // ---- what the store shows ----

    data class State(
        val searching: Boolean = false,
        /** Plain words for the last try: a problem, or that nothing new fits; null when it found something or hasn't run. */
        val message: String? = null,
        val error: Boolean = false,
        /** When it last finished (ms since 1970; 0: never). */
        val lastSearched: Long = 0,
        /** The models the last search added: shown with a "New" tag. */
        val newIds: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    @Volatile private var file: File? = null

    /** Reads what earlier searches found (the app's data folder, at start). */
    fun load(f: File) {
        file = f
        runCatching {
            val o = JSONObject(f.readText())
            val a = o.getJSONArray("found")
            Catalog.found = (0 until a.length()).mapNotNull { LocalModel.fromJson(a.getJSONObject(it)) }
            _state.update { it.copy(lastSearched = o.optLong("last_searched", 0)) }
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val a = JSONArray(); Catalog.found.forEach { a.put(it.toJson()) }
            f.writeText(JSONObject().put("found", a).put("last_searched", _state.value.lastSearched).toString())
        }
    }

    /** Checks: forget everything searched. */
    fun reset() { Catalog.found = emptyList(); _state.value = State(); file = null }

    /**
     * Refresh: starts the search in the background (not at all when one is running); the list stays
     * usable. New finds are added to the top of the store and kept; a problem leaves the list as it was.
     */
    fun refresh(
        phone: Phone, now: () -> Long = System::currentTimeMillis,
        fetch: (String) -> String = ::fetchOnline,
        run: (() -> Unit) -> Unit = { Thread(it, "model-search").apply { isDaemon = true }.start() },
    ) {
        synchronized(this) {
            if (_state.value.searching) return
            _state.update { it.copy(searching = true, message = null, error = false) }
        }
        run {
            val at = now()
            val result = runCatching { search(phone, Catalog.models, at, fetch) }
            result.onSuccess { new ->
                if (new.isNotEmpty()) Catalog.found = new + Catalog.found
                _state.update {
                    it.copy(searching = false, lastSearched = at, newIds = new.map { m -> m.id }.toSet(), message = if (new.isEmpty()) NOTHING else null, error = false)
                }
                save()
            }.onFailure {
                _state.update { s -> s.copy(searching = false, message = SORRY, error = true) }
            }
        }
    }

    /** One address, read through [Net] (the PC when paired, else this phone). */
    fun fetchOnline(address: String): String =
        Net.get(address, ChatMode.Normal, words = "Looking for models", site = "huggingface.co", accept = setOf("application/json")).text

    /** "today at 7:30 am", "yesterday at 7:30 am", "18 Nov at 7:30 am", or "never". */
    fun lastSearchedWords(at: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        if (at <= 0) return "never"
        fun fmt(p: String) = SimpleDateFormat(p, locale).apply { timeZone = zone }.format(Date(at))
        val day = { ms: Long -> Math.floorDiv(ms + zone.getOffset(ms), 86_400_000L) }
        val time = fmt("h:mm a").lowercase()
        return when (day(now) - day(at)) {
            0L -> "today at $time"
            1L -> "yesterday at $time"
            else -> "${fmt("d MMM")} at $time"
        }
    }
}
