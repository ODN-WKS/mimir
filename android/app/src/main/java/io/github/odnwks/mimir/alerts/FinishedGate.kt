package io.github.odnwks.mimir.alerts

import io.github.odnwks.mimir.link.LFinished

/**
 * Which finished items may alert: one when work really ends (the task reads Done or Stopped),
 * once for each time work started on it (never every turn, and never again for a finish already
 * told, also after the person swiped it away), and none while the task's header hasn't come in
 * this connection (then it is not known whether it runs outside Mímir): those wait for it.
 */
object FinishedGate {
    private val pending = ArrayList<LFinished>()
    private val status = HashMap<String, String>()
    private val epoch = HashMap<String, Int>()
    private val told = HashMap<String, Int>()
    private val busy = HashSet<String>()

    /** A task's header came in: [status] and whether it works now. */
    @Synchronized fun header(task: String, status: String, busyNow: Boolean) {
        this.status[task] = status
        if (busyNow) { if (busy.add(task)) epoch[task] = (epoch[task] ?: 0) + 1 } else busy.remove(task)
    }

    /** The items held for [task] (its header came): to be judged again. */
    @Synchronized fun release(task: String): List<LFinished> {
        val out = pending.filter { it.task == task }
        pending.removeAll(out.toSet())
        return out
    }

    @Synchronized fun removed(task: String) {
        pending.removeAll { it.task == task }
        status.remove(task); epoch.remove(task); told.remove(task); busy.remove(task)
    }

    @Synchronized fun clear() { pending.clear(); status.clear(); epoch.clear(); told.clear(); busy.clear() }

    /** The items to go on with; the rest wait or are dropped. */
    @Synchronized fun pass(items: List<LFinished>, known: (String?) -> Boolean): List<LFinished> {
        val out = ArrayList<LFinished>()
        for (f in items) {
            if (!known(f.task)) {
                if (pending.none { it.id == f.id }) pending += f
                while (pending.size > 100) pending.removeAt(0)
                continue
            }
            val st = status[f.task]
            if (st != null && st != "Done" && st != "Stopped") continue
            val e = epoch[f.task] ?: 0
            if (told[f.task] == e) continue
            told[f.task] = e
            out += f
        }
        return out
    }
}
