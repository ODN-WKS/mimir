package io.github.odnwks.mimir.online

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** A service that speaks the OpenAI-style chat API: where it is, and where its key is got. */
class Service(val id: String, val name: String, val company: String, val base: String, val keysUrl: String?) {
    /** "Free, fast. Your messages go to <company>." */
    val who: String get() = "Free, fast. Your messages go to $company."
}

/** What testing a pasted key found. */
sealed interface KeyTest {
    /** The key works; these are the models it may use (free ones for OpenRouter). */
    data class Works(val models: List<String>) : KeyTest
    data object BadKey : KeyTest
    data object Away : KeyTest
    data class Other(val words: String) : KeyTest
}

/** A reply, and the model that wrote it. */
class Answer(val text: String, val model: String)

/** A problem in plain words, ready to show. */
class Sorry(val words: String) : Exception(words)

/**
 * Free online models for a phone that can't run one (or whose owner would rather): OpenRouter,
 * Groq, or any OpenAI-style service, with a key the owner pasted. Requests go straight from the
 * phone to that service with the chat and nothing else; the key stays on the phone, locked
 * ([KeyVault]), and is never logged. A Whisper never uses this.
 */
object OnlineModels {
    val OPENROUTER = Service("openrouter", "OpenRouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys")
    val GROQ = Service("groq", "Groq", "Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys")
    val BUILT_IN = listOf(OPENROUTER, GROQ)

    /** The owner's own service: its address is where the chat and key go. */
    fun own(base: String): Service {
        val host = runCatching { URL(base).host }.getOrDefault(base)
        return Service("own", host, host, base.trimEnd('/'), null)
    }

    fun serviceOf(s: Setup): Service = BUILT_IN.firstOrNull { it.id == s.id } ?: Service("own", s.name, s.name, s.base, null)

    private const val TAG = "MimirOnlineModel"
    const val WHISPER_NO_ONLINE = "A Whisper can't use an online model. Choose who answers instead, then start the Whisper."
    const val BAD_KEY = "That key didn't work. Copy it again from their website."
    const val PRIVACY = "Know before you choose: with an online model, your messages go to that company. With a PC or a phone model, they never do."
    const val KEPT = "Your key is kept only on this phone, locked. Remove it any time."
    fun away(s: String) = "Couldn't reach $s just now. Check the connection and try again."
    fun limit(s: String) = "$s says today's free limit is used up. Try again later, or pick another answerer."
    fun gone() = "That model isn't available any more. Pick another one."

    // ---------- What is set up, and who answers ----------

    /** What is set up (with keys, inside the app only) and whether the online model is the one answering. */
    data class State(val setups: List<Setup> = emptyList(), val answering: String? = null, /** Services whose key can't be opened any more: shown as "set up again". */ val broken: Set<String> = emptySet())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var vault: KeyVault? = null
    private var flag: File? = null

    /** Opens the vault in [dir]; [sealer] locks it (the Android Keystore on the phone). */
    fun init(dir: File, sealer: Sealer = KeystoreSealer()) {
        val v = KeyVault(File(dir, "online-keys.bin"), sealer)
        vault = v
        flag = File(dir, "online-answers.txt")
        val setups = v.all()
        val broken = v.needSetupAgain()
        setups.filter { it.id == "own" }.forEach { Net.allowAnswerHost(runCatching { URL(it.base).host }.getOrDefault("")) }
        val on = flag?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { id -> setups.any { it.id == id } }
        // Nothing answers with a key that can't be opened: the flag goes too, so the two never disagree.
        if (on == null) flag?.delete()
        _state.value = State(setups, on, broken)
    }

    /** Opens the vault once (the first screen that needs it). */
    fun ensure(dir: File) { if (vault == null) init(dir) }

    /** The service answering now, or null (this phone or the PC answers). */
    fun answering(): Setup? = _state.value.let { s -> s.setups.firstOrNull { it.id == s.answering } }

    fun setup(id: String): Setup? = _state.value.setups.firstOrNull { it.id == id }

    /** Keeps a tested key and its choice of model. */
    @Synchronized fun save(s: Setup) {
        if (s.id == "own") Net.allowAnswerHost(hostOf(s.base))
        vault?.put(s)
        _state.update { st -> st.copy(setups = st.setups.filter { it.id != s.id } + s, broken = st.broken - s.id) }
    }

    /** A model pick made while a reply was being written: kept only if the key is still set up (it may have been removed meanwhile). */
    @Synchronized private fun saveIfStill(s: Setup) { if (_state.value.setups.any { it.id == s.id && it.key == s.key }) save(s) }

    private fun hostOf(base: String) = runCatching { URL(base).host }.getOrDefault("")

    /** "Online model" chosen in Who should answer (only with a service set up). */
    @Synchronized fun answerWith(id: String?) {
        if (id != null && setup(id) == null) return
        flag?.let { if (id == null) it.delete() else it.writeText(id) }
        _state.update { it.copy(answering = id) }
    }

    /** Remove key: the key is forgotten, and if it was answering, this phone or the PC answers again. */
    @Synchronized fun remove(id: String) {
        setup(id)?.takeIf { it.id == "own" }?.let { Net.forgetAnswerHost(hostOf(it.base)) }
        vault?.remove(id)
        if (_state.value.answering == id) flag?.delete()
        _state.update { st -> st.copy(setups = st.setups.filter { it.id != id }, answering = st.answering.takeIf { it != id }, broken = st.broken - id) }
    }


    // ---------- Talking to the service ----------

    class Req(val method: String, val url: URL, val headers: Map<String, String>, val body: String?, val timeoutMs: Int)
    class Resp(val status: Int, val body: String)

    /** Swapped in checks: makes the request; [onLine] gets a streamed answer's lines as they come. */
    @Volatile var transport: (Req, ((String) -> Unit)?) -> Resp = ::httpCall

    /** Swapped in checks (a local fake server): which addresses may be used at all. */
    @Volatile var guard: (URL) -> Unit = Net::answerGuard

    private fun call(s: Setup, method: String, path: String, body: String?, timeoutMs: Int, onLine: ((String) -> Unit)? = null): Resp {
        val url = URL(s.base.trimEnd('/') + path)
        guard(url)
        val headers = mutableMapOf("Authorization" to "Bearer ${s.key}", "Accept" to if (onLine != null) "text/event-stream" else "application/json", "User-Agent" to "Mimir")
        if (body != null) headers["Content-Type"] = "application/json"
        return transport(Req(method, url, headers, body, timeoutMs), onLine)
    }

    private fun httpCall(r: Req, onLine: ((String) -> Unit)?): Resp {
        val c = r.url.openConnection() as HttpURLConnection
        try {
            // A redirect would carry the key somewhere else: never followed.
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("Connection", "close")
            c.connectTimeout = 8_000
            c.readTimeout = r.timeoutMs
            c.requestMethod = r.method
            r.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (r.body != null) { c.doOutput = true; c.outputStream.use { it.write(r.body.toByteArray(Charsets.UTF_8)) } }
            val status = c.responseCode
            if (status in 200..299 && onLine != null) {
                c.inputStream.bufferedReader(Charsets.UTF_8).useLines { it.forEach(onLine) }
                return Resp(status, "")
            }
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { inp -> inp.readBytes().let { if (it.size > MAX_BODY) it.copyOf(MAX_BODY) else it }.toString(Charsets.UTF_8) }.orEmpty()
            return Resp(status, text)
        } finally {
            c.disconnect()
        }
    }

    private const val MAX_BODY = 4_000_000

    /** Lists the models with [key]: the check that a pasted key works. */
    fun test(service: Service, key: String): KeyTest = try {
        val who = Setup(service.id, service.name, service.base, key)
        // OpenRouter lists its models to anyone, so the list alone proves nothing: its own key check says whether the key is real.
        if (service.id == "openrouter") {
            val k = call(who, "GET", "/key", null, 15_000)
            if (k.status == 401 || k.status == 403) return KeyTest.BadKey
        }
        val r = call(who, "GET", "/models", null, 15_000)
        when {
            r.status == 401 || r.status == 403 -> KeyTest.BadKey
            r.status !in 200..299 -> KeyTest.Other("${service.name} had a problem (it answered ${r.status}).")
            else -> KeyTest.Works(usable(service.id, r.body)).let { if (it.models.isEmpty()) KeyTest.Other("The key works, but ${service.name} lists no free model for it.") else it }
        }
    } catch (e: Net.Refused) {
        KeyTest.Other("That address can't be used. Check it, or pick another service.")
    } catch (e: java.io.IOException) {
        KeyTest.Away
    } catch (e: org.json.JSONException) {
        KeyTest.Other("${service.name} answered with something that isn't a list of models.")
    }

    // ---------- Which models, and the best free one ----------

    private val NOT_CHAT = Regex("whisper|tts|playai|guard|embed|moderation|orpheus|transcri|image|rerank|safeguard|speech|audio|vision-preview|lyria", RegexOption.IGNORE_CASE)

    /**
     * The ids of the models this service lists that the owner may use for free: OpenRouter's
     * `:free` ones (or priced 0), every active one for Groq and for the owner's own service.
     * Models that aren't for chat (speech, pictures, guards, embeddings) are left out.
     */
    fun usable(serviceId: String, json: String): List<String> {
        val a = JSONObject(json).optJSONArray("data") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { m ->
            val id = m.optString("id")
            if (id.isEmpty() || NOT_CHAT.containsMatchIn(id) || !m.optBoolean("active", true)) return@filter false
            val out = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
            if (out != null && (0 until out.length()).none { out.optString(it) == "text" }) return@filter false
            if (serviceId != "openrouter") return@filter true
            val p = m.optJSONObject("pricing")
            id.endsWith(":free") || (p != null && p.has("prompt") && p.has("completion") && p.optString("prompt").toDoubleOrNull() == 0.0 && p.optString("completion").toDoubleOrNull() == 0.0)
        }.map { it.optString("id") }.distinct()
    }

    /**
     * Best first: the larger model, then the newer one, then by name. Size is the "70b" in a name
     * (0 when it has none), newer is the first version number left ("3.3"). Simple on purpose.
     */
    fun ranked(ids: List<String>): List<String> = ids.sortedWith(
        compareByDescending<String> { sizeOf(it) }.thenByDescending { versionOf(it) }.thenBy { it },
    )

    fun best(ids: List<String>, not: Set<String> = emptySet()): String? = ranked(ids.filter { it !in not }).firstOrNull()

    private val SIZE = Regex("(?<![a-z0-9.])(\\d+(?:\\.\\d+)?)b(?![a-z])", RegexOption.IGNORE_CASE)
    internal fun sizeOf(id: String): Double = SIZE.findAll(id).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull() ?: 0.0
    internal fun versionOf(id: String): Double = Regex("(\\d+(?:\\.\\d+)?)").findAll(SIZE.replace(id, "")).firstOrNull()?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    // ---------- Chatting ----------

    /** The words the model is told first. The owner's ground rules (Settings) go on after them. */
    fun system(rules: String? = io.github.odnwks.mimir.data.GroundRules.current, projects: Boolean = false): String =
        "You are Mímir, answering through an online model. " +
            (rules ?: "Answer exactly what is asked, clearly and directly. Use short paragraphs.") +
            " You can't browse the web yourself; the app may add fetched information to a message, marked as such: use it and say where it came from. " +
            io.github.odnwks.mimir.standalone.Prompt.noActions(projects)

    /**
     * Asks [setup]'s model for a reply to [messages] (role and words, the chat so far), passing the
     * words so far to [onText] as they come. If the model is gone or no longer free, a "best
     * free one" setup picks again once and retries. Throws [Sorry] with plain words.
     */
    fun chat(setup: Setup, messages: List<Pair<String, String>>, stop: () -> Boolean, onText: (String) -> Unit): Answer {
        val service = serviceOf(setup)
        var s = setup
        var retried = false
        while (true) {
            val model = s.model ?: pickAgain(s, null).also { s = s.copy(model = it) }
            try {
                return Answer(once(s, service, model, messages, stop, onText), model)
            } catch (g: Gone) {
                if (!s.auto || retried) throw Sorry(gone())
                retried = true
                val next = pickAgain(s, model)
                s = s.copy(model = next)
                saveIfStill(s)
            }
        }
    }

    private class Gone : Exception()

    /** A new best free model (not [not]); the list is read again. */
    private fun pickAgain(s: Setup, not: String?): String {
        val service = serviceOf(s)
        val r = try { call(s, "GET", "/models", null, 15_000) } catch (e: java.io.IOException) { throw Sorry(away(service.name)) } catch (e: Net.Refused) { throw Sorry("That address can't be used. Check it, or pick another service.") }
        if (r.status == 401 || r.status == 403) throw Sorry(BAD_KEY)
        if (r.status !in 200..299) throw Sorry(away(service.name))
        val ids = runCatching { usable(s.id, r.body) }.getOrDefault(emptyList())
        return best(ids, setOfNotNull(not)) ?: throw Sorry("${service.name} lists no free model right now.")
    }

    private fun once(s: Setup, service: Service, model: String, messages: List<Pair<String, String>>, stop: () -> Boolean, onText: (String) -> Unit): String {
        val body = JSONObject().put("model", model).put("stream", true).put(
            "messages", JSONArray().apply { messages.forEach { (role, text) -> put(JSONObject().put("role", role).put("content", text)) } },
        ).toString()
        val out = StringBuilder()
        val loose = StringBuilder()
        var problem: String? = null
        val r = try {
            call(s, "POST", "/chat/completions", body, 60_000) { line ->
                if (stop()) throw StopRead()
                val t = line.trim()
                when {
                    t.startsWith("data:") -> {
                        val d = t.removePrefix("data:").trim()
                        if (d != "[DONE]" && d.isNotEmpty()) runCatching {
                            val o = JSONObject(d)
                            o.optJSONObject("error")?.let { e -> problem = e.optString("message").ifEmpty { "error" } }
                            val c = o.optJSONArray("choices")?.optJSONObject(0)
                            val delta = c?.optJSONObject("delta")
                            val piece = if (delta == null || delta.isNull("content")) "" else delta.optString("content")
                            if (piece.isNotEmpty()) { out.append(piece); onText(out.toString()) }
                        }
                    }
                    t.startsWith(":") || t.isEmpty() -> {}
                    else -> loose.append(t)
                }
            }
        } catch (e: StopRead) {
            return out.toString()
        } catch (e: Net.Refused) {
            throw Sorry("That address can't be used. Check it, or pick another service.")
        } catch (e: java.io.IOException) {
            if (out.isNotEmpty()) return out.toString()
            throw Sorry(away(service.name))
        }
        // Only the service and the status are ever logged: never the key, never the words.
        if (r.status !in 200..299) android.util.Log.w(TAG, "${service.name} answered ${r.status}")
        when {
            r.status == 401 || r.status == 403 -> throw Sorry(BAD_KEY)
            r.status == 429 -> throw Sorry(limit(service.name))
            r.status == 404 || r.status == 410 || r.status == 402 -> throw Gone()
            (r.status == 400 || r.status == 422) && r.body.contains("model", ignoreCase = true) -> throw Gone()
            r.status !in 200..299 -> throw Sorry("${service.name} had a problem (it answered ${r.status}).")
        }
        problem?.let { p -> if (out.isEmpty()) { if (p.contains("model", true) || p.contains("free", true)) throw Gone() else throw Sorry("${service.name} had a problem.") } }
        // A service that ignored "stream" and sent the whole answer in one piece.
        if (out.isEmpty() && loose.isNotEmpty()) runCatching {
            val whole = JSONObject(loose.toString()).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            if (whole.isNotEmpty()) { out.append(whole); onText(whole) }
        }
        if (out.isEmpty()) throw Sorry("${service.name} sent no answer. Try again.")
        return out.toString()
    }

    private class StopRead : RuntimeException()
}
