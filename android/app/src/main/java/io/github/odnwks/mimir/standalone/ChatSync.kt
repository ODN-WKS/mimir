package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.link.LChatRecord
import io.github.odnwks.mimir.link.LChatTurn

/**
 * One history on the phone and the PC (Stage 1, 2.5). The phone is the record for what it holds;
 * the PC is the meeting point. Pure rules, so the checks run them without a link:
 *
 * *   turns are append-only with ids, and only those the PC hasn't got yet travel ([unsent]);
 * *   an answer with a higher version replaces a lower one, whichever side has it;
 * *   turns added on both sides while apart are ordered by (time, the message before its answer, id);
 * *   a deleted chat never comes back (the PC keeps its tombstone; the phone drops its copy).
 */
object ChatSync {
    /** The turns of [c] the PC hasn't confirmed yet. */
    fun unsent(c: LocalChat): List<Turn> = c.turns.filter { it.seq == 0L }

    /** The turns of [c] cut away by an edit that the PC hasn't been told about yet. */
    fun unsentCuts(c: LocalChat): List<Cut> = c.cuts.filter { !it.sent }

    fun fromWire(t: LChatTurn): Turn = Turn(
        t.user, t.text,
        sources = t.sources.map { io.github.odnwks.mimir.online.Source(it.site, it.url, it.words, it.results, it.via) },
        id = t.id, at = t.at, ran = t.ran, modelWords = t.modelWords, version = t.version, seq = t.seq, switchNote = t.switchNote,
    )

    /** The PC's [r] (only its changed turns) merged into the phone's copy ([local]; null: a chat new to this phone). */
    fun merge(local: LocalChat?, r: LChatRecord, model: String): LocalChat {
        val base = local ?: LocalChat(r.id, model, emptyList(), r.name, r.at)
        val mine = base.turns.associateBy { it.id }
        val fresh = ArrayList<Turn>()
        // A message waiting here (held, say) that the PC already has isn't listed a second time.
        val waiting = base.queued.mapTo(HashSet()) { it.id }
        val replaced = HashMap<String, Turn>()
        var cuts = base.cuts
        val cutAway = HashSet<String>()
        for (t in r.turns) {
            // A cut (an edit here or on another device): the turn goes, and an older copy never returns.
            if (t.gone) {
                if (cuts.none { it.id == t.id && it.version >= t.version }) cuts = cuts.filter { it.id != t.id } + Cut(t.id, t.user, t.at, t.version, sent = true)
                if (mine[t.id]?.let { it.version < t.version } == true) cutAway += t.id
                continue
            }
            val cut = cuts.firstOrNull { it.id == t.id }
            if (cut != null) {
                if (cut.version >= t.version) continue
                cuts = cuts.filter { it.id != t.id }
            }
            val have = mine[t.id]
            when {
                have == null -> if (t.id !in waiting) fresh += fromWire(t)
                t.version > have.version -> replaced[t.id] = fromWire(t)
                // The same turn: the phone's copy keeps its pictures and files, and learns the PC's number.
                t.version == have.version && have.seq == 0L -> replaced[t.id] = have.copy(seq = t.seq)
            }
        }
        var turns = base.turns.filter { it.id !in cutAway }.map { replaced[it.id] ?: it } + fresh.sortedWith(ORDER)
        // Everything timed: one order on every device, whatever arrived where.
        if (fresh.isNotEmpty() && turns.all { it.at > 0 }) turns = inOrder(turns)
        return base.copy(turns = turns, name = r.name ?: base.name, at = maxOf(base.at, r.at), cuts = cuts)
    }

    /**
     * [answer] added to [turns] once: when the sync already brought the PC's copy of it (same id), that copy
     * is replaced (a higher or equal version wins, keeping the PC's number) instead of listed twice.
     */
    fun withAnswer(turns: List<Turn>, answer: Turn): List<Turn> {
        if (answer.id.isEmpty() || turns.none { it.id == answer.id }) return turns + answer
        return turns.map { if (it.id == answer.id) (if (answer.version >= it.version) answer.copy(seq = maxOf(answer.seq, it.seq)) else it) else it }
    }

    /** [ORDER], except that an answer (its id is the message's id and ".a") never comes before its message, whatever the clocks say. */
    fun inOrder(turns: List<Turn>): List<Turn> {
        val asked = turns.filter { it.user }.associateBy { it.id }
        fun at(t: Turn) = if (!t.user && t.id.endsWith(".a")) maxOf(t.at, asked[t.id.removeSuffix(".a")]?.at ?: 0L) else t.at
        return turns.sortedWith(compareBy<Turn>({ at(it) }, { if (it.user) 0 else 1 }, { it.id }))
    }

    /** (time, the message before its answer, id). */
    val ORDER: Comparator<Turn> = compareBy<Turn>({ it.at }, { if (it.user) 0 else 1 }, { it.id })
}
