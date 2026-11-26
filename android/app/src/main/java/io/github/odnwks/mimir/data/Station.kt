package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.LongNotice
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the screens read from, and send to, Mímir on the PC.
 *
 * Today [FakeStation] fills it with the boards' example data. The real link (phone-core over JNI)
 * implements the same interface later, so the screens don't change when it arrives.
 */
interface Station {
    val state: StateFlow<StationState>

    /** A message from the general chat; the PC files it in the right project. */
    fun send(text: String)
    /**
     * An everyday message answered on this phone (the weather from Open-Meteo), not sent to the
     * PC: it shows as sent, with [working] in the answer's place until [answer] (run off the main
     * thread) gives the answer's paragraphs.
     */
    fun answerHere(text: String, working: String, answer: () -> List<String>)
    /** "Try again" on an answer from [answerHere] that couldn't be looked up: asks again in its place. */
    fun retryAnswer(replyId: String) {}
    /** New chat: the conversation so far goes to the chats list, and an empty one starts. */
    fun newChat()
    /** An earlier conversation from the chats list opens again (the open one goes to the list). */
    fun openPastChat(id: String)
    fun renamePastChat(id: String, name: String)
    /** Deletes an earlier conversation; returns it, for Undo. */
    fun deletePastChat(id: String): KeptChat?
    /** Undo: a deleted conversation comes back. */
    fun restorePastChat(chat: KeptChat)
    /** Undo a filing ("Undo", "Move it") or cancel a message still waiting on this phone ("Cancel"). */
    fun undoMessage(messageId: String)
    /** A message the PC couldn't take (its line says so): sent again. */
    fun retryMessage(messageId: String)
    /**
     * A reply typed into one task, with the pictures and files chosen for it (the PC wipes their hidden details).
     * False when it was not taken (nothing to send, or the pictures and files couldn't be kept): the box keeps what was in it.
     */
    /** [reply] with the heavy work off the main thread: [done] says whether it was taken (the box keeps its content until then). */
    fun replyAsync(taskId: String, text: String, pictures: List<io.github.odnwks.mimir.standalone.TurnPicture> = emptyList(), files: List<io.github.odnwks.mimir.standalone.TurnFile> = emptyList(), done: (Boolean) -> Unit) { done(reply(taskId, text, pictures, files)) }
    /** A reply the PC refused (or whose pictures can't be read back) stays with its words: send it again, or remove it. */
    fun retryReply(replyId: String) {}
    fun removeReply(replyId: String) {}
    fun reply(taskId: String, text: String, pictures: List<io.github.odnwks.mimir.standalone.TurnPicture> = emptyList(), files: List<io.github.odnwks.mimir.standalone.TurnFile> = emptyList()): Boolean
    /** This PC takes pictures and files on a task's message ("task_files"); an older one doesn't, and the box offers none. */
    fun canAttachToTasks(): Boolean = false
    /** This PC lets a phone set a task's model and effort with a message ("task_reply_options"). */
    fun canSetReplyOptions(): Boolean = false
    /** The effort for this task from now on (null: its usual one); the PC takes it with the next message. */
    fun setTaskEffort(taskId: String, effort: String?) {}
    /** [done] gets null when it went out, or the plain reason it did not (nothing was sent then). Edit one of the owner's own messages in a task: the task goes back to that message and carries on with [text]. */
    fun editTaskMessage(taskId: String, messageId: String, text: String, done: (String?) -> Unit = {}) { reply(taskId, text); done(null) }
    /** Takes back a new task that is still waiting on this phone (nothing is sent). */
    fun cancelWaitingTask(taskId: String) {}
    /** Sends a new task that the PC didn't start once more. */
    fun retryWaitingTask(taskId: String) {}
    /** Why the last [startTask] gave no task, in plain words. */
    fun startProblem(): String = ""
    /** An answer to a needs-you item: a permission, a question, a fix to check, a design. */
    fun answer(itemId: String, answer: Answer)
    /** One of a needs-you item's choices (a question's listed options, an ask's choices). */
    fun answerChoice(itemId: String, index: Int)
    /** "Move it": the task a chat message started goes to another project. */
    fun moveMessage(messageId: String, project: String)
    /** A message waiting in the Inbox goes to the project the owner picked. */
    fun fileInbox(messageId: String, project: String)
    /** How this phone stays in touch with the PC (Settings › Notifications). */
    fun setStayMode(mode: StayMode)
    fun stopTask(taskId: String)
    /** Asks the PC for its Claude Code sessions again (pull to refresh); an older PC has none. */
    fun refreshPcSessions() {}
    /** Opens one PC session as a task and tells [done] its id (null when the PC couldn't). */
    fun openPcSession(session: String, done: (String?) -> Unit) { done(null) }
    fun pauseTask(taskId: String)
    fun continueTask(taskId: String)
    fun renameTask(taskId: String, title: String)
    fun archiveTask(taskId: String)
    /**
     * Deletes the task on the PC (its conversation and files too). [done] gets null once the PC did it,
     * or the PC's reason it didn't (for example while the task works).
     */
    fun deleteTask(taskId: String, done: (String?) -> Unit = {}) { done("This PC can't delete tasks yet.") }
    /** Pull to refresh: asks the PC for its newest list of tasks and projects. */
    fun refresh() {}
    /** The owner tapped OK on a "That didn't work" note: it goes from the chat. */
    fun dismissNote(id: String) {}
    /** Undo of Archive: the task is back in the list. */
    fun unarchiveTask(taskId: String)
    /** The task, conversation and all, carries on in another project (refused while Claude works on it). */
    /** "Move to another project": [said] gets the PC's answer in plain words (why not, or where it went). */
    fun moveTask(taskId: String, project: String, said: (String) -> Unit = {})
    /** Ask me first (false) or Plan first (true): the two a phone may choose. */
    fun setTaskPlan(taskId: String, plan: Boolean)
    /** YOLO for this one task, after the warning and the phone's unlock (the PC may refuse). */
    fun setTaskYolo(taskId: String) {}
    /** The same, saying how it went: [done] gets null once YOLO is on, or the PC's reason it isn't. */
    fun turnOnYolo(taskId: String, done: (String?) -> Unit) { setTaskYolo(taskId); done(null) }
    fun setTaskModel(taskId: String, modelId: String)
    fun endIncognito()
    /** Starts a task in [project]; [model] is for this task only (null: the usual one). Returns its id, or "" if it didn't start. */
    fun startTask(
        text: String, project: String, incognito: Boolean, plan: Boolean = false, model: String? = null,
        pictures: List<io.github.odnwks.mimir.standalone.TurnPicture> = emptyList(), files: List<io.github.odnwks.mimir.standalone.TurnFile> = emptyList(), effort: String? = null,
    ): String

    /** [startTask] with the heavy work off the main thread: [done] gets the id ("" if it didn't start). */
    fun startTaskAsync(
        text: String, project: String, incognito: Boolean, plan: Boolean = false, model: String? = null,
        pictures: List<io.github.odnwks.mimir.standalone.TurnPicture> = emptyList(), files: List<io.github.odnwks.mimir.standalone.TurnFile> = emptyList(), effort: String? = null,
        done: (String) -> Unit,
    ) { done(startTask(text, project, incognito, plan, model, pictures, files, effort)) }

    /**
     * Makes a project folder on the PC (or picks it if it's already there). [done] gets the
     * project's name and what to tell the owner, or null and why not.
     */
    fun newProject(name: String, done: (name: String?, message: String) -> Unit)

    fun selectModel(modelId: String)
    /** A free-model task carries on in a new task on Claude ("Continue on Claude", "Use Claude instead"). */
    fun continueOnClaude(taskId: String)
    /** "Start fresh with a summary" on a long conversation: a new task from a short summary; the old one stays. */
    fun startFresh(taskId: String, done: (newTaskId: String?) -> Unit)
    fun setSwitchToFree(on: Boolean)
    /** The PC's weekly look for a better model for this phone ("Worth switching?"), on or off. */
    fun setWeeklyCheck(on: Boolean)
    /** Settings › Who answers' two lines: chats "free" or "paid", project work "free" or "claude" (null: unchanged). */
    fun setEngines(chats: String?, project: String?)

    /** Which Claude model answers chats (all chats on this PC); an older PC without the choice ignores it. */
    fun setChatModel(id: String) {}
    /** The name this PC shows on phones (empty: the PC's own name). */
    fun setPcName(name: String)
    /** Phones reach the PC through relays when they can't connect directly (from the PC's next start). */
    fun setRelays(on: Boolean)
    /** The files edits were always allowed to in a task; [done] gets them, or null if the PC can't say. */
    fun alwaysAllowed(taskId: String, done: (List<String>?) -> Unit)
    /** Edits to [file] ask first again in that task. */
    fun stopAllowing(taskId: String, file: String)
    fun setFingerprintLock(on: Boolean)
    fun removePhone(phoneId: String)

    /** Reads one of a task's messages aloud (made on the PC with its voice, kept on the phone). */
    fun playMessage(taskId: String, messageId: String)

    /** Play or pause one update. */
    fun playPause(updateId: String)
    fun playNext()
    fun back10()
    fun cycleSpeed()
    /** The read-aloud speed (50% to 150%, in 5% steps): the Updates player and the phone's voices. */
    fun setSpeed(speed: Float)

    /**
     * What the owner said (16 kHz mono WAV), turned into text on the PC: [done] gets the text, or
     * a reason it couldn't be heard.
     */
    fun listen(wav: ByteArray, done: (text: String?, error: String?) -> Unit)

    /** Pairs with the PC whose code was scanned or pasted; progress shows in [StationState.pairing]. */
    fun pair(code: String)

    /**
     * A recording spoken while the PC is away: kept on this phone and turned into words and sent
     * once the PC is back, to [taskId] or (null) to the chat, like a typed message.
     */
    fun keepRecording(taskId: String?, wav: ByteArray, seconds: Int)
}

/** What [Station.listen] answers when the recording could not even be sent (the PC is away): the caller keeps it instead. */
const val LISTEN_NOT_SENT = "listen-not-sent"

/** How a recording waiting on this phone shows until it's turned into words. */
fun recordingWords(seconds: Int) = "Recording, ${seconds.coerceAtLeast(1)} s"

/**
 * When the PC was last reached, as a clock time ("11:42"), "yesterday 23:10" or a date; "" if
 * never. It's the "as of" time every screen shows while the PC is away.
 */
fun seenWords(at: Long, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
    if (at <= 0L) return ""
    val cal = { t: Long -> java.util.Calendar.getInstance(zone).apply { timeInMillis = t } }
    val a = cal(at)
    val n = cal(now)
    val clock = String.format(java.util.Locale.ROOT, "%02d:%02d", a.get(java.util.Calendar.HOUR_OF_DAY), a.get(java.util.Calendar.MINUTE))
    val days = n.get(java.util.Calendar.DAY_OF_YEAR) - a.get(java.util.Calendar.DAY_OF_YEAR) +
        365 * (n.get(java.util.Calendar.YEAR) - a.get(java.util.Calendar.YEAR))
    return when (days) {
        0 -> clock
        1 -> "yesterday $clock"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.ENGLISH).apply { timeZone = zone }.format(java.util.Date(at))
    }
}

/** Where pairing stands. */
sealed interface PairProgress {
    data object Idle : PairProgress
    data object Working : PairProgress
    /** The code was right: the PC shows the same words and asks the owner. */
    data class ConfirmOnPc(val words: List<String>) : PairProgress
    data object Paired : PairProgress
    data class Failed(val why: String) : PairProgress
}

enum class Answer { Allow, DontAllow, AlwaysAllowFile, Release, KeepForLater, ItWorks, StillBroken, AskClaudeToFix, Approve, Opened }

/** An earlier conversation of the paired chat, kept on this phone: [log] is its [ChatLog] text. */
data class KeptChat(val id: String, val name: String?, val at: Long, val log: String) {
    fun summary(): PastChat {
        val entries = ChatLog.read(log).first
        val topic = name?.takeIf { it.isNotBlank() }
            ?: entries.firstNotNullOfOrNull { (it as? ChatEntry.Mine)?.text?.let(io.github.odnwks.mimir.standalone.LocalChat::topicOf) }
        val words = entries.joinToString(" ") { e -> when (e) { is ChatEntry.Mine -> e.text; is ChatEntry.Reply -> e.paragraphs.joinToString(" "); else -> "" } }
        return PastChat(id, topic ?: "Chat", at, entries.count { it is ChatEntry.Mine || it is ChatEntry.Reply }, words.take(4000))
    }

    companion object {
        /** How many earlier conversations are kept (the oldest go first). */
        const val MAX = 100

        fun write(list: List<KeptChat>): String = org.json.JSONArray(list.map {
            org.json.JSONObject().put("id", it.id).put("name", it.name ?: "").put("at", it.at).put("log", it.log)
        }).toString()

        fun read(json: String): List<KeptChat> = runCatching {
            val a = org.json.JSONArray(json)
            (0 until a.length()).map { i -> a.getJSONObject(i).let { KeptChat(it.getString("id"), it.optString("name").ifEmpty { null }, it.optLong("at"), it.getString("log")) } }
        }.getOrDefault(emptyList())
    }
}

/** How an earlier conversation shows in the chats list. */
data class PastChat(val id: String, val title: String, val at: Long, val messages: Int, val words: String = "")

/** Where a session on the PC is at. */
enum class PcSessionState { Working, Idle, Ended }

/** One Claude Code session on the PC, wherever it was started: [here] a Mímir task ([task]), else [app] (the Claude app) or a terminal. */
data class PcSession(
    val id: String, val title: String, val project: String, val here: Boolean, val app: Boolean, val task: String?,
    val state: PcSessionState, val lastUnix: Long,
) {
    /** "a Mímir task", "in the Claude app" or "in a terminal". */
    val where: String get() = if (here) "a Mímir task" else if (app) "in the Claude app" else "in a terminal"
    val line: String get() = listOf(project, where).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
}

/** The short time in a session's row: "now", "2m", "11h", "1d". */
fun pcAgo(lastUnix: Long, nowUnix: Long): String {
    val s = (nowUnix - lastUnix).coerceAtLeast(0)
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}

data class StationState(
    val pc: Pc,
    val chat: List<ChatEntry>,
    val needsYou: List<NeedsYouItem>,
    val tasks: List<Task>,
    val projects: List<Project>,
    val usage: Usage,
    val updates: List<Update>,
    val player: Player,
    val models: Models,
    val phones: List<Phone>,
    val fingerprintLock: Boolean,
    val incognito: IncognitoTask?,
    val pairWords: List<String>,
    val appVersion: String,
    val pairing: PairProgress = PairProgress.Idle,
    val stayMode: StayMode = StayMode.Every5,
    /** False while this phone itself has no network (then it's not the PC that's away). */
    val phoneOnline: Boolean = true,
    /** Paired with a PC (its projects can be chosen; new ones made there). */
    val paired: Boolean = true,
    /** A paid Claude choice is on (the PC's truth; null: an older PC that doesn't say). Claude's use shows only then. */
    val paidOn: Boolean? = null,
    /** The PC's processor, memory and graphics load (null: an older PC that doesn't send it). */
    val pcStats: PcStats? = null,
    /** The phone has a screen lock (PIN, pattern or fingerprint). Without one, pairing warns once. */
    val screenLock: Boolean = true,
    /** The PC answers chats itself (Stage 1): the one chat (the phone's own chat screen) is used, paired too. */
    val pcTalks: Boolean = false,
    /** The PC tells this phone when a task finishes (feature "finished_events"): alerts come in seconds while a task works. */
    val pcFinished: Boolean = false,
    /** This phone belongs to the PC's owner. Anyone else sees no "Needs you", no Projects and no "What's running?": the PC keeps those to its owner. */
    val owner: Boolean = true,
    /** The PC's project names for the picker (kept from earlier while the PC is away). */
    val projectChoices: List<String> = emptyList(),
    /** The project used last on the PC: where a new task starts unless another is picked. */
    val lastProject: String = "",
    /** Tasks just started on this phone: the name their screen opened on → the PC's id ("" if it failed). */
    val startedTasks: Map<String, String> = emptyMap(),
    /** Answers given while the PC was away, waiting on this phone to be sent. */
    val answersWaiting: Int = 0,
    /** The Mímir version on the PC ("" until it's said); the phone app ships with each one. */
    val pcVersion: String = "",
    /** The PC's Claude Code sessions, newest first; null on a PC that doesn't list them (or for anyone but its owner). */
    val pcSessions: List<PcSession>? = null,
    /** Phones reach the PC through relays when they can't connect directly (the PC's setting). */
    val relays: Boolean = true,
    /** The PC's "Allow YOLO from my phone": null until known (said by the PC, or by its answer to a try). */
    val yoloFromPhone: Boolean? = null,
    /** Archived tasks: out of every list but All tasks › Archived, where each can come back. */
    val archived: List<Task> = emptyList(),
    /** Labs on the PC: what Claude is doing there right now, in one line (null while off or away). */
    val pcLine: String? = null,
    /** Earlier conversations of this chat, newest first (New chat puts the open one here). */
    val pastChats: List<PastChat> = emptyList(),
    /** When the open conversation last changed (ms since 1970; 0 while it's empty). */
    val chatAt: Long = 0,
) {
    /** The PC has a newer Mímir than this app: a newer app came with it. */
    val appUpdate: Boolean get() = paired && newer(pcVersion, appVersion)
    /** The weekly "Worth switching?" card, if one came. */
    val worthSwitching: Update? get() = updates.firstOrNull { it.text.startsWith("Worth switching?") }
    /** Paired, but the PC can't be reached right now (what's shown is from earlier). */
    val pcAway: Boolean get() = paired && !pc.online
    /**
     * The one connection line, the same words on every screen: whether the PC is reached, and
     * while it isn't, whose side is away (this phone or the PC) and how old the facts are.
     */
    val connectionLine: String get() = when {
        !paired -> "Not paired yet"
        pc.online -> "PC online · " + if (pc.tasksRunning == 0) "nothing working" else "${pc.tasksRunning} working"
        // One side, never both, in a few words that fit one line (QA I1).
        !phoneOnline -> "This phone is offline"
        pc.lastSeen == "not connected" -> "Reaching your PC…"
        else -> "Your PC is out of reach"
    }
    /** What still works while the PC can't be reached (null while it can). */
    val whatStillWorks: String? get() = when {
        !pcAway -> null
        !phoneOnline -> asOf() + "Messages wait and send when this phone is back online."
        else -> asOf() + "Messages wait and send when your PC is back."
    }
    /** How old what's shown is, on the strip under the header (not in the one-line header, where it wrapped). */
    private fun asOf() = if (pc.lastSeen.isBlank() || pc.lastSeen == "not connected" || pc.lastSeen == "now") "" else "As of ${pc.lastSeen}. "
    /** How a message box says where its words wait while the PC can't be reached (null while it can). */
    val waitsUntil: String? get() = when {
        !pcAway -> null
        !phoneOnline -> "sends when this phone is back online"
        else -> "sends when your PC is back"
    }
    /** Where a new task starts: the last project, else the first there is. */
    val newTaskProject: String get() = lastProject.ifEmpty { pickable.firstOrNull().orEmpty() }
    /** Names the project picker offers. */
    val pickable: List<String> get() = projectChoices.ifEmpty { projects.map { it.name } }
    /** Claude waits on permissions and questions; other items can wait. */
    val pausedOn: Int get() = needsYou.count { it.kind == NeedsKind.Permission || it.kind == NeedsKind.Question }
    fun task(id: String): Task? = tasks.firstOrNull { it.id == id } ?: archived.firstOrNull { it.id == id }
        ?: startedTasks[id]?.takeIf { it.isNotEmpty() }?.let { real -> tasks.firstOrNull { it.id == real } }
    fun project(name: String): Project? = projects.firstOrNull { it.name == name }
    /** This state with the tasks being deleted taken out of every list they show in (their Undo is still on screen). */
    fun without(hidden: Collection<String>): StationState = if (hidden.isEmpty()) this else copy(
        tasks = tasks.filter { it.id !in hidden },
        archived = archived.filter { it.id !in hidden },
        needsYou = needsYou.filter { it.taskId == null || it.taskId !in hidden },
        projects = projects.map { p -> if (p.tasks.any { it.taskId in hidden }) p.copy(tasks = p.tasks.filter { it.taskId !in hidden }) else p },
    )
}

data class Pc(
    val name: String,
    val online: Boolean,
    val lastSeen: String,
    val route: String,
    val tasksRunning: Int,
)

sealed interface ChatEntry {
    val id: String

    data class Mine(override val id: String, val text: String, val filing: Filing?) : ChatEntry
    /** [retry]: it couldn't be looked up, and "Try again" asks again (while the app runs). */
    data class Reply(override val id: String, val paragraphs: List<String>, val playLabel: String, val retry: Boolean = false) : ChatEntry
    /** The note that an Incognito task is running; its messages aren't kept in the chat. */
    data class IncognitoRunning(override val id: String, val project: String) : ChatEntry
    /** A small centred note (e.g. that an answer was sent), or a [problem]: red-outlined, with why. */
    data class Note(override val id: String, val text: String, val problem: Boolean = false, val action: NoteAction? = null) : ChatEntry
}

/** The one button on a problem card: what it says, and what it does (the card goes after it). */
class NoteAction(val label: String, val run: () -> Unit)

/** What happened to a message from the general chat. */
data class Filing(
    val kind: FilingKind,
    val where: String,
    val choices: List<String> = emptyList(),
    /** The task the message started or joined: its line opens it and shows its state. */
    val taskId: String? = null,
) {
    val text: String get() = when (kind) {
        FilingKind.NewTask -> "New task in $where"
        FilingKind.SameTask -> "Added to a task in $where"
        FilingKind.Idea -> "Filed in $where, Ideas"
        FilingKind.Waiting -> "Waiting · sends when your PC is back"
        FilingKind.Inbox -> "Which project?"
        FilingKind.Failed -> "Your PC couldn't take this. Try again, or delete it."
    }
    val action: String get() = when (kind) {
        FilingKind.NewTask, FilingKind.SameTask -> "Move it"
        FilingKind.Idea -> "Undo"
        FilingKind.Waiting -> "Cancel"
        FilingKind.Inbox -> ""
        FilingKind.Failed -> "Delete"
    }
}

enum class FilingKind { NewTask, SameTask, Idea, Waiting, Inbox, Failed }

/** How the phone stays in touch with the PC when the app is closed. */
enum class StayMode(val title: String, val about: String) {
    BatterySaver("Battery saver", "Live updates only while the app is open. Other updates wait until you open it."),
    Every5("Check every 5 minutes", "The phone checks with your PC about every 5 minutes, a bit less often while it sleeps."),
}

enum class NeedsKind(val label: String) {
    Permission("Allow?"), Question("A question for you"), CheckFix("Check this"), Approve("Your OK"),
    Choose("A question for you"), Release("Your OK"), CheckFailing("Check this"),
}

data class NeedsYouItem(
    val id: String,
    val kind: NeedsKind,
    val project: String,
    val age: String,
    val text: String,
    val taskId: String?,
    val choices: List<String> = emptyList(),
    /** Serious items make a sound. */
    val serious: Boolean = false,
    /** PCS-10: action leaves this PC and cannot be undone. Show a warning. */
    val leavesPC: Boolean = false,
)

data class Task(
    val id: String,
    val title: String,
    val project: String,
    val model: String,
    val age: String,
    val steps: List<String>,
    val messages: List<TaskMessage>,
    /** A question from Claude waiting for an answer (its needs-you item id). */
    val question: Question?,
    /** A permission request, pinned above the reply box (its needs-you item id). */
    val permission: Permission?,
    val change: Change?,
    val stopped: Boolean = false,
    /** It runs on a free model on the PC (with "Use Claude instead"). */
    val freeModel: Boolean = false,
    /** Its free model ran out of room ("Continue on Claude"). */
    val outOfRoom: Boolean = false,
    val refusalsRemoved: Boolean = false,
    /** "Working", "Paused", "Done", "Stopped" or "Needs you". */
    val status: String = "",
    val busy: Boolean = false,
    /** Something of the task runs on the PC right now (a Claude Code process is alive). */
    val running: Boolean = true,
    /** When it was started (for the tasks list, newest first). */
    val createdUnix: Long = 0,
    /** When its conversation last changed, wherever it went on (0: not known). */
    val activeUnix: Long = 0,
    /**
     * Its conversation is going on in a terminal or the Claude app right now: replies go there.
     * A reply from here would start a second Claude on it, so there's no reply box.
     */
    val liveElsewhere: Boolean = false,
    /** The conversation has run long: a calm line with "Start fresh with a summary" (null: not long). */
    val long: LongNotice? = null,
    /** The live sign under the task: sending, got it, what Claude is doing and for how long (null: nothing to say). */
    val sign: LiveSign? = null,
    /** Plan first (it changes nothing yet); false: it asks first before changes. */
    val plan: Boolean = false,
    /** The PC's permission mode: "Ask", "AcceptEdits", "Plan", "Auto" or "NoAsk". */
    val mode: String = "Ask",
    /** A Whisper task (YOLO is never offered there). */
    val whisper: Boolean = false,
    /** Started on this phone and not yet on the PC: kept here, starts by itself (Cancel is offered while the PC is away). */
    val waitingToStart: Boolean = false,
    /** A waiting task that isn't out with the PC: Cancel is offered (never while it is on its way). */
    val canCancel: Boolean = false,
    /** Why the PC didn't start it ("" if nothing went wrong): the words stay, with Try again and Remove. */
    val failedWhy: String = "",
    /** Stop was tapped while the PC is away: it goes out when the PC is back (the task still shows what it was doing). */
    val stopPending: Boolean = false,
    /** Where a session going on elsewhere runs, when the PC says: "app" (the Claude app) or "terminal"; "" when it doesn't. */
    val elsewhereIn: String = "",
    /** The effort chosen on this phone for the task's next messages (the PC's word, e.g. "high"); "" when its usual one. */
    val effort: String = "",
) {
    /** A mode that does things without asking, in words for its chip; null when it asks first. */
    val riskyMode: String? get() = when (mode) {
        "AcceptEdits" -> "Auto-edit"
        "Auto" -> "Auto"
        "NoAsk" -> "Go ahead"
        else -> null
    }
    /** YOLO: nothing asks first (red chip and a red edge round the chat). */
    val yolo: Boolean get() = mode == "NoAsk"

    /** The task's state in one or two words, the same everywhere it shows. */
    val stateWords: String get() = when {
        permission != null || question != null || status == "Needs you" -> "Needs you"
        status == "Stopped unexpectedly" || status == "Failed" -> "Stopped unexpectedly"
        stopped || status == "Stopped" || status == "Interrupted" -> "Stopped"
        status == "Paused" -> "Paused"
        busy || status == "Working" -> if (messages.none { it.fromClaude } && steps.isEmpty()) "Starting" else "Working"
        status.isNotEmpty() && status != "Done" -> status
        else -> "Done"
    }
    /**
     * Stop shows only while something of the task runs on the PC: it works (also on something it
     * started in the background), or a question or permission prompt is held by a live session.
     * A question nobody is waiting on any more has nothing to stop.
     */
    val showsStop: Boolean get() = when (stateWords) {
        "Working", "Starting" -> true
        "Needs you" -> (permission != null || question != null || status == "Needs you") && running || permission != null
        else -> false
    }
    /** The line under a task's title: what is happening now, in a sentence. */
    val stateLine: String get() = when (stateWords) {
        "Needs you" -> "Needs you"
        "Stopped" -> "Stopped"
        "Stopped unexpectedly" -> "Stopped unexpectedly"
        "Paused" -> "Paused"
        "Starting" -> "Starting…"
        "Working" -> "Working on it" + (steps.lastOrNull()?.let { ": " + it.trimStart('✓', '✕', '…', ' ') } ?: "")
        else -> stateWords
    }
}

data class TaskMessage(
    val id: String,
    val fromClaude: Boolean,
    val text: String,
    val playLabel: String = "",
    /** Typed while the PC was away: kept on this phone and sent when it's back. */
    val waiting: Boolean = false,
    /** A short line from the PC (not said by you or Claude): drawn small and dim. */
    val note: Boolean = false,
    /** The PC refused this waiting reply (or its pictures can't be read back): why; it stays to try again or remove. */
    val failedWhy: String = "",
    /** When it was made (unix ms); null for entries from before the PC kept times (no time shown, never a wrong one). */
    val atMs: Long? = null,
    /** The owner's message: Sent (still on this phone, or the PC has it), Delivered or Read; null when not known. */
    val receipt: MessageReceipt? = null,
    /** A reply: the model that answered it ("claude-opus-5-5"); the owner's message: the model it was sent to. */
    val model: String? = null,
    /** The effort with [model] ("high"); null with a model is the PC's default effort. */
    val effort: String? = null,
    /** A reply whose model or effort differs from the reply before: why in a few words ("you picked it")... */
    val switched: String? = null,
    /** ...and in a full sentence, for a tap. */
    val switchedWhy: String? = null,
)

/** What became of the owner's message: Sent (an empty circle), Delivered (a circle with a check), Read (a filled circle). */
enum class MessageReceipt { Sent, Delivered, Read }

data class Question(
    val itemId: String,
    val text: String,
    val yes: String,
    val releaseVersion: String,
    /** The options Claude listed, each a button. */
    val choices: List<String> = emptyList(),
)

/**
 * A permission request. An edit has a change to show; a command shows the command itself;
 * anything else just its title.
 */
data class Permission(
    val itemId: String,
    val title: String,
    val file: String,
    val changeLines: Int,
    val kind: PermissionKind = PermissionKind.Edit,
    val command: String = "",
) {
    val allowWords: String get() = when (kind) {
        PermissionKind.Edit -> "Allow this edit"
        PermissionKind.Command -> "Allow this command"
        PermissionKind.Other -> "Allow"
    }
}

enum class PermissionKind { Edit, Command, Other }

/**
 * Whether version [a] ("0.19", "0.19.1") is newer than [b]; a version with words after it
 * ("0.18 [example]", "0.18-dev") counts by its numbers.
 */
fun newer(a: String, b: String): Boolean {
    fun parts(v: String) = v.trim().takeWhile { it.isDigit() || it == '.' }.split('.').mapNotNull { it.toIntOrNull() }
    val x = parts(a)
    val y = parts(b)
    if (x.isEmpty() || y.isEmpty()) return false
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/** "1 step", "2 steps". */
fun count(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

data class Change(
    val file: String,
    val what: String,
    val lines: List<DiffLine>,
    val checks: String,
    val checksNote: String,
)

data class DiffLine(val kind: DiffKind, val text: String)
enum class DiffKind { Context, Removed, Added }

enum class CheckState { Pass, Fail, None }

data class Project(
    val name: String,
    val about: String,
    val state: CheckState,
    val passing: Int,
    val total: Int,
    val line: String,
    val spokenLine: String,
    val checks: List<Check>,
    val failingSince: String,
    val tasks: List<ProjectTask>,
    val tasksRunning: Int,
    /** When it was last worked on, in words ("2 h ago"; "" when not known). */
    val lastActivity: String = "",
    /** Things in it waiting for the owner. */
    val needsYou: Int = 0,
) {
    /** How its checks stand; with no checks, what's going on in it instead (Stage 1: no "No checks yet"). */
    val words: String get() = when (state) {
        CheckState.Pass -> "All $total passing"
        CheckState.Fail -> "${total - passing} of $total failing"
        CheckState.None -> when {
            needsYou > 0 -> "Needs you"
            tasksRunning > 0 -> "Working"
            lastActivity.isNotEmpty() -> "Active $lastActivity"
            else -> ""
        }
    }
    val fraction: Float get() = if (total == 0) 0f else passing.toFloat() / total
}

data class Check(val name: String, val ok: Boolean)

enum class Chip(val label: String) {
    Running("Working"), Idea("Idea"), Done("Done"), Stopped("Stopped"), StoppedUnexpectedly("Stopped unexpectedly"), Waiting("Waiting"),
    NeedsYou("Needs you"), Paused("Paused"), Starting("Starting"), Live("Live"),
    Away("Was working"), Archived("Archived");

    companion object {
        /** A task's chip from the PC's status words: a stopped or denied task isn't "Done". */
        fun of(busy: Boolean, status: String): Chip = when {
            busy -> Running
            status == "Stopped unexpectedly" || status == "Failed" -> StoppedUnexpectedly
            status == "Stopped" || status == "Interrupted" -> Stopped
            else -> Done
        }

        /**
         * A task's chip from [Task.stateWords], the one source every screen shows. Only "Done" is
         * green; a word not known here gets the neutral chip, never Done.
         */
        fun ofWords(words: String): Chip = when (words) {
            "Done" -> Done
            "Needs you", "Waiting for you" -> NeedsYou
            "Paused" -> Paused
            "Starting" -> Starting
            "Working", "Answering…" -> Running
            "Stopped" -> Stopped
            "Stopped unexpectedly" -> StoppedUnexpectedly
            "Live" -> Live
            "Was working (PC away)" -> Away
            "Archived" -> Archived
            else -> Waiting
        }

        /** The words [ofWords] knows. */
        fun knows(words: String): Boolean = words in setOf(
            "Done", "Needs you", "Waiting for you", "Paused", "Starting", "Working", "Answering…",
            "Stopped", "Stopped unexpectedly", "Live", "Was working (PC away)", "Archived",
        )
    }
}

data class ProjectTask(val taskId: String?, val name: String, val meta: String, val chip: Chip)

/** The PC's load as Your PC shows it: percent for the processor and graphics, bytes for memory. */
data class PcStats(val cpuPercent: Int, val memUsedBytes: Long, val memTotalBytes: Long, val gpuPercent: Int?, val threads: Int? = null) {
    /** Threads' worth of work going on now (the processor load times the threads), when the PC says how many it has. */
    val threadsBusy: Int? get() = threads?.let { n -> Math.round(cpuPercent * n / 100.0).toInt().coerceIn(0, n) }
}

data class Usage(val percentText: String, val percent: Int, val resets: String, val switchAt: Int)

enum class Heard { New, Heard }

data class Update(
    val id: String,
    val project: String,
    val whenShort: String,
    val whenSpoken: String,
    val text: String,
    val heard: Heard,
    val opens: UpdateTarget,
    val lengthSeconds: Int,
)

/** Where tapping an update's text goes. */
sealed interface UpdateTarget {
    data class TaskOf(val taskId: String) : UpdateTarget
    data object Incognito : UpdateTarget
    data object Models : UpdateTarget
    data class ProjectOf(val name: String) : UpdateTarget
    data object NeedsYou : UpdateTarget
}

/** [real]: a real player reports the position (the panel doesn't move on by itself). */
data class Player(val updateId: String?, val playing: Boolean, val positionSeconds: Int, val speed: Float, val real: Boolean = false)

/** Which updates were heard on this phone (kept on the phone only). */
interface HeardStore {
    fun heard(): Set<String>
    fun mark(id: String)

    class InMemory : HeardStore {
        private val s = HashSet<String>()
        override fun heard(): Set<String> = synchronized(s) { s.toSet() }
        override fun mark(id: String) { synchronized(s) { s += id } }
    }
}

data class Model(val id: String, val name: String, val note: String, val free: Boolean, val refusalsRemoved: Boolean = false)

data class Models(
    val claude: List<Model>,
    val local: List<Model>,
    val selected: String,
    val switchToFree: Boolean,
    val freeFallback: String,
    /** The PC's weekly look for a better model for this phone. */
    val weeklyCheck: Boolean = true,
    /** What powers tasks, as set on the PC (null: the PC doesn't say). */
    val power: Power? = null,
    /** What answers chats and lookups, as set on the PC: "free" or "paid" (null: the PC has no "engines"). */
    val chatsEngine: String? = null,
    /** Which Claude model chats use on the PC ("auto" or an alias; null: an older PC) and what it offers, each with a one-line meaning. */
    val chatsModel: String? = null,
    val chatModels: List<io.github.odnwks.mimir.link.LChoice> = emptyList(),
    /** What does project work, as set on the PC: "free" or "claude" (null: the PC has no "engines"). */
    val projectEngine: String? = null,
    /** Whether any model on the PC can look at this person's pictures (null: not known yet, or an older PC). Kept here so a change redraws the attach menu. */
    val seesPictures: Boolean? = null,
) {
    val selectedName: String get() = (claude + local).firstOrNull { it.id == selected }?.name ?: selected
}

/** What powers tasks: the PC's setting (Settings › General there) is the one that counts. */
enum class Power(val wire: String, val words: String) {
    Backup("backup", "Online, with free models as backup"),
    ClaudeOnly("claude", "Online only"),
    FreeOnly("free", "Your PC's models only"),
    ;

    companion object {
        fun of(wire: String?): Power? = entries.firstOrNull { it.wire == wire }
    }
}

data class Phone(val id: String, val name: String, val note: String, val thisPhone: Boolean)

data class IncognitoTask(
    val title: String,
    val project: String,
    val model: String,
    val prompt: String,
    val reply: String,
    val playLabel: String,
    /** The PC's task id (empty for the stand-in). */
    val id: String = "",
    /** A permission it waits for, in plain words. */
    val asking: String? = null,
)
