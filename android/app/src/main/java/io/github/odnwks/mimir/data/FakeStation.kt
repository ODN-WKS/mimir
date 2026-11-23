package io.github.odnwks.mimir.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A stand-in for the link to the PC, filled with the approved boards' example data
 * (text marked [example] is made up on the boards too). It answers at once and keeps
 * everything in memory: no network, no files.
 */
class FakeStation(initial: StationState = ExampleData.awake()) : Station {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<StationState> = flow.asStateFlow()
    private var nextId = 1
    private fun newId(prefix: String) = "$prefix-new-${nextId++}"

    override fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        flow.update { s ->
            val filing = when {
                !s.pc.online -> Filing(FilingKind.Waiting, "")
                clean.startsWith("Idea", ignoreCase = true) -> Filing(FilingKind.Idea, "Mímir")
                else -> Filing(FilingKind.NewTask, "Mímir", taskId = newId("t"))
            }
            val started = filing.taskId?.let { id ->
                Task(id, clean.split(' ').take(4).joinToString(" "), "Mímir", s.models.selectedName, "just now", emptyList(),
                    listOf(TaskMessage(newId("r"), fromClaude = false, text = clean)), null, null, null, status = "Working", busy = true)
            }
            val running = if (filing.kind == FilingKind.NewTask) s.pc.tasksRunning + 1 else s.pc.tasksRunning
            s.copy(chat = s.chat + ChatEntry.Mine(newId("m"), clean, filing), pc = s.pc.copy(tasksRunning = running), tasks = s.tasks + listOfNotNull(started), chatAt = System.currentTimeMillis())
        }
    }

    override fun answerHere(text: String, working: String, answer: () -> List<String>) {
        val id = newId("m")
        flow.update { s -> s.copy(chat = s.chat + ChatEntry.Mine(id, text.trim(), null) + ChatEntry.Reply("r-$id", listOf(working), ""), chatAt = System.currentTimeMillis()) }
        answerInto("r-$id", working, answer)
    }

    private val retries = java.util.concurrent.ConcurrentHashMap<String, Pair<String, () -> List<String>>>()

    private fun answerInto(rid: String, working: String, answer: () -> List<String>) {
        Thread({
            val r = runCatching(answer)
            if (r.isFailure) retries[rid] = working to answer else retries.remove(rid)
            val words = r.getOrElse { listOf(io.github.odnwks.mimir.online.Net.sorry(it)) }
            flow.update { s -> s.copy(chat = s.chat.map { if (it.id == rid) ChatEntry.Reply(rid, words, "Play the answer", retry = r.isFailure) else it }) }
        }, "fake-answer").start()
    }

    override fun retryAnswer(replyId: String) {
        val (working, answer) = retries.remove(replyId) ?: return
        flow.update { s -> s.copy(chat = s.chat.map { if (it.id == replyId) ChatEntry.Reply(replyId, listOf(working), "") else it }) }
        answerInto(replyId, working, answer)
    }

    private val past = ArrayList<KeptChat>()

    private fun keepOpen(s: StationState): KeptChat? =
        if (s.chat.none { it is ChatEntry.Mine || it is ChatEntry.Reply }) null
        else KeptChat(newId("c"), null, s.chatAt.takeIf { it > 0 } ?: System.currentTimeMillis(), ChatLog.write(s.chat.filterNot { it is ChatEntry.IncognitoRunning }, emptyMap()))

    private fun showPast(s: StationState) = s.copy(pastChats = past.map { it.summary() })

    override fun newChat() = flow.update { s ->
        val kept = keepOpen(s) ?: return@update s
        past.add(0, kept)
        showPast(s.copy(chat = emptyList(), chatAt = 0))
    }

    override fun openPastChat(id: String) = flow.update { s ->
        val i = past.indexOfFirst { it.id == id }
        if (i < 0) return@update s
        val pick = past.removeAt(i)
        keepOpen(s)?.let { past.add(0, it) }
        showPast(s.copy(chat = ChatLog.read(pick.log).first, chatAt = pick.at))
    }

    override fun renamePastChat(id: String, name: String) = flow.update { s ->
        val i = past.indexOfFirst { it.id == id }
        if (i >= 0) past[i] = past[i].copy(name = name.trim().take(60).ifBlank { null })
        showPast(s)
    }

    override fun deletePastChat(id: String): KeptChat? {
        val i = past.indexOfFirst { it.id == id }
        if (i < 0) return null
        val gone = past.removeAt(i)
        flow.update { showPast(it) }
        return gone
    }

    override fun restorePastChat(chat: KeptChat) {
        if (past.any { it.id == chat.id }) return
        past.add(past.indexOfFirst { it.at < chat.at }.let { if (it < 0) past.size else it }, chat)
        flow.update { showPast(it) }
    }

    override fun retryMessage(messageId: String) {}
    override fun undoMessage(messageId: String) {
        flow.update { s ->
            val gone = s.chat.firstOrNull { it.id == messageId } as? ChatEntry.Mine ?: return@update s
            val running = if (gone.filing?.kind == FilingKind.NewTask) (s.pc.tasksRunning - 1).coerceAtLeast(0) else s.pc.tasksRunning
            s.copy(chat = s.chat - gone, pc = s.pc.copy(tasksRunning = running))
        }
    }

    override fun reply(taskId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        replied += clean
        flow.update { s ->
            s.copy(tasks = s.tasks.map { t ->
                if (t.id == taskId) t.copy(messages = t.messages + TaskMessage(newId("r"), fromClaude = false, text = clean)) else t
            })
        }
    }

    /** Edits made on a task: (task, message, new words). Checks read this. */
    val edited = mutableListOf<Triple<String, String, String>>()
    /** Set by a check: the reason an edit is refused (nothing is sent then). */
    var editRefusal: String? = null
    override fun editTaskMessage(taskId: String, messageId: String, text: String, done: (String?) -> Unit) {
        edited += Triple(taskId, messageId, text)
        val why = editRefusal
        if (why == null) reply(taskId, text)
        done(why)
    }

    /** Words sent as replies in a task (checks read this). */
    val replied = mutableListOf<String>()

    override fun answer(itemId: String, answer: Answer) {
        if (answer == Answer.Opened || answer == Answer.AskClaudeToFix) return
        flow.update { s ->
            s.copy(
                needsYou = s.needsYou.filterNot { it.id == itemId },
                tasks = s.tasks.map { t ->
                    t.copy(
                        permission = t.permission?.takeUnless { it.itemId == itemId },
                        question = t.question?.takeUnless { it.itemId == itemId },
                    )
                },
            )
        }
    }

    override fun answerChoice(itemId: String, index: Int) = answer(itemId, Answer.Allow)

    override fun moveMessage(messageId: String, project: String) = refile(messageId, project)

    override fun fileInbox(messageId: String, project: String) = refile(messageId, project)

    private fun refile(messageId: String, project: String) = flow.update { s ->
        s.copy(chat = s.chat.map { e ->
            if (e is ChatEntry.Mine && e.id == messageId) e.copy(filing = Filing(FilingKind.NewTask, project)) else e
        })
    }

    override fun setStayMode(mode: StayMode) = flow.update { it.copy(stayMode = mode) }

    override fun stopTask(taskId: String) {
        flow.update { s ->
            val t = s.task(taskId) ?: return@update s
            if (t.stopped) return@update s
            s.copy(
                tasks = s.tasks.map { if (it.id == taskId) it.copy(stopped = true) else it },
                pc = s.pc.copy(tasksRunning = (s.pc.tasksRunning - 1).coerceAtLeast(0)),
            )
        }
    }

    override fun endIncognito() {
        flow.update { s ->
            s.copy(incognito = null, chat = s.chat.filterNot { it is ChatEntry.IncognitoRunning })
        }
    }

    override fun newProject(name: String, done: (String?, String) -> Unit) {
        val n = name.trim()
        if (n.isEmpty() || n.startsWith(".") || n.contains("/") || n.contains("..")) {
            done(null, "Use a plain name: letters, digits, spaces and - _ . only.")
            return
        }
        if (!flow.value.paired) { done(null, "Pair with your PC to make projects there."); return }
        if (!flow.value.pc.online) { done(null, "Your PC is away: new projects can be made when it's back."); return }
        val exists = n in flow.value.pickable
        if (!exists) flow.update { it.copy(projectChoices = it.pickable + n) }
        done(n, if (exists) "$n is already on your PC: picked it." else "Made ~/Projects/$n on your PC")
    }

    override fun pauseTask(taskId: String) = retitle(taskId) { it }
    override fun continueTask(taskId: String) = retitle(taskId) { it }
    override fun renameTask(taskId: String, title: String) = retitle(taskId) { title.trim().ifEmpty { it } }
    override fun dismissNote(id: String) = flow.update { s -> s.copy(chat = s.chat.filterNot { it.id == id }) }

    override fun archiveTask(taskId: String) = flow.update { s ->
        val t = s.tasks.firstOrNull { it.id == taskId } ?: return@update s
        s.copy(tasks = s.tasks - t, archived = s.archived + t)
    }
    override fun unarchiveTask(taskId: String) = flow.update { s ->
        val t = s.archived.firstOrNull { it.id == taskId } ?: return@update s
        s.copy(tasks = s.tasks + t, archived = s.archived - t)
    }
    override fun moveTask(taskId: String, project: String) = flow.update { s ->
        s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(project = project) else it })
    }
    override fun setTaskPlan(taskId: String, plan: Boolean) = flow.update { s ->
        s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(plan = plan, mode = if (plan) "Plan" else "Ask") else it })
    }
    /** For checks: the PC's answer to turning on YOLO from this phone (null: it allows it). */
    var yoloRefusal: String? = null
    override fun turnOnYolo(taskId: String, done: (String?) -> Unit) {
        val no = yoloRefusal
        if (no == null) { setTaskYolo(taskId); flow.update { it.copy(yoloFromPhone = true) } }
        else flow.update { it.copy(yoloFromPhone = false) }
        done(no)
    }
    override fun setTaskYolo(taskId: String) = flow.update { s ->
        s.copy(tasks = s.tasks.map { if (it.id == taskId && !it.whisper) it.copy(mode = "NoAsk", plan = false) else it })
    }
    override fun setTaskModel(taskId: String, modelId: String) {}
    private fun retitle(taskId: String, f: (String) -> String) = flow.update { s ->
        s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(title = f(it.title)) else it })
    }

    override fun startTask(text: String, project: String, incognito: Boolean, plan: Boolean, model: String?): String {
        val clean = text.trim().ifEmpty { "New task" }
        val title = clean.split(' ').take(4).joinToString(" ").trimEnd('.', ',')
        if (incognito) {
            flow.update { s ->
                s.copy(
                    incognito = IncognitoTask(title, project, s.models.freeFallback, clean, "", "Play the reply"),
                    chat = s.chat.filterNot { it is ChatEntry.IncognitoRunning } + ChatEntry.IncognitoRunning(newId("i"), project),
                )
            }
            return INCOGNITO
        }
        val id = newId("t")
        flow.update { s ->
            s.copy(
                tasks = s.tasks + Task(id, title, project, (s.models.claude + s.models.local).firstOrNull { it.id == model }?.name ?: s.models.selectedName, "just now", emptyList(),
                    listOf(TaskMessage(newId("r"), fromClaude = false, text = clean)), null, null, null, status = "Working", busy = true),
                pc = s.pc.copy(tasksRunning = s.pc.tasksRunning + 1),
            )
        }
        return id
    }

    override fun continueOnClaude(taskId: String) {}
    override fun startFresh(taskId: String) {}
    override fun selectModel(modelId: String) = flow.update { it.copy(models = it.models.copy(selected = modelId)) }
    override fun setSwitchToFree(on: Boolean) = flow.update { it.copy(models = it.models.copy(switchToFree = on)) }
    override fun setWeeklyCheck(on: Boolean) = flow.update { it.copy(models = it.models.copy(weeklyCheck = on)) }
    override fun setEngines(chats: String?, project: String?) = flow.update {
        it.copy(models = it.models.copy(chatsEngine = chats ?: it.models.chatsEngine, projectEngine = project ?: it.models.projectEngine))
    }
    override fun setChatModel(id: String) = flow.update { it.copy(models = it.models.copy(chatsModel = id)) }
    override fun setPcName(name: String) = flow.update { s -> s.copy(pc = s.pc.copy(name = name.trim().ifEmpty { "Your PC" })) }
    override fun setRelays(on: Boolean) = flow.update { it.copy(relays = on) }
    /** Files always allowed, per task (the example's first task has one). */
    private val allowed = HashMap<String, List<String>>().apply { put(ExampleData.TASK_ALLOW, listOf("crates/station/src/remote.rs")) }
    override fun alwaysAllowed(taskId: String, done: (List<String>?) -> Unit) = done(synchronized(allowed) { allowed[taskId].orEmpty() })
    override fun stopAllowing(taskId: String, file: String) { synchronized(allowed) { allowed[taskId] = allowed[taskId].orEmpty() - file } }
    override fun setFingerprintLock(on: Boolean) = flow.update { it.copy(fingerprintLock = on) }
    override fun removePhone(phoneId: String) =
        flow.update { s -> s.copy(phones = s.phones.filterNot { it.id == phoneId && !it.thisPhone }) }

    override fun playPause(updateId: String) {
        flow.update { s ->
            val p = s.player
            val player = if (p.updateId == updateId) p.copy(playing = !p.playing)
            else p.copy(updateId = updateId, playing = true, positionSeconds = 0)
            s.copy(player = player, updates = s.updates.markHeard(updateId))
        }
    }

    override fun playNext() {
        flow.update { s ->
            val i = s.updates.indexOfFirst { it.id == s.player.updateId }
            val next = s.updates.getOrNull(i + 1) ?: return@update s.copy(player = s.player.copy(playing = false))
            s.copy(player = s.player.copy(updateId = next.id, playing = true, positionSeconds = 0), updates = s.updates.markHeard(next.id))
        }
    }

    /** Messages played by the PC's voice (checks read this). */
    val played = mutableListOf<String>()
    override fun playMessage(taskId: String, messageId: String) { played += messageId }

    override fun listen(wav: ByteArray, done: (String?, String?) -> Unit) = done("Run the checks again, please.", null)

    override fun pair(code: String) = flow.update { it.copy(pairing = PairProgress.ConfirmOnPc(it.pairWords)) }

    override fun keepRecording(taskId: String?, wav: ByteArray, seconds: Int) = flow.update { s ->
        if (taskId == null) s.copy(chat = s.chat + ChatEntry.Mine(newId("m"), recordingWords(seconds), Filing(FilingKind.Waiting, "")))
        else s.copy(tasks = s.tasks.map { t ->
            if (t.id == taskId) t.copy(messages = t.messages + TaskMessage(newId("r"), fromClaude = false, text = recordingWords(seconds), waiting = true)) else t
        })
    }

    /** Changes the example state directly (for checks: something arrives from the PC). */
    fun set(change: (StationState) -> StationState) = flow.update(change)

    /** How pairing ended on the PC (for checks). */
    fun pairEnded(p: PairProgress) = flow.update { it.copy(pairing = p) }

    override fun back10() = flow.update { it.copy(player = it.player.copy(positionSeconds = (it.player.positionSeconds - 10).coerceAtLeast(0))) }

    override fun setSpeed(speed: Float) = flow.update { it.copy(player = it.player.copy(speed = io.github.odnwks.mimir.audio.ReadSpeed.snap(speed))) }

    override fun cycleSpeed() {
        flow.update { s ->
            val i = SPEEDS.indexOf(s.player.speed)
            s.copy(player = s.player.copy(speed = SPEEDS[(i + 1) % SPEEDS.size]))
        }
    }

    private fun List<Update>.markHeard(id: String) = map { if (it.id == id) it.copy(heard = Heard.Heard) else it }

    companion object {
        /** The id [startTask] returns for an Incognito task (there is only ever one). */
        const val INCOGNITO = "incognito"
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f)
    }
}

/** The boards' example data. */
object ExampleData {
    const val TASK_ALLOW = "t-allow"
    const val TASK_CELL = "t-cell"
    const val TASK_TABLET = "t-tablet"
    const val TASK_ORDER = "t-order"

    private val change = Change(
        file = "remote.rs",
        what = "Removes a fixed 1.5 s wait before your Allow is sent to Mímir. The wait was only needed by an old relay that is gone.",
        lines = listOf(
            DiffLine(DiffKind.Context, "fn send_answer(&self, answer: Answer) {"),
            DiffLine(DiffKind.Removed, "sleep(Duration::from_millis(1500));"),
            DiffLine(DiffKind.Added, "// Send at once: the old relay is gone."),
            DiffLine(DiffKind.Context, "    self.link.send(answer);\n}"),
        ),
        checks = "Allow reaches Mímir · Don't allow reaches Mímir · Always allow stops asking",
        checksNote = "They run after you allow the edit. You'll hear if any fail.",
    )

    private val needsYou = listOf(
        NeedsYouItem("p-remote", NeedsKind.Permission, "Mímir", "2 min",
            "Edit remote.rs (2 lines) so Allow reaches Mímir faster. Mímir is paused until you answer.", TASK_ALLOW),
        NeedsYouItem("q-release", NeedsKind.Question, "Mímir", "2 min",
            "Release this as 0.16 once the checks pass, or keep it for the next batch?", TASK_ALLOW),
        NeedsYouItem("f-budgetapp", NeedsKind.CheckFix, "Recipe box", "1 h",
            "Open Recipe box once from the PC's app menu. Did it start without the error?", null,
            listOf("It works", "Still broken")),
        NeedsYouItem("d-keep", NeedsKind.Approve, "Mímir", "2 h",
            "The keep-it-working design is ready. Read it, then say OK or what to change.", null),
    )

    private val tasks = listOf(
        Task(
            id = TASK_ALLOW, title = "Make Allow respond faster", project = "Mímir", model = "Sonnet", age = "6 min",
            steps = listOf("Read how Allow reaches Mímir", "Timed it: 1.8 s, of which 1.5 s is a fixed wait", "Wrote a fix that removes the wait"),
            messages = listOf(TaskMessage("tm-1", fromClaude = true,
                text = "The fix cuts Allow from 1.8 s to about 0.2 s. To test it, I need to change a file the phone link also uses.",
                playLabel = "Play the reply about the fix")),
            question = Question("q-release", "Release this as 0.16 once the checks pass, or keep it for the next batch?", "Release as 0.16…", "0.16"),
            permission = Permission("p-remote", "Allow Mímir to edit remote.rs?", "remote.rs", 2),
            change = change, status = "Needs you",
        ),
        Task(TASK_CELL, "Fix calls on cell signal", "Budget-App", "Opus", "12 min", listOf("Read the call log from the failing phone", "… Trying the call again over cell signal"), emptyList(), null, null, null, status = "Working", busy = true),
        Task(TASK_TABLET, "Make the call screen bigger on tablets", "Budget-App", "Sonnet", "3 min", emptyList(), emptyList(), null, null, null, status = "Working", busy = true),
        Task(TASK_ORDER, "Messages arrive in order", "Budget-App", "Sonnet", "yesterday", emptyList(), emptyList(), null, null, null, stopped = true, status = "Stopped"),
    )

    private fun project(
        name: String, about: String, state: CheckState, pass: Int, total: Int, line: String, spoken: String,
        checks: List<Check> = emptyList(), since: String = "", tasks: List<ProjectTask> = emptyList(), running: Int = 0,
    ) = Project(name, about, state, pass, total, line, spoken, checks, since, tasks, running)

    private val projects = listOf(
        project("Mímir", "Mímir's tasks on the PC [example]", CheckState.Pass, 7, 7, "1 task running", "1 task running.",
            tasks = listOf(ProjectTask(TASK_ALLOW, "Make Allow respond faster", "Sonnet · running 6 min", Chip.Running)), running = 1),
        project("Recipe box", "[example]", CheckState.None, 0, 0, "Check the launch fix", "Check the launch fix."),
        project("Budget-App", "Calls and messages app", CheckState.Fail, 4, 5,
            "Failing: calls on cell signal · 2 tasks", "Failing: calls on cell signal. 2 tasks running.",
            checks = listOf(Check("Messages arrive in order", true), Check("Audio calls on Wi-Fi", true), Check("Video calls", true),
                Check("Sending pictures", true), Check("Calls on cell signal: fails", false)),
            since = "Failing since this morning [example] · last passed yesterday",
            tasks = listOf(
                ProjectTask(TASK_CELL, "Fix calls on cell signal", "Opus · running 12 min", Chip.Running),
                ProjectTask(TASK_TABLET, "Make the call screen bigger on tablets", "Sonnet · running 3 min [example]", Chip.Running),
                ProjectTask(null, "Show caller's photo on the kids' TV", "Idea · filed today", Chip.Idea),
                ProjectTask(TASK_ORDER, "Messages arrive in order", "Finished yesterday", Chip.Done),
            ),
            running = 2),
        project("Garden planner", "[example]", CheckState.None, 0, 0, "Version 0.67 · idle", "Version 0.67, idle."),
        project("Garden hub", "[example]", CheckState.None, 0, 0, "Build helpers online · idle", "Build helpers online, idle."),
        project("General", "[example]", CheckState.None, 0, 0, "Local model and Whisper mode tests done", "Local model and Whisper mode tests done."),
    )

    private val updates = listOf(
        Update("u-allow", "Mímir", "2 min ago", "2 minutes ago",
            "Allow now reaches Mímir in 0.2 s instead of 1.8 s. Waiting for your OK to change one file.", Heard.Heard, UpdateTarget.TaskOf(TASK_ALLOW), 21),
        Update("u-incog", "General", "6 min ago", "6 minutes ago",
            "Whisper mode works: your PC's model fixed both test bugs in 24 s with the internet cut off.", Heard.New, UpdateTarget.Incognito, 14),
        Update("u-models", "General", "18 min ago", "18 minutes ago",
            "Six local models tested. Qwen 3.6 Coding fixed both bugs in 30 s, the fastest.", Heard.Heard, UpdateTarget.Models, 12),
        Update("u-idea", "Budget-App", "41 min ago", "41 minutes ago",
            "New idea saved: show a big photo of who is calling on the kids’ TV.", Heard.Heard, UpdateTarget.ProjectOf("Budget-App"), 9),
        Update("u-budgetapp", "Recipe box", "1 h ago", "1 hour ago",
            "The launch error is fixed. Open Recipe box once from the app menu to confirm.", Heard.Heard, UpdateTarget.NeedsYou, 11),
    )

    private val models = Models(
        claude = listOf(
            Model("auto", "Auto", "Picks the best model for each message", free = false),
            Model("opus", "Opus 5.5", "For the hardest work · uses the most", free = false),
        ),
        local = listOf(
            Model("qwen-coding", "Qwen 3.6 Coding", "Fastest: fixed both test bugs in 30 s", free = true),
            Model("gpt-oss", "GPT-OSS 20B", "Fixed both in 32 s", free = true),
            Model("gpt-oss-abl", "GPT-OSS 20B abliterated", "Fixed both in 3 min", free = true, refusalsRemoved = true),
            Model("qwen-abl", "Qwen 3.6 abliterated", "Fixed both in 90 s", free = true, refusalsRemoved = true),
        ),
        selected = "auto",
        switchToFree = true,
        freeFallback = "Qwen 3.6 Coding",
    )

    /** The PC is online: the Chat board and everything reached from it. */
    fun awake() = StationState(
        pc = Pc("Gaming PC [example]", online = true, lastSeen = "now", route = "private connection", tasksRunning = 3),
        chat = listOf(
            ChatEntry.Mine("m-allow", "Make Allow respond faster on the phone app", Filing(FilingKind.NewTask, "Mímir", taskId = TASK_ALLOW)),
            ChatEntry.Mine("m-idea", "Idea: show a big photo of who's calling on the kids' TV", Filing(FilingKind.Idea, "Budget-App")),
            ChatEntry.IncognitoRunning("m-incog", "General"),
            ChatEntry.Mine("m-status", "What's the status?", null),
            ChatEntry.Reply("m-reply", listOf(
                "4 things need you. Mímir wants to edit a file and asks about a release. Recipe box needs you to check a fix. The keep-it-working design is ready for your OK.",
                "Budget-App has 1 failing check: calls on cell signal. Everything else is passing or idle.",
            ), "Play the status reply, just now"),
        ),
        needsYou = needsYou,
        tasks = tasks,
        projects = projects,
        usage = Usage("[example 62%]", 62, "Mon", 85),
        updates = updates,
        player = Player("u-allow", playing = true, positionSeconds = 8, speed = 1.25f),
        models = models,
        phones = listOf(
            Phone("s25", "Your phone", "This phone · used now", thisPhone = true),
            Phone("s8", "Galaxy S8", "Test phone · used 2 h ago", thisPhone = false),
        ),
        fingerprintLock = true,
        incognito = IncognitoTask("Read the tax letter", "General", "Qwen 3.6 Coding",
            "Read the tax letter in my Documents folder and tell me what it asks for.",
            "It asks you to confirm your address by 1 December. There is nothing to pay. [example]",
            "Play the reply about the tax letter"),
        pairWords = listOf("maple", "river", "seven", "lamp"),
        appVersion = "0.1 [example]",
    )

    /** The PC is asleep: the Trouble board (an answer already sent, one message waiting on the phone). */
    fun asleep() = awake().let { s ->
        s.copy(
            pc = s.pc.copy(online = false, lastSeen = "11:42 [example]"),
            chat = listOf(
                ChatEntry.Note("n-sent", "Answer sent: Allow this edit · Mímir carried on"),
                ChatEntry.Mine("m-sunday", "Idea: a short spoken summary every Sunday [example]", Filing(FilingKind.Idea, "Mímir")),
                ChatEntry.Mine("m-dark", "Add a dark mode switch to Garden planner's settings [example]", Filing(FilingKind.Waiting, "")),
            ),
            needsYou = s.needsYou.filterNot { it.kind == NeedsKind.Permission },
            tasks = s.tasks.map { it.copy(permission = null) },
        )
    }
}
