package io.github.odnwks.mimir.alerts

import android.content.Context

/**
 * Tasks whose conversation runs outside Mímir (a terminal, the Claude app on the PC). They never
 * count toward "Working on" and never keep the private link open on their own, and by default
 * alert only when they need the person. A task stays marked until the person carries on in it
 * from this phone, after which it is theirs.
 */
object OutsideTasks {
    private const val FILE = "outside_tasks"
    private val ids = HashSet<String>()
    @Volatile private var app: Context? = null

    /** Loads what was marked before the app was closed (called once, when the station is made). */
    @Synchronized fun init(context: Context) {
        app = context.applicationContext
        ids.addAll(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet("ids", emptySet()).orEmpty())
    }

    @Synchronized fun contains(task: String?): Boolean = task != null && task in ids

    /** The PC says [task] is live outside Mímir ([live]) or not; true marks it. A mark stays until [mine]. */
    @Synchronized fun seen(task: String, live: Boolean) {
        if (live && ids.add(task)) save()
    }

    /** The person carried on in [task] from this phone: it is theirs from now on. */
    @Synchronized fun mine(task: String) {
        if (ids.remove(task)) save()
    }

    @Synchronized fun forgetAll() {
        ids.clear()
        save()
    }

    private fun save() {
        app?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putStringSet("ids", HashSet(ids))?.apply()
    }
}
