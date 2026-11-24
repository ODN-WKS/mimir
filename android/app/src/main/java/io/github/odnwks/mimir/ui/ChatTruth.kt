package io.github.odnwks.mimir.ui

import android.content.Context
import io.github.odnwks.mimir.data.Usage
import io.github.odnwks.mimir.standalone.Turn

/** 0.09 items 23, 26 and 11: the chat screen says who answered, who answers now, and opens the keyboard by itself. */

const val KEYBOARD_AUTO = "keyboard_auto"

/** "Open the keyboard by itself": on unless chosen off. */
fun keyboardAutoOn(ctx: Context): Boolean = ctx.getSharedPreferences(PHONE_CHAT_PREFS, 0).getBoolean(KEYBOARD_AUTO, true)

/**
 * The line under an answer naming who wrote it: "This phone · Qwen 3.5", "Your PC · Qwen 3.6" or "Claude · Sonnet 4.6".
 * Null when nothing is known (an old answer). An online service's answer names the service as it was kept.
 */
fun answerAuthor(turn: Turn, pcName: String): String? {
    val words = turn.modelWords?.trim().orEmpty()
    return when (turn.ran) {
        "phone" -> when {
            words.isEmpty() -> "This phone"
            // The phone's own model was kept as "Qwen 3.5 · This phone": who first.
            words.endsWith("· This phone") -> "This phone · " + words.removeSuffix("· This phone").trim()
            // An online service the person set up was kept as "Service · model".
            else -> words
        }
        "pc" -> if (words.isEmpty() || words == pcName) "Your PC" else "Your PC · $words"
        "paid" -> "Claude" + (claudeModelWord(words)?.let { " · $it" } ?: if (words.isNotEmpty() && !words.startsWith("claude", true)) " · $words" else "")
        else -> null
    }
}

/**
 * The top bar's word for who answers the next message, and why when it isn't the usual one. [usePc] is the chosen place;
 * the phone takes over when the PC is away, or when its last answer came from this phone with a switch note.
 */
data class WhoNow(val phoneTookOver: Boolean, val why: String?)

fun whoNow(paired: Boolean, usePc: Boolean, phoneCan: Boolean, pcOnline: Boolean, lastRan: String?, lastNote: String?, whisper: Boolean, online: Boolean): WhoNow {
    if (!paired || !usePc || !phoneCan || whisper || online) return WhoNow(false, null)
    if (!pcOnline) return WhoNow(true, "Your PC isn't reachable, so this phone answers")
    if (lastRan == "phone") return WhoNow(true, lastNote?.takeIf { it.isNotBlank() } ?: "Your PC's model isn't running, so this phone answers")
    return WhoNow(false, null)
}

/** Claude's use left this week in plain words, on the chat screen and in the side menu; "not read yet" when the phone has no reading. */
fun usageLeftWords(u: Usage): String =
    if (u.percentText == "—") "Claude use this week: not read yet" else "Claude use left this week: ${100 - u.percent}%" + (u.resets.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

/** The side menu's right-hand word: the share left, or "not read yet". */
fun usageLeftShort(u: Usage): String = if (u.percentText == "—") "not read yet" else "${100 - u.percent}%"
