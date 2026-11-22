package io.github.odnwks.mimir.standalone

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.withLock

/** A turn cut away by an edit: [version] is the tombstone's (one above the turn's); [sent]: the PC has been told. */
data class Cut(val id: String, val user: Boolean, val at: Long, val version: Int, val sent: Boolean = false) {
    /** As told to the PC: no words, and cut away. */
    fun wire() = io.github.odnwks.mimir.link.LChatTurn(id, user, "", at = at, version = version, gone = true)
}

/** One standalone chat. */
data class LocalChat(
    val id: String, val model: String, val turns: List<Turn>, val name: String? = null,
    /** When a message was last sent in it (ms since 1970; 0 in chats from before this was kept). */
    val at: Long = 0,
    /** Renamed here and not yet told to the PC. */
    val pendingName: Boolean = false,
    /** The project task the owner took the chat out of (✕ on "In Garden planner-Mod-Manager"). */
    val leftTask: String? = null,
    /** Rules for this chat only: a text the model is told first, in this chat and no other (empty by default). */
    val rules: String = "",
    /** How many bytes of this chat were sent to the PC to be answered (words only; counted here). */
    val sentToPc: Long = 0,
    /** Turns cut away by an edit of an earlier message: each is told to the PC once, and an older copy never brings it back. */
    val cuts: List<Cut> = emptyList(),
    /** Messages typed while nothing could answer (the PC away): they go by themselves, in order, once, when it can. */
    val queued: List<Turn> = emptyList(),
) {
    /** The last project card's task and project (Stage 1, 2.3), unless the owner took the chat out of it. */
    val projectCard: Turn? get() = turns.lastOrNull { !it.user && it.task != null }?.takeIf { it.task != leftTask }

    /** Where its newest answer ran ("pc", "phone", "paid"; null: none yet), for Chat history. */
    val lastRan: String? get() = turns.lastOrNull { !it.user }?.ran

    val title: String get() = topic ?: "New chat"

    /** The chat's topic: the name given to it, or a few words from the first message (null before one). */
    val topic: String? get() = name?.takeIf { it.isNotBlank() } ?: turns.firstOrNull { it.user }?.let { t -> topicOf(t.text) ?: t.files.firstOrNull()?.name ?: t.pictures.firstOrNull()?.name }

    companion object {
        /** How long a chat's first words may be as its title (an ellipsis follows when more was cut). */
        const val TITLE_CHARS = 40

        /**
         * The first words of a first message, without the small talk at its start, as the owner typed them
         * (punctuation and apostrophes kept), up to [TITLE_CHARS] characters and cut at a word with an ellipsis.
         */
        fun topicOf(text: String): String? {
            val skip = setOf("please", "can", "could", "would", "you", "hi", "hey", "hello", "i", "want", "to", "me", "help", "a", "an", "the", "give", "tell", "show", "write", "explain", "some")
            val words = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            val start = words.indexOfFirst { it.trim(',', '.', '!', '?', ';', ':', '"', '\'').lowercase() !in skip }.let { if (it < 0) 0 else it }
            val rest = words.drop(start).ifEmpty { words }
            val out = StringBuilder()
            var used = 0
            for (w in rest) {
                if (out.isNotEmpty() && out.length + 1 + w.length > TITLE_CHARS) break
                if (out.isNotEmpty()) out.append(' ')
                out.append(w)
                used++
            }
            var title = out.toString()
            if (title.length > TITLE_CHARS) title = title.take(TITLE_CHARS - 1).trimEnd() + "…"
            else if (used < rest.size) title = title.trimEnd(',', ';', ':', '.', '-') + "…"
            return title.replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * What standalone mode keeps: the chosen model, the chats (newest first), whether "Think first" is
 * on, when each model was last used, and the checksum of each downloaded file (to spot updates).
 */
data class Saved(
    val model: String,
    val chats: List<LocalChat>,
    val think: Boolean = false,
    val lastUsed: Map<String, Long> = emptyMap(),
    val files: Map<String, String> = emptyMap(),
    /** The effort chip's choice ([Effort] by name); null in files from before it. */
    val effort: String? = null,
    /** The composer's place chip: "Your PC when connected" (true) or "This phone only". */
    val usePc: Boolean = true,
    /** The PC's change number this phone has caught up to (Stage 1, 2.5). */
    val lastSeq: Long = 0,
    /** Chats deleted here that the PC hasn't been told of yet. */
    val pendingDeletes: List<String> = emptyList(),
    /** The first sync with the PC is done (its earlier chats came across). */
    val synced: Boolean = false,
)

/**
 * Standalone mode's chats, in one file encrypted with a key that never leaves the phone's
 * Keystore (its own key, apart from the link's). [codec] can be swapped for plain text in checks.
 */
class Store(private val file: File, private val codec: Codec = KeystoreCodec()) {
    /** How much room the kept chats take on the phone. */
    fun bytes(): Long = file.length()

    /** Each chat's unsent words, sealed like the chats, in a file beside them (so they survive the app being closed). */
    private val draftsFile: File get() = File(file.parentFile, "drafts.bin")

    fun loadDrafts(): Map<String, String> = runCatching {
        val o = JSONObject(codec.open(draftsFile.readBytes()))
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun saveDrafts(texts: Map<String, String>) {
        if (texts.isEmpty()) { draftsFile.delete(); return }
        io.github.odnwks.mimir.link.SafeFile.write(draftsFile, codec.seal(JSONObject(texts).toString()))
    }

    interface Codec {
        fun seal(text: String): ByteArray
        fun open(bytes: ByteArray): String
    }

    /**
     * Set when the chat file was there but could not be read, and the owner is told. If it was damaged it is kept as
     * `standalone.bin.bad-<time>`; if the Keystore was only busy it stays where it is. Either way nothing is saved over it
     * until the next start ([readOnly]).
     */
    @Volatile var unreadable: Boolean = false
        private set

    /** Set when saving is refused to keep an unreadable file safe (it could not be moved aside, or the Keystore was busy). */
    @Volatile var readOnly: Boolean = false
        private set

    private val writeLock = java.util.concurrent.locks.ReentrantLock()

    /**
     * No file: nothing yet. A file that can't be read is tried again; if it still fails it is
     * kept aside (never overwritten) and [unreadable] is set. Never throws.
     */
    fun load(): Saved {
        unreadable = false
        readOnly = false
        io.github.odnwks.mimir.link.SafeFile.sweep(file)
        val empty = Saved(Catalog.default.id, emptyList())
        if (!file.exists()) return empty
        var last: Throwable? = null
        for (attempt in 1..3) {
            val got = runCatching { parse(codec.open(file.readBytes())) }
            got.getOrNull()?.let { return it }
            last = got.exceptionOrNull()
            // Only what went wrong (never any chat text), so a failure can be traced.
            android.util.Log.w("MimirStore", "couldn't read ${file.name} (try $attempt, ${file.length()} bytes): ${got.exceptionOrNull()?.javaClass?.simpleName}: ${got.exceptionOrNull()?.message?.take(80)}")
            if (attempt < 3) runCatching { Thread.sleep(300L * attempt) }
        }
        unreadable = true
        // Only a damaged file is moved; a busy Keystore leaves it alone. A file that couldn't be moved is never saved over.
        val moved = if (io.github.odnwks.mimir.link.SafeFile.isDamage(last)) io.github.odnwks.mimir.link.SafeFile.setAside(file) else null
        readOnly = moved == null
        return empty
    }

    private fun parse(text: String): Saved {
        val o = JSONObject(text)
        val chats = o.optJSONArray("chats") ?: JSONArray()
        fun map(key: String): Map<String, Any> = o.optJSONObject(key)?.let { m -> m.keys().asSequence().associateWith { m.get(it) } } ?: emptyMap()
        return Saved(
            model = o.optString("model").ifEmpty { Catalog.default.id },
            think = o.optBoolean("think"),
            effort = o.optString("effort").ifEmpty { null },
            usePc = o.optBoolean("use_pc", true),
            lastSeq = o.optLong("last_seq"),
            pendingDeletes = (o.optJSONArray("pending_deletes") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } },
            synced = o.optBoolean("synced"),
            lastUsed = map("last_used").mapValues { (it.value as Number).toLong() },
            files = map("files").mapValues { it.value.toString() },
            chats = (0 until chats.length()).map { i ->
                val c = chats.getJSONObject(i)
                val turns = c.getJSONArray("turns")
                LocalChat(
                    c.getString("id"), c.optString("model"),
                    (0 until turns.length()).map { j -> parseTurn(turns.getJSONObject(j), "${c.getString("id")}.$j") },
                    c.optString("name").ifEmpty { null },
                    c.optLong("at"),
                    c.optBoolean("pending_name"),
                    c.optString("left_task").ifEmpty { null },
                    c.optString("rules"),
                    c.optLong("sent_to_pc"),
                    (c.optJSONArray("cuts") ?: JSONArray()).let { a ->
                        (0 until a.length()).map { k -> a.getJSONObject(k).let { Cut(it.getString("id"), it.getBoolean("user"), it.optLong("at"), it.optInt("version", 2), it.optBoolean("sent")) } }
                    },
                    (c.optJSONArray("queued") ?: JSONArray()).let { a -> (0 until a.length()).map { k -> parseTurn(a.getJSONObject(k), "${c.getString("id")}.q$k") } },
                )
            },
        )
    }

    private fun parseTurn(t: JSONObject, fallbackId: String): Turn {
        val user = t.getBoolean("user")
        val files = t.optJSONArray("files") ?: JSONArray()
        val pictures = t.optJSONArray("pictures") ?: JSONArray()
        return Turn(user, t.getString("text"), (0 until files.length()).map { k ->
            files.getJSONObject(k).let { f ->
                TurnFile(
                    f.getString("name"), f.getString("text"), f.optBoolean("cut"),
                    bytes = f.optString("bytes").takeIf { it.isNotEmpty() }?.let { java.util.Base64.getDecoder().decode(it) },
                    mime = f.optString("mime"), note = f.optString("note"),
                )
            }
        }, (0 until pictures.length()).map { k ->
            pictures.getJSONObject(k).let { TurnPicture(it.getString("id"), it.getString("name"), java.util.Base64.getDecoder().decode(it.getString("jpeg"))) }
        }, (t.optJSONArray("sources") ?: JSONArray()).let { a ->
            (0 until a.length()).map { k -> a.getJSONObject(k).let { io.github.odnwks.mimir.online.Source(it.getString("site"), it.getString("url"), it.optString("words"), it.optBoolean("results"), it.optString("via")) } }
        },
            // Chats from before ids get one from their place, the same at every load.
            id = t.optString("id").ifEmpty { fallbackId + if (user) "" else ".a" },
            at = t.optLong("at"),
            ran = t.optString("ran").ifEmpty { null },
            modelWords = t.optString("model_words").ifEmpty { null },
            switchNote = t.optString("switch_note").ifEmpty { null },
            version = t.optInt("version", 1),
            seq = t.optLong("seq"),
            task = t.optString("task").ifEmpty { null },
            project = t.optString("project").ifEmpty { null },
            voice = t.optString("voice").ifEmpty { null },
        )
    }

    /** A waiting message keeps the bytes of files for the PC (they go when it is back); a sent one never does. */
    private fun queuedJson(t: Turn): JSONObject = turnJson(t, keepBytes = true)

    private fun turnJson(t: Turn, keepBytes: Boolean = false): JSONObject =
        JSONObject().put("user", t.user).put("text", t.text).apply {
            if (t.id.isNotEmpty()) put("id", t.id)
            if (t.at != 0L) put("at", t.at)
            t.ran?.let { put("ran", it) }
            t.modelWords?.let { put("model_words", it) }
            t.switchNote?.let { put("switch_note", it) }
            if (t.version != 1) put("version", t.version)
            if (t.seq != 0L) put("seq", t.seq)
            t.task?.let { put("task", it) }
            t.project?.let { put("project", it) }
            t.voice?.let { put("voice", it) }
            if (t.files.isNotEmpty()) put("files", JSONArray(t.files.map { f ->
                JSONObject().put("name", f.name).put("text", f.text).put("cut", f.cut).apply {
                    if (f.note.isNotEmpty()) put("note", f.note)
                    if (f.mime.isNotEmpty()) put("mime", f.mime)
                    if (keepBytes && f.bytes != null) put("bytes", java.util.Base64.getEncoder().encodeToString(f.bytes))
                }
            }))
            if (t.sources.isNotEmpty()) put("sources", JSONArray(t.sources.map { JSONObject().put("site", it.site).put("url", it.url).put("words", it.words).put("results", it.results).put("via", it.via) }))
            if (t.pictures.isNotEmpty()) put("pictures", JSONArray(t.pictures.map {
                JSONObject().put("id", it.id).put("name", it.name).put("jpeg", java.util.Base64.getEncoder().encodeToString(it.jpeg))
            }))
        }

    /** A recording sealed with the chat file's own key, for the file at [file] (never plain on the phone). */
    fun sealAudio(file: File, wav: ByteArray) {
        io.github.odnwks.mimir.link.SafeFile.write(file, codec.seal(java.util.Base64.getEncoder().encodeToString(wav)))
    }

    /** What [sealAudio] kept; null if it can't be read. */
    fun openAudio(file: File): ByteArray? = runCatching { java.util.Base64.getDecoder().decode(codec.open(file.readBytes())) }.getOrNull()

    /** One save at a time (the main, writer, sync and download threads all call it); throws if it didn't reach the disk. */
    fun save(s: Saved) = writeLock.withLock { saveNow(s) }

    private fun saveNow(s: Saved) {
        if (readOnly) throw java.io.IOException("the chat file is unreadable, so nothing is saved over it") // lint:log-only
        val o = JSONObject().put("model", s.model).put("think", s.think).put("effort", s.effort ?: JSONObject.NULL).put("use_pc", s.usePc)
            .put("last_seq", s.lastSeq).put("pending_deletes", JSONArray(s.pendingDeletes)).put("synced", s.synced)
            .put("last_used", JSONObject(s.lastUsed as Map<*, *>)).put("files", JSONObject(s.files as Map<*, *>)).put(
            "chats",
            JSONArray(s.chats.map { c ->
                JSONObject().put("id", c.id).put("model", c.model).put("at", c.at).apply { c.name?.let { put("name", it) }; if (c.pendingName) put("pending_name", true); c.leftTask?.let { put("left_task", it) }; if (c.rules.isNotEmpty()) put("rules", c.rules); if (c.sentToPc != 0L) put("sent_to_pc", c.sentToPc)
                    if (c.cuts.isNotEmpty()) put("cuts", JSONArray(c.cuts.map { JSONObject().put("id", it.id).put("user", it.user).put("at", it.at).put("version", it.version).put("sent", it.sent) })) }
                    .put("turns", JSONArray(c.turns.map(::turnJson)))
                    .apply { if (c.queued.isNotEmpty()) put("queued", JSONArray(c.queued.map(::queuedJson))) }
            }),
        )
        io.github.odnwks.mimir.link.SafeFile.write(file, codec.seal(o.toString()))
    }
}

/** The chats' file: a Keystore key (its own, apart from the link's) sealing a key of its own (see [io.github.odnwks.mimir.link.Envelope]); usable while the phone is locked, so a reply can finish then. */
class KeystoreCodec(alias: String = "mimir-standalone") : Store.Codec {
    private val envelope = io.github.odnwks.mimir.link.Envelope(io.github.odnwks.mimir.link.KeystoreWrap(alias))
    override fun seal(text: String): ByteArray = envelope.seal(text)
    override fun open(bytes: ByteArray): String = envelope.open(bytes)
}
