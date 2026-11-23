package io.github.odnwks.mimir.alerts

import android.content.Context

/**
 * Tasks whose conversation runs outside Mímir (a terminal, the Claude app on the PC). They never
 * count toward "Working on" and never keep the private link open on their own, and by default
 * alert only when they need the person.
 *
 * A PC that says it itself ("outside" in the task header, kept on the PC until the person acts on
 * the task in Mímir) is believed. For an older PC the phone keeps its own mark: set when the task is
 * seen live outside, kept when it goes quiet, and never set again once the person carried on here
 * ("mine"). Marks go when the PC stops listing the task. Nothing is judged for a task whose
 * header hasn't come in this connection ([known]): its alerts wait.
 */
object OutsideTasks {
    private const val FILE = "outside_tasks"
    private val marks = HashSet<String>()
    private val mineIds = HashSet<String>()
    private val said = HashMap<String, Boolean>()
    private val heard = HashSet<String>()
    @Volatile private var app: Context? = null

    /** Loads what was marked before the app was closed (called once, when the station is made). */
    @Synchronized fun init(context: Context) {
        app = context.applicationContext
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        marks.addAll(p.getStringSet("ids", emptySet()).orEmpty())
        mineIds.addAll(p.getStringSet("mine", emptySet()).orEmpty())
    }

    /** A new connection: every task's header is unknown again until it comes in. */
    @Synchronized fun newConnection() {
        heard.clear()
        said.clear()
    }

    /** A task's header came in. [pcSays]: its "outside" field, null from an older PC. */
    @Synchronized fun header(task: String, live: Boolean, pcSays: Boolean?) {
        heard.add(task)
        if (pcSays != null) said[task] = pcSays
        else if (live && task !in mineIds && marks.add(task)) save()
    }

    /** Whether the task's header has been seen in this connection (a task with no id is always known). */
    @Synchronized fun known(task: String?): Boolean = task == null || task in heard

    @Synchronized fun contains(task: String?): Boolean = task != null && (said[task] ?: (task in marks))

    /** The person carried on in [task] from this phone: it is theirs from now on. */
    @Synchronized fun mine(task: String) {
        said[task] = false
        val a = marks.remove(task)
        val b = mineIds.add(task)
        if (a || b) save()
    }

    /** The PC no longer lists [task]: nothing is remembered about it. */
    @Synchronized fun removed(task: String) {
        said.remove(task); heard.remove(task)
        val a = marks.remove(task)
        val b = mineIds.remove(task)
        if (a || b) save()
    }

    @Synchronized fun forgetAll() {
        marks.clear(); mineIds.clear(); said.clear(); heard.clear()
        save()
    }

    private fun save() {
        app?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()
            ?.putStringSet("ids", HashSet(marks))?.putStringSet("mine", HashSet(mineIds))?.apply()
    }
}
