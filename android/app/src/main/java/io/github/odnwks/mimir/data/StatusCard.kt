package io.github.odnwks.mimir.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.ZoneId

/**
 * The Status card in a chat: asking "status" shows what runs on the PC, built on the phone from what
 * the PC already sent. No model runs, nothing is sent, and nothing is written to disk: the card is
 * never part of a conversation an AI reads.
 */

/** One step of a task's checklist, as the PC keeps it. */
data class PlanStep(val text: String, val done: Boolean, val doing: Boolean)

/** A running or finished job. [percent] is null unless the PC has a checklist for it: never a guess. */
data class StatusRow(
    val taskId: String?,
    val title: String,
    /** "Your PC", or where else on it a session runs, then the project. */
    val where: String,
    /** Seconds since 1970: when its work began (running) or ended (finished); 0 when the PC didn't say. */
    val timeUnix: Long,
    val percent: Int? = null,
    /** "step 3 of 5"; "" without a checklist. */
    val step: String = "",
    /** The checklist's next step after the one being done; "" when there is none. */
    val next: String = "",
)

/** Something that waits for the person. */
data class StatusNeed(val taskId: String?, val words: String)

data class StatusSnapshot(
    /** When these facts were true (ms): now, or when the PC was last reached. 0 when not known. */
    val asOfMs: Long,
    val away: Boolean,
    val running: List<StatusRow>,
    val needsYou: List<StatusNeed>,
    val finished: List<StatusRow>,
)

private val ASKS = setOf(
    "status", "/status", "status report", "status update", "status check", "status now", "any status", "show status", "show me the status",
    "give me a status", "give me a status report", "give me a status update", "what's the status", "whats the status", "what is the status",
    "what's the status now", "what's running", "whats running", "what is running", "what's still running",
)

/** Whether [text] asks for the card: the whole message, so "fix the status bar" still goes to the AI. */
fun asksStatus(text: String): Boolean {
    val t = text.trim().lowercase().replace('’', '\'').trimEnd('?', '!', '.', ' ')
        .replace(Regex("\\s+"), " ").removePrefix("please ").removeSuffix(" please")
    return t in ASKS
}

private val RUNNING = setOf("Working", "Starting", "Live", "Answering…", "Was working (PC away)")

/** How many finished jobs the card lists before "and N more". */
const val STATUS_FINISHED_SHOWN = 5

fun statusSnapshot(state: StationState, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): StatusSnapshot {
    val away = !state.pc.online
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    fun isToday(unix: Long) = unix > 0 && Instant.ofEpochSecond(unix).atZone(zone).toLocalDate() == today
    // A Whisper's title is its words: the card names it only as a Whisper.
    fun title(t: Task) = if (t.whisper) "Whisper task" else t.title
    // The project goes with the device: a new task may be named by little more than its date.
    fun at(device: String, project: String) = listOf(device, project).filter { it.isNotBlank() }.joinToString(" · ")

    val needs = state.needsYou.map { n -> StatusNeed(n.taskId, listOf(n.project, n.text).filter { it.isNotBlank() }.joinToString(": ")) }.toMutableList()
    val asked = state.needsYou.mapNotNull { it.taskId }.toSet()
    val running = mutableListOf<StatusRow>()
    val finished = mutableListOf<StatusRow>()
    for (t in state.tasks) {
        val words = t.stateWords
        when {
            t.waitingToStart && t.failedWhy.isNotEmpty() -> needs += StatusNeed(t.id, "${title(t)}: didn't start")
            words == "Needs you" -> if (t.id !in asked) needs += StatusNeed(t.id, "${title(t)}: needs you")
            words == "Stopped unexpectedly" -> if (isToday(t.sinceUnix.takeIf { it > 0 } ?: t.activeUnix)) needs += StatusNeed(t.id, "${title(t)}: stopped unexpectedly")
            words == "Done" -> {
                val ended = t.sinceUnix.takeIf { it > 0 } ?: t.activeUnix
                if (isToday(ended)) finished += StatusRow(t.id, title(t), at("Your PC", if (t.whisper) "" else t.project), ended)
            }
            words in RUNNING || t.busy || t.waitingToStart -> {
                val list = t.checklist
                val done = list.count { it.done }
                val now = list.indexOfFirst { it.doing }.takeIf { it >= 0 } ?: list.indexOfFirst { !it.done }
                running += StatusRow(
                    t.id, title(t),
                    at(when {
                        t.waitingToStart -> "Not started yet"
                        t.liveElsewhere && t.elsewhereIn == "app" -> "Your PC, in the Claude app"
                        t.liveElsewhere && t.elsewhereIn == "terminal" -> "Your PC, in a terminal"
                        else -> "Your PC"
                    }, if (t.whisper) "" else t.project),
                    t.sign?.turnUnix?.takeIf { it > 0 } ?: t.sinceUnix,
                    percent = if (list.isEmpty()) null else done * 100 / list.size,
                    step = if (list.isEmpty()) "" else "step ${(done + 1).coerceAtMost(list.size)} of ${list.size}",
                    next = if (now < 0 || t.whisper) "" else list.drop(now + 1).firstOrNull { !it.done }?.text.orEmpty(),
                )
            }
        }
    }
    // Sessions on the PC that aren't Mímir tasks on this phone's list: a terminal, the Claude app.
    val listed = state.tasks.map { it.id }.toSet()
    state.pcSessions.orEmpty().filter { it.state == PcSessionState.Working && (it.task == null || it.task !in listed) }.forEach { s ->
        running += StatusRow(s.task, s.title, at("Your PC, ${s.where}", s.project), 0)
    }
    return StatusSnapshot(
        asOfMs = if (away) state.pc.seenAtMs else nowMs,
        away = away,
        running = running.sortedByDescending { it.timeUnix },
        needsYou = needs,
        finished = finished.sortedByDescending { it.timeUnix },
    )
}

/** The one card on screen: which chat it is in, after which message, and what it says. */
data class StatusAsk(val chat: String, val after: String, val snapshot: StatusSnapshot, val asked: Int)

/** Only the newest card is shown, kept in memory (it survives turning the phone, not closing the app). */
object StatusCards {
    var shown by mutableStateOf<StatusAsk?>(null)
        private set
    /** Counts asks, so a new one is scrolled to once. */
    var scrolled = 0
    /** The ask still waiting for the PC's fresh facts (0: none). */
    var fresh = 0
    private var asks = 0

    fun ask(chat: String, after: String, snapshot: StatusSnapshot) { shown = StatusAsk(chat, after, snapshot, ++asks); fresh = asks }
    /** Update: the same card in the same place, with newer facts. */
    fun update(snapshot: StatusSnapshot) { shown = shown?.copy(snapshot = snapshot) }
    fun dismiss() { shown = null }
    fun clear() { shown = null; scrolled = 0; fresh = 0; asks = 0 }
}
