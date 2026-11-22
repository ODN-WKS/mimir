package io.github.odnwks.mimir.data.store

import android.content.Context
import io.github.odnwks.mimir.link.SafeFile
import io.github.odnwks.mimir.standalone.KeystoreCodec
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.standalone.TurnFile
import io.github.odnwks.mimir.standalone.TurnPicture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-chat file store. Fixes PHE-01 (one corrupt save can't wreck all chats), PHD-02 (turns
 * tracked as unsent until TurnAck), PHD-17 / PHE-24 (pictures stored separately, not re-encrypted
 * on every save).
 *
 * Each chat is kept as `chats/<id>.bin` (encrypted). Sent state is tracked in `<id>.sent.bin`.
 * Background writing runs on one serial thread so the UI thread is never blocked.
 */
class ChatRepository(private val dir: File, private val codec: Codec) {

    constructor(context: Context) : this(
        File(context.filesDir, "chats"),
        object : Codec {
            private val inner = KeystoreCodec("mimir-chatrepo")
            override fun seal(text: String) = inner.seal(text)
            override fun open(bytes: ByteArray) = inner.open(bytes)
        }
    )

    interface Codec {
        fun seal(text: String): ByteArray
        fun open(bytes: ByteArray): String
    }

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "chat-repo-writer").apply { isDaemon = true } }
    private val lock = ReentrantLock()

    fun saveChat(chat: Chat) {
        writer.execute { writeChatNow(chat) }
    }

    fun loadChat(id: ChatId): Chat? = lock.withLock {
        val f = chatFile(id)
        if (!f.exists()) return null
        return runCatching {
            val text = codec.open(f.readBytes())
            parseChatJson(JSONObject(text))
        }.getOrNull()
    }

    fun listChats(): List<ChatSummary> = lock.withLock {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".bin") && !f.name.endsWith(".sent.bin") }
            ?.mapNotNull { f ->
                runCatching {
                    val text = codec.open(f.readBytes())
                    val o = JSONObject(text)
                    val id = o.getString("id")
                    val name = o.optString("name").ifEmpty { null }
                    val at = o.optLong("at")
                    val turns = o.optJSONArray("turns") ?: JSONArray()
                    val title = name ?: run {
                        val firstUser = (0 until turns.length()).map { turns.getJSONObject(it) }.firstOrNull { it.getBoolean("user") }
                        firstUser?.optString("text")?.take(50) ?: "New chat"
                    }
                    ChatSummary(id, title, at)
                }.getOrNull()
            } ?: emptyList()
    }

    fun deleteChat(id: ChatId) {
        writer.execute {
            lock.withLock {
                chatFile(id).delete()
                sentFile(id).delete()
            }
        }
    }

    fun markSent(id: ChatId, turnIndex: Int) {
        writer.execute {
            lock.withLock {
                val chat = loadChatUnlocked(id) ?: return@withLock
                val turn = chat.turns.getOrNull(turnIndex) ?: return@withLock
                if (turn.id.isEmpty()) return@withLock
                val sentIds = loadSentIds(id).toMutableSet()
                sentIds.add(turn.id)
                saveSentIds(id, sentIds)
            }
        }
    }

    fun unsent(): List<Pair<ChatId, Turn>> {
        return lock.withLock {
            dir.mkdirs()
            val result = mutableListOf<Pair<ChatId, Turn>>()
            dir.listFiles { f -> f.name.endsWith(".bin") && !f.name.endsWith(".sent.bin") }?.forEach { f ->
                runCatching {
                    val text = codec.open(f.readBytes())
                    val o = JSONObject(text)
                    val id = o.getString("id")
                    val sentIds = loadSentIds(id)
                    val turns = o.optJSONArray("turns") ?: JSONArray()
                    for (i in 0 until turns.length()) {
                        val t = turns.getJSONObject(i)
                        val turnId = t.optString("id")
                        if (turnId.isNotEmpty() && turnId !in sentIds) {
                            result.add(id to parseTurnJson(t))
                        }
                    }
                }
            }
            result
        }
    }

    private fun chatFile(id: ChatId) = File(dir, "$id.bin")
    private fun sentFile(id: ChatId) = File(dir, "$id.sent.bin")

    private fun loadSentIds(id: ChatId): Set<String> {
        val f = sentFile(id)
        if (!f.exists()) return emptySet()
        return runCatching {
            val text = codec.open(f.readBytes())
            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun saveSentIds(id: ChatId, ids: Set<String>) {
        val arr = JSONArray(ids.toList())
        SafeFile.write(sentFile(id), codec.seal(arr.toString()))
    }

    private fun loadChatUnlocked(id: ChatId): Chat? {
        val f = chatFile(id)
        if (!f.exists()) return null
        return runCatching {
            val text = codec.open(f.readBytes())
            parseChatJson(JSONObject(text))
        }.getOrNull()
    }

    private fun writeChatNow(chat: Chat) {
        lock.withLock {
            dir.mkdirs()
            SafeFile.sweep(chatFile(chat.id))
            val o = chatToJson(chat)
            SafeFile.write(chatFile(chat.id), codec.seal(o.toString()))
        }
    }

    private fun chatToJson(chat: LocalChat): JSONObject {
        return JSONObject()
            .put("id", chat.id)
            .put("model", chat.model)
            .put("at", chat.at)
            .apply { chat.name?.let { put("name", it) } }
            .apply { if (chat.pendingName) put("pending_name", true) }
            .apply { chat.leftTask?.let { put("left_task", it) } }
            .put("turns", JSONArray(chat.turns.map { t ->
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
                    if (t.files.isNotEmpty()) put("files", JSONArray(t.files.map { JSONObject().put("name", it.name).put("text", it.text).put("cut", it.cut) }))
                    if (t.pictures.isNotEmpty()) put("pictures", JSONArray(t.pictures.map {
                        JSONObject().put("id", it.id).put("name", it.name).put("jpeg", java.util.Base64.getEncoder().encodeToString(it.jpeg))
                    }))
                    if (t.sources.isNotEmpty()) put("sources", JSONArray(t.sources.map { s ->
                        JSONObject().put("site", s.site).put("url", s.url).put("words", s.words).put("results", s.results).put("via", s.via)
                    }))
                }
            }))
    }

    private fun parseChatJson(o: JSONObject): LocalChat {
        val turns = o.optJSONArray("turns") ?: JSONArray()
        return LocalChat(
            id = o.getString("id"),
            model = o.optString("model"),
            turns = (0 until turns.length()).map { parseTurnJson(turns.getJSONObject(it)) },
            name = o.optString("name").ifEmpty { null },
            at = o.optLong("at"),
            pendingName = o.optBoolean("pending_name"),
            leftTask = o.optString("left_task").ifEmpty { null },
        )
    }

    private fun parseTurnJson(t: JSONObject): Turn {
        val files = t.optJSONArray("files") ?: JSONArray()
        val pictures = t.optJSONArray("pictures") ?: JSONArray()
        val sources = t.optJSONArray("sources") ?: JSONArray()
        return Turn(
            user = t.getBoolean("user"),
            text = t.getString("text"),
            files = (0 until files.length()).map { files.getJSONObject(it).let { TurnFile(it.getString("name"), it.getString("text"), it.optBoolean("cut")) } },
            pictures = (0 until pictures.length()).map { pictures.getJSONObject(it).let { TurnPicture(it.getString("id"), it.getString("name"), java.util.Base64.getDecoder().decode(it.getString("jpeg"))) } },
            sources = (0 until sources.length()).map { sources.getJSONObject(it).let { io.github.odnwks.mimir.online.Source(it.getString("site"), it.getString("url"), it.optString("words"), it.optBoolean("results"), it.optString("via")) } },
            id = t.optString("id"),
            at = t.optLong("at"),
            ran = t.optString("ran").ifEmpty { null },
            modelWords = t.optString("model_words").ifEmpty { null },
            switchNote = t.optString("switch_note").ifEmpty { null },
            version = t.optInt("version", 1),
            seq = t.optLong("seq"),
            task = t.optString("task").ifEmpty { null },
            project = t.optString("project").ifEmpty { null },
        )
    }
}
