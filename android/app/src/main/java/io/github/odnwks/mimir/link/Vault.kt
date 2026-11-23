package io.github.odnwks.mimir.link

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.withLock

/**
 * The link's own store: this phone's key, the paired PC and messages waiting to be sent, kept in
 * one file encrypted with a key that never leaves the phone's Keystore. It is readable while the
 * phone is locked, so the link can work in the background; the chat store with the conversations
 * comes with the needs-you phase.
 */
class Vault(
    private val file: File,
    private val codec: Codec = KeystoreVaultCodec(),
    private val newKey: () -> ByteArray = { if (Native.available) Native.generateKey() else java.security.SecureRandom().generateSeed(32) },
    /** Reading the file is tried this many times before it is called unreadable. */
    private val tries: Int = 3,
    private val pause: (Int) -> Unit = { Thread.sleep(300L * it) },
) {
    interface Codec {
        fun seal(text: String): ByteArray
        fun open(bytes: ByteArray): String
    }

    /** Set when the file was there but could not be read: it was kept aside and the phone starts unpaired ("Re-pair"). */
    @Volatile var unreadable: Boolean = false
        private set

    /** The file the unreadable one was kept as, or null. */
    @Volatile var keptAs: File? = null
        private set

    /** Saving is refused to keep an unreadable file safe: it couldn't be moved aside, or the Keystore was only busy. */
    @Volatile var readOnly: Boolean = false
        private set

    data class Data(
        val key: ByteArray,
        val pcId: String? = null,
        val pcName: String? = null,
        val outbox: List<Pair<String, String>> = emptyList(),
        /** Replies typed inside a task while the PC was away: (id, task, text). */
        val replies: List<Triple<String, String, String>> = emptyList(),
        /** New tasks started while the PC was away, oldest first: they start once when it's back. */
        val newTasks: List<WaitingTask> = emptyList(),
        /** Answers given while the PC was away: (the ask's id, the call to send). */
        val answers: List<Pair<String, String>> = emptyList(),
        /** The general chat (the newest entries), so it's still there after the app closes. */
        val chat: String = "[]",
        /** Recordings spoken while the PC was away: (id, task or "" for the chat, seconds); the sound is a file. */
        val recordings: List<Triple<String, String, Int>> = emptyList(),
        /** When the PC was last reached (ms since 1970; 0: never), for "as of 11:42". */
        val seenAt: Long = 0,
        /** Earlier conversations of the general chat ([io.github.odnwks.mimir.data.KeptChat] text). */
        val past: String = "[]",
        /** When the open conversation last changed (ms since 1970). */
        val chatAt: Long = 0,
        /** The PC answered chats itself ("talk") when last reached: the one chat is used, even while it's away. */
        val pcTalks: Boolean = false,
        /** Whose phone the PC said this was when last reached (an id), to tell when it is paired as another person's. */
        val person: String? = null,
    )

    /**
     * A new identity is made only when there is no file at all. A file that fails to read is tried
     * again, and if it still fails it is kept aside (`.bad`), never overwritten, and [unreadable] is set.
     * It never throws.
     */
    fun load(): Data {
        unreadable = false
        keptAs = null
        readOnly = false
        SafeFile.sweep(file)
        if (!file.exists()) {
            val fresh = Data(newKey())
            runCatching { save(fresh) }
            return fresh
        }
        var last: Throwable? = null
        for (attempt in 1..tries) {
            val got = runCatching { parse(codec.open(file.readBytes())) }
            got.getOrNull()?.let { return it }
            last = got.exceptionOrNull()
            android.util.Log.w("MimirVault", "couldn't read ${file.name} (try $attempt, ${file.length()} bytes): ${got.exceptionOrNull()?.javaClass?.simpleName}: ${got.exceptionOrNull()?.message?.take(80)}")
            if (attempt < tries) runCatching { pause(attempt) }
        }
        unreadable = true
        // Only a damaged file is moved aside; if it can't be (or the Keystore was only busy) it stays, and nothing is saved over it.
        keptAs = if (SafeFile.isDamage(last)) SafeFile.setAside(file) else null
        readOnly = keptAs == null
        val fresh = Data(newKey())
        if (!readOnly) runCatching { save(fresh) }
        return fresh
    }

    private fun parse(text: String): Data {
        val o = JSONObject(text)
        val box = o.optJSONArray("outbox") ?: JSONArray()
        val rs = o.optJSONArray("replies") ?: JSONArray()
        val nts = o.optJSONArray("new_tasks") ?: JSONArray()
        val ans = o.optJSONArray("answers") ?: JSONArray()
        val rec = o.optJSONArray("recordings") ?: JSONArray()
        return Data(
            key = android.util.Base64.decode(o.getString("key"), android.util.Base64.NO_WRAP),
            pcId = o.optString("pc_id").ifEmpty { null },
            pcName = o.optString("pc_name").ifEmpty { null },
            outbox = (0 until box.length()).map { box.getJSONObject(it).let { m -> m.getString("id") to m.getString("text") } },
            replies = (0 until rs.length()).map { rs.getJSONObject(it).let { m -> Triple(m.getString("id"), m.getString("task"), m.getString("text")) } },
            newTasks = (0 until nts.length()).map { nts.getJSONObject(it).let { m ->
                WaitingTask(m.getString("id"), m.getString("project"), m.getString("text"), m.optString("model", "auto"), m.optBoolean("plan"), m.optString("rules").ifEmpty { null }, m.optString("failed"))
            } },
            answers = (0 until ans.length()).map { ans.getJSONObject(it).let { m -> m.getString("ask") to m.getString("call") } },
            chat = (o.optJSONArray("chat") ?: JSONArray()).toString(),
            recordings = (0 until rec.length()).map { rec.getJSONObject(it).let { m -> Triple(m.getString("id"), m.optString("task"), m.optInt("seconds")) } },
            seenAt = o.optLong("seen_at"),
            past = (o.optJSONArray("past") ?: JSONArray()).toString(),
            chatAt = o.optLong("chat_at"),
            pcTalks = o.optBoolean("pc_talks"),
            person = o.optString("person").ifEmpty { null },
        )
    }

    /** Whether the last save reached the disk. */
    @Volatile var lastSaveOk: Boolean = true
        private set

    /** Saves one writer at a time; a failure is remembered in [lastSaveOk] (never thrown), and the next save tries again. */
    fun save(d: Data) {
        if (readOnly) { lastSaveOk = false; return }
        val o = JSONObject()
            .put("key", android.util.Base64.encodeToString(d.key, android.util.Base64.NO_WRAP))
            .put("pc_id", d.pcId ?: "")
            .put("pc_name", d.pcName ?: "")
            .put("outbox", JSONArray(d.outbox.map { JSONObject().put("id", it.first).put("text", it.second) }))
            .put("replies", JSONArray(d.replies.map { JSONObject().put("id", it.first).put("task", it.second).put("text", it.third) }))
            .put("new_tasks", JSONArray(d.newTasks.map { JSONObject().put("id", it.id).put("project", it.project).put("text", it.text).put("model", it.model).put("plan", it.plan).put("rules", it.rules ?: "").put("failed", it.failed) }))
            .put("answers", JSONArray(d.answers.map { JSONObject().put("ask", it.first).put("call", it.second) }))
            .put("chat", runCatching { JSONArray(d.chat) }.getOrDefault(JSONArray()))
            .put("recordings", JSONArray(d.recordings.map { JSONObject().put("id", it.first).put("task", it.second).put("seconds", it.third) }))
            .put("seen_at", d.seenAt)
            .put("past", runCatching { JSONArray(d.past) }.getOrDefault(JSONArray()))
            .put("chat_at", d.chatAt)
            .put("pc_talks", d.pcTalks)
            .put("person", d.person ?: "")
        lastSaveOk = runCatching { writeLock.withLock { SafeFile.write(file, codec.seal(o.toString())) } }.isSuccess
    }

    private val writeLock = java.util.concurrent.locks.ReentrantLock()
}

/** The link's file: a Keystore key sealing a key of its own (see [Envelope]), usable while the phone is locked. */
class KeystoreVaultCodec : Vault.Codec {
    private val envelope = Envelope(KeystoreWrap("mimir-link"))
    override fun seal(text: String): ByteArray = envelope.seal(text)
    override fun open(bytes: ByteArray): String = envelope.open(bytes)
}

/** A new task started while the PC was away: kept until the PC has it ([id] is also what the PC dedupes on). */
data class WaitingTask(val id: String, val project: String, val text: String, val model: String, val plan: Boolean, val rules: String?, val failed: String = "")
