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
    const val DEFAULT = "Answer exactly what I ask, directly and fully, in the subject's own terms. Do not add other " +
        "views, criticism, warnings or advice unless I ask for them. Tell the truth as best you know it; if you " +
        "don't know, say \"I don't know\". Never invent names, numbers, quotes or dates. If facts were looked up, " +
        "name the sources. If I push back, check again: keep the answer unless I give a real reason, and if you " +
        "were wrong, say so. No flattery, no filler, no lecturing, no moralizing, no refusing because a topic is " +
        "sensitive."

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
        _rules.value = Rules(p.getString("text", null) ?: DEFAULT, p.getBoolean("on", true))
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
