package io.github.odnwks.mimir.audio

import java.text.Normalizer

/**
 * The PC's read-aloud text rules (say-update), so a voice on the phone sounds the way it does on
 * the PC: the text cleaned twice (plain words, then only characters the engine may see), split
 * into phrases, each followed by the same pause (comma 250 ms, full stop 500 ms, line break 400 ms).
 */
object Phrases {
    const val PAUSE_COMMA = 250
    const val PAUSE_STOP = 500
    const val PAUSE_LINE = 400
    private const val MAX_TEXT = 4000
    private const val MAX_WORD = 40
    private const val MAX_PIECE = 400

    /** One phrase to speak, and the silence after it in milliseconds. */
    data class Phrase(val text: String, val pauseMs: Int)

    private val link = Regex("https?://\\S+")
    private val code = Regex("`([^`]*)`")
    private val marks = Regex("[*_#>|]+")
    private val markup = Regex("[<>\\[\\]{}\\\\]")
    private val longWord = Regex("\\S{${MAX_WORD + 1},}")
    private val spaces = Regex("[ \\t]+")
    private val split = Regex("(?<=[.!?…])\\s+|(?<=[,;:])\\s+")
    private const val ALLOWED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,!?;:'\"()-/%+"

    /** Markdown, links and control characters out; long dashes become commas. */
    fun plain(text: String): String {
        var t = link.replace(text, " link ")
        t = code.replace(t, "$1")
        t = marks.replace(t, " ")
        t = markup.replace(t, " ")
        t = t.filter { it == '\n' || it == '\t' || (it.code >= 32 && it.code !in 0x7f until 0xa0) }
        t = t.replace("—", ", ").replace("–", ", ")
        t = longWord.replace(t, " ")
        t = spaces.replace(t, " ")
        return t.take(MAX_TEXT)
    }

    /** Only what the engine may see: letters (accented ones kept), digits and plain punctuation. */
    fun forEngine(phrase: String): String {
        val p = phrase.replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')
        val out = StringBuilder()
        for (ch in p) {
            if (ch in ALLOWED) { out.append(ch); continue }
            val d = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
            val base = d[0]
            out.append(if (base in ALLOWED && base.isLetter() && d.length <= 3) ch else ' ')
        }
        return Regex(" +").replace(out, " ").trim()
    }

    /** The phrases of [text], each with its pause; the last one has none. */
    fun of(text: String): List<Phrase> {
        val res = ArrayList<Pair<String, Int>>()
        for (raw in plain(text).split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = split.split(line)
            parts.forEachIndexed { i, piece ->
                var part = piece.trim()
                if (part.isEmpty()) return@forEachIndexed
                while (part.length > MAX_PIECE) {
                    val space = part.lastIndexOf(' ', MAX_PIECE - 1)
                    val cut = if (space > 0) space else MAX_PIECE
                    res += part.take(cut) to PAUSE_COMMA
                    part = part.substring(cut).trim()
                }
                if (part.isEmpty()) return@forEachIndexed
                val last = part.last()
                var pause = when (last) {
                    '.', '!', '?', '…' -> PAUSE_STOP
                    ',', ';', ':' -> PAUSE_COMMA
                    else -> PAUSE_STOP
                }
                if (i == parts.size - 1) pause = maxOf(pause, PAUSE_LINE)
                res += part to pause
            }
        }
        if (res.isNotEmpty()) res[res.size - 1] = res.last().first to 0
        return res.mapNotNull { (t, p) -> forEngine(t).takeIf { it.isNotEmpty() }?.let { Phrase(it, p) } }
    }
}
