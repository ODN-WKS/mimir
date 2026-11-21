package io.github.odnwks.mimir.devlog

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The developer log: the app's own logcat lines (never message text unless the chats switch is on),
 * kept in rotating files in the app's external files folder so the PC can `adb pull` them.
 *
 * Two sets of files:
 * *   `devlog-*.txt`: everything but the noise, in files of 5 MB, at most 20 of them (100 MB), kept 7 days.
 * *   `important-*.txt`: the evidence (errors, crashes, long stalls of the screen, lookups and models that
 *     failed, the start header, and the log's own notes), at most 20 MB, kept 30 days. A flood of
 *     ordinary lines can push the first set's old files out, but never this one.
 *
 * Writes are gathered and written in one go (every 64 KB or 5 s, never a write per line, to spare the phone's
 * flash); the important file is written at once and flushed to the disk, since its lines are rare.
 * With under 1 GB free on the phone only the important file is kept.
 *
 * This class is the file side (plain Java, unit tested); [DevLogRunner] feeds it on the phone.
 */
class DevLog(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
    private val maxFiles: Int = MAX_FILES,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val now: () -> Long = System::currentTimeMillis,
    /** True while a Whisper is open: nothing is written then, except one note. */
    private val whisperOpen: () -> Boolean = { false },
    /** Whether normal chat text may be written too (the second Labs switch). */
    private val chatsOn: () -> Boolean = { false },
    private val importantBytes: Long = IMPORTANT_FILE_BYTES,
    private val importantFiles: Int = IMPORTANT_FILES,
    private val importantAgeMs: Long = IMPORTANT_AGE_MS,
    /** Lines are gathered until this many bytes (0: every line is written as it comes). */
    private val bufferBytes: Int = BUFFER_BYTES,
    /** ...or until this long since the last write to the disk. */
    private val flushMs: Long = FLUSH_MS,
    /** How much room the phone has left. */
    private val freeBytes: () -> Long = { dir.usableSpace },
    private val minFreeBytes: Long = MIN_FREE_BYTES,
) {
    private var wasWhisper = false
    private val normal = Rotor("devlog-", maxBytes, maxFiles, maxAgeMs, sync = false)
    private val important = Rotor("important-", importantBytes, importantFiles, importantAgeMs, sync = true)

    /** Times (ms) the normal files filled up, the last hour's, for the "log busy" line. */
    private val fills = ArrayDeque<Long>()
    private var lastBusyNote = Long.MIN_VALUE / 2
    private val tagCounts = HashMap<String, Int>()
    private var lowSpace = false
    private var lastSpaceCheck = Long.MIN_VALUE / 2
    private var lastFlush = 0L

    /** One set of rotating files, with its lines gathered before they are written. */
    private inner class Rotor(val prefix: String, val maxBytes: Long, val maxFiles: Int, val maxAgeMs: Long, val sync: Boolean) {
        var current: File? = null
        private var size = 0L
        private val pending = ArrayList<ByteArray>()
        private var pendingBytes = 0

        fun files(): List<File> =
            dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".txt") }?.sortedBy { it.name }.orEmpty()

        fun tidy() {
            val cutoff = now() - maxAgeMs
            files().forEach { if (it.lastModified() < cutoff && it != current) it.delete() }
            val left = files()
            if (left.size > maxFiles) left.take(left.size - maxFiles).forEach { if (it != current) it.delete() }
        }

        /** Gathers [text]; writes when enough has gathered (or at once for a rotor that syncs). Returns true if a new file was started. */
        fun add(text: String, atOnce: Boolean = false): Boolean {
            val line = ((if (text.length > MAX_LINE) text.take(MAX_LINE) + "[cut]" else text) + "\n").toByteArray()
            pending += line
            pendingBytes += line.size
            return if (atOnce || sync || bufferBytes <= 0 || pendingBytes >= bufferBytes) write() else false
        }

        fun hasPending() = pending.isNotEmpty()

        /** Writes what has gathered, in as few appends as the file size allows. Returns true if a new file was started. */
        fun write(): Boolean {
            if (pending.isEmpty()) return false
            var started = false
            runCatching {
                dir.mkdirs()
                var i = 0
                while (i < pending.size) {
                    var f = current
                    if (f == null || !f.exists() || size >= maxBytes) {
                        // Names sort by time; a counter keeps two made in the same millisecond apart.
                        val stamp = now()
                        var n = 0
                        do { f = File(dir, "%s%013d-%d.txt".format(prefix, stamp, n++)) } while (f!!.exists())
                        val wasThere = current != null
                        current = f
                        f.createNewFile()
                        size = 0
                        tidy()
                        if (wasThere) started = true
                    }
                    // As many whole lines as fit (at least one), in one append.
                    val room = maxBytes - size
                    var j = i
                    var bytes = 0L
                    val chunk = java.io.ByteArrayOutputStream()
                    while (j < pending.size && (j == i || bytes + pending[j].size <= room)) { chunk.write(pending[j]); bytes += pending[j].size; j++ }
                    java.io.FileOutputStream(f!!, true).use { out ->
                        out.write(chunk.toByteArray())
                        out.flush()
                        if (sync) out.fd.sync()
                    }
                    size += bytes
                    i = j
                }
            }
            pending.clear()
            pendingBytes = 0
            return started
        }

        fun clear() {
            current = null
            size = 0
            pending.clear()
            pendingBytes = 0
        }
    }

    /** Deletes files older than the age limit and, beyond the file limit, the oldest ones. */
    @Synchronized
    fun tidy() {
        normal.tidy()
        important.tidy()
    }

    /** Writes [line]; leaves out noise; during a Whisper writes nothing but one note. */
    @Synchronized
    fun write(line: String) {
        if (whisperOpen()) {
            if (!wasWhisper) { wasWhisper = true; toNormal("Whisper: not logged", "Whisper") }
            return
        }
        wasWhisper = false
        if (isNoise(line)) return
        toNormal(line, tagOf(line))
        if (isImportant(line)) important.add(line)
        tick()
    }

    /**
     * One normal chat message or answer: chat id, role, time and the text (cut at [CHAT_CUT]).
     * Never written while a Whisper is open or with the chats switch off.
     */
    @Synchronized
    fun chat(chatId: String, role: String, text: String) {
        if (whisperOpen() || !chatsOn()) return
        val clock = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(now()))
        val body = (if (text.length > CHAT_CUT) text.take(CHAT_CUT) + "[cut]" else text).replace("\r", "").replace("\n", "\\n")
        toNormal("$CHAT_MARK $chatId $role $clock $body", CHAT_MARK)
        tick()
    }

    /** The chats switch went off: the files are rewritten without the chat lines. */
    @Synchronized
    fun removeChatLines() {
        normal.write()
        normal.files().forEach { f ->
            runCatching {
                val keep = f.readLines().filter { !it.startsWith("$CHAT_MARK ") }
                if (keep.isEmpty()) f.delete() else f.writeText(keep.joinToString("\n") + "\n")
            }
        }
    }

    /** Written at once, for a crash or the start header: to both sets of files, and flushed (unless a Whisper is open). */
    @Synchronized
    fun writeNow(text: String) {
        if (whisperOpen()) return
        if (!lowSpace) normal.add(text, atOnce = true)
        important.add(text)
        lastFlush = now()
    }

    /** Writes what has gathered to the disk (the runner does this every few seconds, and a busy log does it by itself). */
    @Synchronized
    fun flush() {
        normal.write()
        important.write()
        lastFlush = now()
    }

    /** Switched off: every file goes at once. */
    @Synchronized
    fun clear() {
        normal.clear()
        important.clear()
        fills.clear()
        tagCounts.clear()
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun toNormal(text: String, tag: String) {
        if (spaceLow()) return
        tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
        if (normal.add(text)) filled()
    }

    /** The time-based part of buffering: a busy log writes at least every [flushMs]. */
    private fun tick() {
        if (now() - lastFlush >= flushMs) flush()
    }

    /** A normal file just filled up: two within an hour is a flood, and the important file says who. */
    private fun filled() {
        val t = now()
        fills += t
        while (fills.isNotEmpty() && t - fills.first() > HOUR_MS) fills.removeFirst()
        val top = tagCounts.maxByOrNull { it.value }
        tagCounts.clear()
        if (fills.size >= 2 && t - lastBusyNote > HOUR_MS) {
            lastBusyNote = t
            val minutes = ((t - fills.first()) / 60_000).coerceAtLeast(0)
            important.add("$BUSY log busy: ${fills.size} files of ${sizeWords(maxBytes)} filled within ${minutes + 1} min; most lines from ${top?.key ?: "unknown"} (${top?.value ?: 0} in the last file)")
        }
    }

    /** Under 1 GB free: the normal files go and only the important file is kept, until there is room again. */
    private fun spaceLow(): Boolean {
        val t = now()
        if (t - lastSpaceCheck < SPACE_CHECK_MS) return lowSpace
        lastSpaceCheck = t
        val free = runCatching { freeBytes() }.getOrDefault(Long.MAX_VALUE)
        if (!lowSpace && free < minFreeBytes) {
            lowSpace = true
            normal.clear()
            dir.listFiles { f -> f.isFile && f.name.startsWith("devlog-") }?.forEach { it.delete() }
            important.add("$BUSY low space: ${free / 1_000_000} MB free, so only this important log is kept until there is 1 GB again")
        } else if (lowSpace && free >= minFreeBytes + minFreeBytes / 5) {
            lowSpace = false
            important.add("$BUSY space is back (${free / 1_000_000} MB free): the full log is kept again")
        }
        return lowSpace
    }

    companion object {
        const val CHAT_MARK = "CHAT"
        const val BUSY = "LOG:"
        const val CHAT_CUT = 4000
        const val MAX_LINE = 16_000
        const val MAX_BYTES = 5_000_000L
        const val MAX_FILES = 20
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val IMPORTANT_FILE_BYTES = 5_000_000L
        const val IMPORTANT_FILES = 4
        const val IMPORTANT_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val BUFFER_BYTES = 64 * 1024
        const val FLUSH_MS = 5_000L
        const val MIN_FREE_BYTES = 1_000_000_000L
        private const val SPACE_CHECK_MS = 30_000L
        private const val HOUR_MS = 3_600_000L
        /** A stall of the screen longer than this is written to the important file (7 frames at 60 Hz is 117 ms). */
        const val STALL_FRAMES = 7

        private val FRAME_TAGS = setOf("ViewRootImpl", "VRI")
        private val FRAME_WORDS = listOf("framerate", "frame rate", "frame_rate", "preferredframe")

        /** A logcat line (threadtime) that is only noise: llama.cpp's dots, frame-rate chatter, GC. */
        private val THREADTIME = Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+([VDIWEF])\s+(.*?)\s*: (.*)$""")
        private val SKIPPED = Regex("""Skipped (\d+) frames""")
        private val FAILED_WORDS = Regex("""(?i)(fail|error|exception|couldn't|could not|wouldn't|refused|timed out|timeout|no signal|no sound from|unreadable|is down|answered [45]\d\d|blocked|unreachable)""")
        private val LOOKUP_TAGS = setOf("MimirLookup", "Net", "Search", "Weather", "Lookup", "TorLink", "Tor", "MimirStandalone")
        private val MODEL_WORDS = Regex("""(?i)(wouldn't load|couldn't load|failed to load|load failed|couldn't be loaded|model.*(not found|corrupt)|llama.*(error|failed))""")
        private val DATA_TAGS = setOf("MimirStore", "MimirVault")

        private fun sizeWords(b: Long) = if (b >= 1_000_000) "${b / 1_000_000} MB" else "${b / 1000} KB"

        fun tagOf(line: String): String = THREADTIME.find(line)?.groupValues?.get(2).orEmpty().ifEmpty { "other" }

        fun isNoise(line: String): Boolean {
            val m = THREADTIME.find(line)
            val tagName = m?.groupValues?.get(2).orEmpty()
            val msg = (m?.groupValues?.get(3) ?: line).trim()
            if (msg.isEmpty() || msg.all { it == '.' }) return true
            val lower = msg.lowercase()
            if (tagName.startsWith("ViewRootImpl") || tagName in FRAME_TAGS) {
                if (FRAME_WORDS.any { lower.contains(it) } || lower.startsWith("viewpostime pointer")) return true
            }
            if (tagName == "art" || tagName == "dalvikvm-heap") {
                if (msg.contains("GC") || lower.contains("paused") || lower.contains("freed")) return true
            }
            return false
        }

        /**
         * A line the important file keeps: an error or fatal line; a stall of the screen (Choreographer skipping
         * [STALL_FRAMES] frames or more, or a "Davey!"); a lookup or a model that failed; a chat or pairing file that
         * couldn't be read or saved; the start header; the log's own notes. Chat lines never.
         */
        fun isImportant(line: String): Boolean {
            if (line.startsWith("=== ") || line.startsWith("CRASH") || line.startsWith(BUSY)) return true
            if (line.startsWith("$CHAT_MARK ")) return false
            val m = THREADTIME.find(line) ?: return false
            val level = m.groupValues[1]
            val tag = m.groupValues[2]
            val msg = m.groupValues[3]
            if (level == "E" || level == "F") return true
            if (tag == "Choreographer") {
                SKIPPED.find(msg)?.let { return it.groupValues[1].toInt() >= STALL_FRAMES }
            }
            if (msg.startsWith("Davey!")) return true
            if (tag in DATA_TAGS && level != "D" && level != "V") return true
            if (MODEL_WORDS.containsMatchIn(msg)) return true
            if (tag in LOOKUP_TAGS && FAILED_WORDS.containsMatchIn(msg)) return true
            return false
        }
    }
}

/** The pieces of the start-of-run header, so the unit tests need no phone. */
object DevLogHeader {
    fun text(
        version: String, built: String, model: String, android: String, memoryMb: Long,
        chosen: String, think: String, paired: Boolean, lookups: Boolean, voice: String,
    ): String =
        "=== Mímir dev log === app $version (built $built) | device $model | Android $android | memory $memoryMb MB | " +
            "model $chosen | think/effort $think | paired ${if (paired) "yes" else "no"} | lookups ${if (lookups) "on" else "off"} | voice $voice"

    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
    fun androidName(): String = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    fun memoryMb(context: Context): Long {
        val info = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }
}

/** A chat as the log sees it: [user] says who wrote each message. */
data class SeenTurn(val user: Boolean, val text: String)

/**
 * Turns the engine's changing chat into log lines: each message once, as it is sent, and each
 * answer once, when it is finished. Chats that were already open when it started are not
 * replayed; Whispers are skipped whole.
 */
class ChatWatch(private val log: DevLog) {
    private val logged = HashMap<String, Int>()
    private var first = true

    fun see(chatId: String, whisper: Boolean, writing: Boolean, turns: List<SeenTurn>) {
        if (whisper) return
        val done = if (writing && turns.lastOrNull()?.user == false) turns.size - 1 else turns.size
        val had = logged[chatId]
        if (had == null) {
            // What was there already isn't replayed, except a brand-new chat's first message.
            val fresh = !first && turns.size <= 1 && turns.lastOrNull()?.user == true
            logged[chatId] = if (fresh) 0 else turns.size
            first = false
            if (!fresh) return
        }
        first = false
        var n = logged[chatId] ?: 0
        if (n > done) { logged[chatId] = done; return } // messages went away (edited, cleared)
        while (n < done) { val t = turns[n]; log.chat(chatId, if (t.user) "you" else "answer", t.text); n++ }
        logged[chatId] = n
    }
}
