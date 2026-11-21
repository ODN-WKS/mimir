package io.github.odnwks.mimir.labs

/**
 * Takes anything private out of a log line before it goes into a problem report: message text, chat
 * titles, names, email addresses, phone numbers, IP addresses, the query part of web addresses,
 * file paths, keys and tokens, device numbers and the PC's name. It over-scrubs on purpose:
 * a report that says `<name>` too often is better than one that says a name once.
 *
 * The PC window has the same function in Rust (`mimir_core::scrub`) with the same rules and the
 * same test lines; change both together.
 */
object Scrub {
    const val MESSAGE = "<message removed>"

    private val EMAIL = Regex("""[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")
    private val URL = Regex("""(?i)\b(?:https?|wss?|ftp)://[^\s<>"')\]]+""")
    private val FILE_URL = Regex("""(?i)\bfile://\S*""")
    private val PATH = Regex("""(?<![\w/.:~-])(?:/(?:home|storage|data|sdcard|Users|root|mnt|run|tmp|var|proc|opt|media|Download|Android)(?:/[^\s'",;)\]}]*)?)""")
    private val WIN_PATH = Regex("""(?i)(?<![\w])[a-z]:\\[^\s'",;)\]}]+""")
    private val MAC = Regex("""(?i)(?<![\w:])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![\w:])""")
    private val IPV4 = Regex("""(?<![\w.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?![\w])""")
    private val IPV6_FULL = Regex("""(?i)(?<![\w:])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![\w:])""")
    private val IPV6_SHORT = Regex("""(?i)(?<![\w:])(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?(?![\w:])""")
    private val UUID = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
    private val TOKEN_PREFIXED = Regex("""\b(?:sk-[A-Za-z0-9_-]{6,}|gsk_[A-Za-z0-9]{6,}|ghp_[A-Za-z0-9]{6,}|gho_[A-Za-z0-9]{6,}|github_pat_[A-Za-z0-9_]{6,}|xox[abprs]-[A-Za-z0-9-]{6,}|AIza[A-Za-z0-9_-]{10,}|AKIA[A-Z0-9]{12,}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_.-]+)""")
    private val BEARER = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}""")
    private val SECRET_PAIR = Regex("""(?i)\b((?:api[_-]?key|apikey|key|secret|token|access[_-]?token|auth|authorization|password|passwd|pwd|pass|passphrase|cookie|session)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;&]+)""")
    private val ID_PAIR = Regex("""(?i)\b((?:serial(?:[_ -]?(?:no|number))?|ro\.serialno|imei|imsi|meid|android[_-]?id|ssaid|device[_-]?id|iccid|advertising[_-]?id)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;&]+)""")
    private val NAME_PAIR = Regex("""(?i)\b((?:title|chat[_ -]?title|pc[_-]?name|phone[_-]?name|device[_-]?name|user[_-]?name|username|user|owner|nickname|project[_-]?name|project|folder|host[_-]?name|hostname|host|ssid|bssid|account|email|e-mail|contact|author)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;&]+)""")
    private val PLACE_PAIR = Regex("""(?i)\b((?:lat|lon|lng|latitude|longitude|place|city|town|location|address|postcode|zip)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;&]+)""")
    private val TEXT_PAIR = Regex("""(?i)\b((?:text|message|msg|prompt|body|content|query|question|answer|reply|transcript)\s*[=:]\s*)("(?:[^"\\]|\\.)*"|'[^']*'|\S.*)""")
    private val JSON_TEXT = Regex("""(?i)("(?:text|message|content|prompt|body|title|name|query|question|answer|reply|transcript|user|owner|email)"\s*:\s*)"(?:[^"\\]|\\.)*"""")
    private val LONG_HEX = Regex("""(?i)(?<![\w])(?=[0-9a-f]*\d)(?=[0-9a-f]*[a-f])[0-9a-f]{16,}(?![\w])""")
    private val LONG_BLOB = Regex("""(?<![\w])(?=[A-Za-z0-9+/_=-]*\d)(?=[A-Za-z0-9+/_=-]*[A-Za-z])[A-Za-z0-9+/_-]{28,}={0,2}(?![\w])""")
    private val LONG_DIGITS = Regex("""(?<![\w.])\d{14,}(?![\w])""")
    private val PHONE = Regex("""(?<![\w.:/-])\+?\(?\d[\d ()\-.]{6,}\d(?![\w:])""")
    private val CHAT_LINE = Regex("""^\s*CHAT\b""")

    /** One line, scrubbed. [names] are exact words to hide wherever they appear (the PC's name, chat titles, the phone's name). */
    fun line(raw: String, names: Collection<String> = emptyList()): String {
        if (CHAT_LINE.containsMatchIn(raw)) return "CHAT $MESSAGE"
        var s = raw
        s = FILE_URL.replace(s, "<path>")
        s = URL.replace(s) { m -> stripQuery(m.value) }
        s = BEARER.replace(s) { it.groupValues[1] + " <key>" }
        s = JSON_TEXT.replace(s) { it.groupValues[1] + "\"<removed>\"" }
        s = TEXT_PAIR.replace(s) { it.groupValues[1] + MESSAGE }
        s = ID_PAIR.replace(s) { it.groupValues[1] + "<id>" }
        s = SECRET_PAIR.replace(s) { it.groupValues[1] + "<key>" }
        s = NAME_PAIR.replace(s) { it.groupValues[1] + "<name>" }
        s = PLACE_PAIR.replace(s) { it.groupValues[1] + "<place>" }
        s = EMAIL.replace(s, "<email>")
        s = TOKEN_PREFIXED.replace(s, "<key>")
        s = WIN_PATH.replace(s, "<path>")
        s = PATH.replace(s, "<path>")
        s = UUID.replace(s, "<id>")
        s = MAC.replace(s, "<id>")
        s = IPV4.replace(s, "<ip>")
        s = IPV6_FULL.replace(s, "<ip>")
        s = IPV6_SHORT.replace(s, "<ip>")
        s = LONG_HEX.replace(s, "<id>")
        s = LONG_BLOB.replace(s, "<key>")
        s = LONG_DIGITS.replace(s, "<id>")
        s = PHONE.replace(s) { m -> if (looksLikePhone(m.value)) "<phone>" else m.value }
        for (n in names.map { it.trim() }.filter { it.length >= 2 }.sortedByDescending { it.length }) {
            s = Regex("(?i)" + Regex.escape(n)).replace(s, "<name>")
        }
        return s
    }

    /** A web address without its user, query and fragment. */
    private fun stripQuery(url: String): String {
        var u = url.trimEnd('.', ',', ';', ':', '!')
        val tail = url.substring(u.length)
        u = u.substringBefore('?').substringBefore('#')
        u = u.replace(Regex("""^([a-z]+://)[^/@]*@""", RegexOption.IGNORE_CASE), "$1")
        return u + tail
    }

    private fun looksLikePhone(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        if (digits < 9 || digits > 15) return false
        if (Regex("""^\d{4}-\d{2}-\d{2}$""").matches(text.trim())) return false
        val plain = text.all { it.isDigit() }
        return !plain || digits in 10..11
    }

    /** The newest [max] lines worth keeping from [raw], scrubbed; lines of chat text are dropped, empty ones too. */
    fun lines(raw: List<String>, names: Collection<String> = emptyList(), max: Int = 50): List<String> =
        raw.filter { it.isNotBlank() && !CHAT_LINE.containsMatchIn(it) }
            .takeLast(max)
            .map { line(it, names).take(400) }
}
