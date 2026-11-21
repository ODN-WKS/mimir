package io.github.odnwks.mimir.standalone

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/**
 * Reads the start of a GGUF model file (its header only, never the weights) for the two things
 * Mímir needs to know about a model the owner dropped in a folder: what kind it is
 * (`general.architecture`) and how it wants the conversation written (`tokenizer.chat_template`).
 */
object Gguf {
    class Header(val architecture: String?, val chatTemplate: String?)

    private const val MAX_KEYS = 100_000L
    private const val MAX_TEXT = 1 shl 20

    /** The header of the file [input] is the start of, or null if it isn't a GGUF file or is cut short. */
    fun read(input: InputStream): Header? = try {
        parse(DataInputStream(input.buffered(64 * 1024)))
    } catch (_: EOFException) {
        null
    } catch (_: java.io.IOException) {
        null
    }

    private fun parse(d: DataInputStream): Header? {
        if (d.readInt() != 0x47475546) return null  // "GGUF"
        val version = Integer.reverseBytes(d.readInt())
        if (version < 1 || version > 3) return null
        // Version 1 counts in 32 bits, later ones in 64.
        fun count(): Long = if (version == 1) Integer.reverseBytes(d.readInt()).toLong() and 0xFFFFFFFFL else java.lang.Long.reverseBytes(d.readLong())
        count()  // tensors
        val keys = count()
        if (keys < 0 || keys > MAX_KEYS) return null
        var architecture: String? = null
        var template: String? = null
        for (i in 0 until keys) {
            val key = text(d, ::count, MAX_TEXT) ?: return null
            val type = Integer.reverseBytes(d.readInt())
            when (key) {
                "general.architecture" -> { if (type != STRING) return null; architecture = text(d, ::count, MAX_TEXT) ?: return null }
                "tokenizer.chat_template" -> { if (type != STRING) return null; template = text(d, ::count, MAX_TEXT) ?: return null }
                else -> if (!skip(d, type, ::count)) return null
            }
            if (architecture != null && template != null) break
        }
        return Header(architecture, template)
    }

    private const val STRING = 8
    private const val ARRAY = 9

    /** A string; text longer than [max] is skipped and read as null for the caller to refuse. */
    private fun text(d: DataInputStream, count: () -> Long, max: Int): String? {
        val n = count()
        if (n < 0 || n > Int.MAX_VALUE) return null
        if (n > max) { skipFully(d, n); return "" }
        val b = ByteArray(n.toInt())
        d.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun skipFully(d: DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = d.skip(left)
            if (s > 0) left -= s
            else { d.readByte(); left-- }
        }
    }

    /** Steps over a value of [type]; false if the type isn't known. */
    private fun skip(d: DataInputStream, type: Int, count: () -> Long): Boolean {
        val size = when (type) {
            0, 1, 7 -> 1
            2, 3 -> 2
            4, 5, 6 -> 4
            10, 11, 12 -> 8
            else -> 0
        }
        when {
            size > 0 -> skipFully(d, size.toLong())
            type == STRING -> skipFully(d, count().also { if (it < 0) return false })
            type == ARRAY -> {
                val inner = Integer.reverseBytes(d.readInt())
                val n = count()
                if (n < 0) return false
                if (inner == STRING) { for (i in 0 until n) skipFully(d, count().also { if (it < 0) return false }) }
                else if (inner == ARRAY) return false
                else {
                    val each = when (inner) { 0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8; else -> return false }
                    skipFully(d, n * each)
                }
            }
            else -> return false
        }
        return true
    }
}

/** A `.gguf` file in the owner's folder, before it's looked at. */
class OwnFile(val docId: String, val name: String, val bytes: Long)

/** A file opened for the model runner: [path] stays valid until it is closed. */
interface OwnOpen : Closeable {
    val path: String
}

/** The folder the owner chose (Android's folder picker). Tests swap in a fake. */
interface OwnFolder {
    /** The folder's name, as the owner sees it. */
    val name: String
    /** Its `.gguf` files, or null when the folder can't be read any more (removed, or the permission was lost). */
    fun files(): List<OwnFile>?
    /** The file's start, for [Gguf.read]. */
    fun header(docId: String): InputStream?
    /** The file as a path the runner can load straight from the folder (no copy); null if it's gone. */
    fun open(docId: String): OwnOpen?
    /** Copies the file to [to] (when the runner can't load straight from the folder); [progress] gets bytes so far. */
    fun copy(docId: String, to: File, progress: (Long) -> Unit): Boolean
}

/** What the app found out about one `.gguf` file in the folder. */
data class OwnEntry(
    val docId: String,
    val file: String,
    val bytes: Long,
    /** How it wants the conversation written; null: "Can't run this kind yet". */
    val format: Format?,
    val thinking: Boolean,
    /** The file name says it has no safety filter ("abliterated", "uncensored", "heretic", "heresy"). */
    val uncensored: Boolean,
    /** The photo part found for it in the same folder (an `mmproj` file), if any. */
    val vision: OwnFile? = null,
) {
    val id: String get() = OwnModels.idOf(docId)
    /** The name the owner sees: the file name without ".gguf". */
    val name: String get() = file.removeSuffix(".gguf").removeSuffix(".GGUF")
    val kind: String get() = when (format) {
        Format.ChatMl -> "Qwen-style"
        Format.Gemma4 -> "Gemma-style"
        null -> "Can't run this kind yet"
    }
    val sizeText: String get() = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

    /** The model as the engine runs it. */
    fun model(): LocalModel = LocalModel(
        id = id, name = name, note = "From your own folder", url = "", bytes = bytes, sha256 = "",
        format = format ?: Format.ChatMl, refusalsRemoved = uncensored, thinking = thinking,
        vision = vision?.let { VisionPart("own:${it.docId}", it.bytes, "") }, own = true,
    )

    /** The bytes it needs in memory: the model and its photo part. */
    val totalBytes: Long get() = bytes + (vision?.bytes ?: 0)
}

object OwnModels {
    const val PREFIX = "own:"
    fun idOf(docId: String) = PREFIX + docId
    fun docIdOf(id: String) = id.removePrefix(PREFIX)
    fun isOwn(id: String?) = id != null && id.startsWith(PREFIX)

    /** A model bigger than this share of the phone's memory won't run well. */
    const val MEMORY_SHARE = 0.7

    /** Too big for this phone: more than about 70% of its memory (0: memory unknown, nothing is called too big). */
    fun tooBig(bytes: Long, phoneBytes: Long): Boolean = phoneBytes > 0 && bytes > phoneBytes * MEMORY_SHARE

    private val MARKS = listOf("abliterated", "uncensored", "heretic", "heresy")

    /** The file name says so, and only then: nothing is guessed. */
    fun uncensored(fileName: String): Boolean = fileName.lowercase().let { n -> MARKS.any { it in n } }

    /** How a chat template wants the conversation written (null: not a kind Mímir can run yet) and whether it can think. */
    fun kindOf(template: String?): Pair<Format, Boolean>? = when {
        template == null -> null
        "<|im_start|>" in template -> Format.ChatMl to ("<think>" in template)
        "<|turn>" in template -> Format.Gemma4 to ("<|channel>" in template)
        else -> null
    }

    private val QUANT = Regex("^(f16|f32|bf16|i1|mxfp4|q\\d+|iq\\d+|[ksml]|xs|xl|xxs|[01])$")
    private fun words(name: String): List<String> =
        name.removeSuffix(".gguf").removeSuffix(".GGUF").lowercase().split(Regex("[^a-z0-9.]+")).filter { it.isNotEmpty() }
    private fun core(w: List<String>): List<String> = w.dropLastWhile { QUANT.matches(it) }

    /** Whether [mmproj] (an "mmproj-…" file) is the photo part of [model]: the names start the same, once "mmproj" and the quantisation are left out. */
    fun pairs(model: String, mmproj: String): Boolean {
        val m = core(words(model))
        val p = core(words(mmproj).filter { it != "mmproj" })
        if (m.isEmpty() || p.isEmpty()) return false
        return m.take(p.size) == p || p.take(m.size) == m
    }

    /**
     * Looks at every `.gguf` file: its type from its header, whether its name says it has no
     * filter, and its photo part. Files that are photo parts themselves aren't models. The result
     * is for the list; [cache] keeps what was read about a file (by its id and size) so "Look
     * again" is quick.
     */
    fun scan(folder: OwnFolder, cache: MutableMap<String, OwnEntry> = HashMap()): List<OwnEntry>? {
        val files = folder.files() ?: return null
        val parts = files.filter { it.name.startsWith("mmproj", ignoreCase = true) }
        return files.filter { it !in parts }.sortedBy { it.name.lowercase() }.mapNotNull { f ->
            val key = "${f.docId}|${f.bytes}"
            val read = cache[key] ?: run {
                val header = runCatching { folder.header(f.docId)?.use { Gguf.read(it) } }.getOrNull() ?: return@mapNotNull null
                // A file that isn't a GGUF file at all (a renamed download) is left out; a photo part has its own kind.
                if (header.architecture == "clip") return@mapNotNull null
                val kind = kindOf(header.chatTemplate)
                OwnEntry(f.docId, f.name, f.bytes, kind?.first, kind?.second == true, uncensored(f.name)).also { cache[key] = it }
            }
            read.copy(file = f.name, uncensored = uncensored(f.name), vision = if (read.format == null) null else parts.firstOrNull { pairs(f.name, it.name) })
        }
    }
}
