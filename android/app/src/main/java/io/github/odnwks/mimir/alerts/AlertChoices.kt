package io.github.odnwks.mimir.alerts

import android.content.Context
import android.content.SharedPreferences
import io.github.odnwks.mimir.data.NeedsKind

/** What to do with a session that runs outside Mímir (a terminal, the Claude app on the PC). */
enum class OutsideMode(val title: String) {
    NeedMe("Only when they need me"),
    AlsoFinished("When they finish too"),
    Off("Off"),
}

/**
 * Which alerts the person wants (Settings > Notifications > What to tell me). The defaults keep
 * alerts few and true: only what needs the person is on; finished tasks, updates and the ongoing
 * "working" note stay off until switched on; sessions from other apps only when they need the person.
 * A choice the person has already saved is kept as it is.
 */
data class AlertChoices(
    val needsYou: Boolean = true,
    val taskFinished: Boolean = false,
    val outside: OutsideMode = OutsideMode.NeedMe,
    val updates: Boolean = false,
    val showWorking: Boolean = false,
)

/** The pure rules: what is posted for a set of choices. */
object AlertRules {
    /** Whether an item that needs the person gets an alert. [outside]: its task runs outside Mímir. */
    fun postNeeds(c: AlertChoices, kind: NeedsKind?, outside: Boolean): Boolean {
        if (outside) return c.outside != OutsideMode.Off
        return when (kind) {
            NeedsKind.Release -> c.updates
            else -> c.needsYou
        }
    }

    /** Whether a finished task gets an alert. */
    fun postFinished(c: AlertChoices, outside: Boolean): Boolean =
        if (outside) c.outside == OutsideMode.AlsoFinished else c.taskFinished

    /** Whether the ongoing "Working on" note, and the link kept open for instant alerts, are on. */
    fun showWorking(c: AlertChoices): Boolean = c.showWorking
}

/** The choices, kept in the app's settings (they apply at once and survive a restart). */
object AlertStore {
    private const val FILE = "alert_choices"

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context): AlertChoices {
        val p = prefs(context)
        val d = AlertChoices()
        return AlertChoices(
            needsYou = p.getBoolean("needs", d.needsYou),
            taskFinished = p.getBoolean("finished", d.taskFinished),
            outside = OutsideMode.entries.firstOrNull { it.name == p.getString("outside", null) } ?: d.outside,
            updates = p.getBoolean("updates", d.updates),
            showWorking = p.getBoolean("working", d.showWorking),
        )
    }

    fun set(context: Context, c: AlertChoices) {
        prefs(context).edit()
            .putBoolean("needs", c.needsYou).putBoolean("finished", c.taskFinished)
            .putString("outside", c.outside.name).putBoolean("updates", c.updates).putBoolean("working", c.showWorking)
            .apply()
    }

    fun change(context: Context, f: (AlertChoices) -> AlertChoices): AlertChoices = f(get(context)).also { set(context, it) }
}
