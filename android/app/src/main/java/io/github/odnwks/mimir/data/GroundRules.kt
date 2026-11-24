package io.github.odnwks.mimir.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ground rules (Settings › Who answers › Ground rules): on by default, editable, Reset to default.
 * Every model on this phone gets them as its instructions (Mímir and Whisper), and a task, chat
 * message or Whisper task sent to the PC carries them (the PC uses them instead of its own).
 */
object GroundRules {
    /** 0.09 item 25: what the models are told about looking things up (added to the approved text). */
    const val LOOKUPS = "Mímir looks things up for you, on the web and in the encyclopedias, when a question " +
        "needs it, and puts the results in front of you; you do not search yourself, Mímir does, whether you " +
        "run on this phone or on the PC. Never say you cannot look things up; if no results came, say that " +
        "none were found."

    const val DEFAULT = "Answer exactly what I ask, directly and fully, in the subject's own terms. Do not add other " +
        "views, criticism, warnings or advice unless I ask for them. Tell the truth as best you know it; if you " +
        "don't know, say \"I don't know\". Never invent names, numbers, quotes or dates. If facts were looked up, " +
        "name the sources. If I push back, check again: keep the answer unless I give a real reason, and if you " +
        "were wrong, say so. No flattery, no filler, no lecturing, no moralizing, no refusing because a topic is " +
        "sensitive. " + LOOKUPS

    /** The default before 0.09 item 25 (stored copies of it were never edited). */
    private const val DEFAULT_0_08 = "Answer exactly what I ask, directly and fully, in the subject's own terms. Do not add other " +
        "views, criticism, warnings or advice unless I ask for them. Tell the truth as best you know it; if you " +
        "don't know, say \"I don't know\". Never invent names, numbers, quotes or dates. If facts were looked up, " +
        "name the sources. If I push back, check again: keep the answer unless I give a real reason, and if you " +
        "were wrong, say so. No flattery, no filler, no lecturing, no moralizing, no refusing because a topic is " +
        "sensitive."

    /**
     * Earlier default texts. Stored text equal to one of these was never edited by the owner (an old
     * version stored its default), so it is dropped and the current default applies.
     */
    val OLD_DEFAULTS: List<String> = listOf(
        DEFAULT_0_08,
        "Tell the truth as best you know it. If you don't know, say \"I don't know\"; if you're unsure, say " +
        "so with a short phrase like \"I'm fairly sure\" or \"I'm not sure\". Never invent names, numbers, " +
        "quotes or dates. On disputed questions, give the strongest evidence and the main views fairly, and " +
        "don't take a side on politics or values unless asked for your view, and then label it as yours. If I" +
        " push back on an answer, check it again: keep it unless I give a real reason, and if you were wrong," +
        " say so and why. Don't change an answer just to please me. If facts were looked up, name the sources" +
        " you used, and say \"I couldn't confirm this\" when they are thin or disagree. No flattery, no " +
        "praise for the question, no filler. Answer what I ask directly and fully, without lecturing, " +
        "moralizing, or refusing because a topic is sensitive.",
    )

    fun isOldDefault(text: String) = text.trim() in OLD_DEFAULTS

    const val NOTE = "Online answers follow their own safety rules on top of these; free and on-phone models follow these as written."

    data class Rules(val text: String = DEFAULT, val on: Boolean = true) {
        /** What is sent: the text while on and not empty, otherwise nothing. */
        val sent: String? get() = text.trim().takeIf { on && it.isNotEmpty() }
    }

    private val _rules = MutableStateFlow(Rules())
    val rules: StateFlow<Rules> = _rules.asStateFlow()

    /** What every model is told now, or null when the rules are off (or emptied). */
    val current: String? get() = _rules.value.sent

    private var prefs: android.content.SharedPreferences? = null

    /** When the rules were last changed here (ms since 1970; 0: never, the default). */
    @Volatile var at: Long = 0L
        private set

    /** Told when the owner changes them here (the link sends them to the PC). */
    @Volatile var onChange: (() -> Unit)? = null

    /** Reads the owner's rules once the app starts (until then, the default). */
    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences("ground_rules", 0)
        prefs = p
        val stored = p.getString("text", null)?.takeUnless { isOldDefault(it) }
        if (stored == null) p.edit().remove("text").apply()
        _rules.value = Rules(stored ?: DEFAULT, p.getBoolean("on", true))
        at = p.getLong("at", 0L)
    }

    fun set(rules: Rules) {
        keep(rules, System.currentTimeMillis())
        onChange?.invoke()
    }

    /**
     * The PC's rules (Stage 1, 1.5): whichever side changed them last wins. Empty text from the PC
     * means they're off there.
     */
    fun fromPc(text: String, changed: Long) {
        if (changed <= at) return
        keep(if (text.isBlank()) _rules.value.copy(on = false) else Rules(text, true), changed)
    }

    private fun keep(rules: Rules, changed: Long) {
        _rules.value = rules
        at = changed
        prefs?.edit()?.apply {
            putLong("at", changed)
            // The default isn't stored, so a better default in a later version reaches the owner.
            if (rules.text == DEFAULT) remove("text") else putString("text", rules.text)
            putBoolean("on", rules.on)
        }?.apply()
    }

    fun reset() = set(Rules(DEFAULT, _rules.value.on))
}
