package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.ItemAnswer
import io.github.odnwks.mimir.link.LItem
import io.github.odnwks.mimir.link.LStatus
import io.github.odnwks.mimir.link.LinkTask

/**
 * What the PC sends, turned into what the screens show. Pure functions, so the unit tests check
 * them without a link.
 */
object Mapping {
    /**
     * Why pairing ended, in the app's words (QA2 #3): a PC that stopped waiting is not a "words don't match".
     * The PC's own "No" keeps its words.
     */
    fun pairWhy(raw: String): String = when {
        "in time" in raw || "stopped waiting" in raw || "closed the pairing" in raw -> "Your PC stopped waiting. Start pairing again on your PC."
        else -> raw
    }

    /**
     * Claude is really working on it: the PC's runner is busy, or the PC says Working (a session
     * started elsewhere that works on, or work going on in the background). The one rule for
     * "Working now", the counts and Stop.
     */
    /** A project page's task row with the task's own state words (the same as its task screen). */
    fun withTaskWords(pt: ProjectTask, words: String): ProjectTask =
        if (Chip.knows(words)) pt.copy(meta = words, chip = Chip.ofWords(words)) else pt.copy(chip = Chip.ofWords(words))

    fun working(t: io.github.odnwks.mimir.link.LinkTask): Boolean = t.busy || t.status == "Working"

    /**
     * A task's one state word: Needs you, Working, Live (going on elsewhere right now), Archived,
     * or the PC's own word (Done, Stopped…). A finished answer is Done at once, not Live for minutes,
     * and an archived task is never Live.
     */
    fun taskWords(t: io.github.odnwks.mimir.link.LinkTask, asks: Boolean, connected: Boolean): String = when {
        asks -> "Needs you"
        working(t) && !connected -> "Was working (PC away)"
        working(t) -> "Working"
        t.status == "NeedsYou" -> "Needs you"
        t.archived -> "Archived"
        connected && t.liveElsewhere -> "Live"
        // N2: a session followed from elsewhere while the PC is away: its last known state is not "Done".
        !connected && t.liveElsewhere -> "Was working (PC away)"
        // A stop nobody asked for reads differently from the owner's own Stop.
        t.status == "Stopped" && t.failed -> "Stopped unexpectedly"
        else -> t.status
    }

    /**
     * The PC's name as the owner sees it: the name set in Mímir, or "Your PC" for a computer's own
     * default name (some systems use a fixed generic hostname, e.g. "localhost").
     */
    /** The phone always says "Your PC", never the PC's system name. */
    @Suppress("UNUSED_PARAMETER")
    fun pcName(raw: String): String = "Your PC"

    /** "now", "6 min", "2 h", "3 d". */
    fun age(secondsAgo: Long): String = when {
        secondsAgo < 60 -> "now"
        secondsAgo < 3600 -> "${secondsAgo / 60} min"
        secondsAgo < 86_400 -> "${secondsAgo / 3600} h"
        else -> "${secondsAgo / 86_400} d"
    }

    fun kind(name: String): NeedsKind? = when (name) {
        "Permission" -> NeedsKind.Permission
        "Question" -> NeedsKind.Question
        "CheckFix" -> NeedsKind.CheckFix
        "Approve" -> NeedsKind.Approve
        "Choose" -> NeedsKind.Choose
        "Release" -> NeedsKind.Release
        "CheckFailing" -> NeedsKind.CheckFailing
        else -> null
    }

    /** The PC's needs-you items, oldest first, as the list shows them. Unknown kinds (a newer PC) are left out. */
    fun needs(items: List<LItem>, name: (String) -> String, nowSeconds: Long): List<NeedsYouItem> =
        items.mapNotNull { i ->
            val k = kind(i.kind) ?: return@mapNotNull null
            val choices = if (k == NeedsKind.Question) offered(i.text, i.choices) else i.choices
            NeedsYouItem(i.askId, k, name(i.project), age(nowSeconds - i.created), i.text, i.task, choices, i.serious, i.leavesPC)
        }

    /** PCS-10: the warning shown for an action that leaves this PC and can't be undone. */
    const val LEAVES_PC_WARNING = "Leaves this PC and is permanent, so check it first"

    /** Whether the approval UI should show the leaves-PC warning for this item. */
    fun approvalWarning(item: NeedsYouItem): String? =
        if (item.leavesPC) LEAVES_PC_WARNING else null

    private val LIST_ITEM = Regex("""^\s*(?:[-*+]|\d+[.)])\s+""")

    /**
     * A question's choices as buttons only when Claude offers them: a line of its own asks right
     * before the list ("Which one should I do?", "Pick one:") or right after it ("Which?"). A plain
     * list, even one whose last item ends in "?", is part of the reply, not options (QA B1). All or
     * none, so a button's number is still the PC's.
     */
    fun offered(text: String, choices: List<String>): List<String> {
        if (choices.isEmpty()) return choices
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.indexOfFirst { LIST_ITEM.containsMatchIn(it) }
        val last = lines.indexOfLast { LIST_ITEM.containsMatchIn(it) }
        if (first < 0) return emptyList()
        val before = lines.getOrNull(first - 1)?.trimEnd('*', '_', ' ')
        val after = lines.getOrNull(last + 1)?.trimEnd('*', '_', ' ')
        val asks = before != null && (before.endsWith("?") || before.endsWith(":")) || after != null && after.endsWith("?")
        return if (asks) choices else emptyList()
    }

    /** The answer the PC takes for a button; null for one that isn't an answer (opening a design). */
    fun answer(a: Answer): ItemAnswer? = when (a) {
        Answer.Allow -> ItemAnswer.Plain("Allow")
        Answer.DontAllow -> ItemAnswer.Plain("DontAllow")
        Answer.AlwaysAllowFile -> ItemAnswer.Plain("AllowFileThisTask")
        Answer.Release -> ItemAnswer.Plain("Release")
        Answer.KeepForLater -> ItemAnswer.Plain("KeepForLater")
        Answer.ItWorks -> ItemAnswer.Plain("Works")
        Answer.StillBroken -> ItemAnswer.Plain("StillBroken")
        Answer.AskClaudeToFix -> ItemAnswer.Plain("AskClaudeToFix")
        Answer.Approve -> ItemAnswer.Plain("Approve")
        Answer.Opened -> null
    }

    /** What a chat message's line under it says once the PC filed it. */
    fun filing(f: Filed, name: (String) -> String): Filing? = when (f) {
        is Filed.NewTask -> Filing(FilingKind.NewTask, name(f.project), taskId = f.task)
        is Filed.SameTask -> Filing(FilingKind.SameTask, name(f.project), taskId = f.task)
        is Filed.Idea -> Filing(FilingKind.Idea, name(f.project))
        is Filed.Inbox -> Filing(FilingKind.Inbox, "", f.candidates.map(name))
        is Filed.Status, is Filed.Talk, is Filed.OnPhone -> null
    }

    // ---- The PC's one truth (Stage 1, section 3): every surface reads it, none works a state out ----

    /** A task's state from the truth, in the words every screen uses (the same as [taskWords]). */
    fun truthWords(state: String, failed: Boolean = false): String = when (state) {
        "Working" -> "Working"
        "NeedsYou" -> "Needs you"
        "LiveElsewhere" -> "Live"
        "Stopped" -> if (failed) "Stopped unexpectedly" else "Stopped"
        else -> "Done"
    }

    /**
     * The status page as the truth says it: its projects (working and needs-you counts, checks only
     * where there are any), Claude's use (only while a paid choice is on) and the PC's line. What
     * only the older page has (a project's version and line) is kept from [page].
     */
    fun pageFromTruth(t: io.github.odnwks.mimir.link.LTruth, page: LStatus?): LStatus {
        val old = page?.projects?.associateBy { it.path }.orEmpty()
        return LStatus(
            projects = t.projects.map { p ->
                io.github.odnwks.mimir.link.LProject(
                    p.path, p.name, old[p.path]?.version, p.checks, p.working, p.needsYou, old[p.path]?.line.orEmpty(), p.lastActivity,
                )
            },
            // The truth says percent; the page said the share used.
            weeklyUsed = t.usage?.weekly?.first?.div(100.0),
            weeklyResets = t.usage?.weekly?.second,
            reply = emptyList(),
            dataTime = t.asOf,
            pcLine = t.pc.line.ifBlank { null },
        )
    }

    /** A project's checks as the gauge shows them. */
    private fun state(p: io.github.odnwks.mimir.link.LProject): CheckState = when {
        p.checks == null || p.checks.total == 0 -> CheckState.None
        p.checks.failing.isNotEmpty() -> CheckState.Fail
        else -> CheckState.Pass
    }

    /**
     * The Status and Project screens: one [Project] per project on the status page, with its
     * tasks and ideas, in the page's order (needs you first, then failing, then latest).
     */
    fun projects(
        page: LStatus,
        tasks: Collection<LinkTask>,
        ideas: Map<String, List<String>>,
        nowSeconds: Long,
    ): List<Project> = page.projects.map { p ->
        val c = p.checks
        val st = state(p)
        val failing = c?.failing?.firstOrNull()?.second
        val spoken = buildString {
            append(p.name).append(": ")
            append(
                when (st) {
                    CheckState.None -> "no checks set up"
                    CheckState.Pass -> "all ${c!!.total} checks passing"
                    CheckState.Fail -> "${c!!.failing.size} of ${c.total} failing" + (failing?.let { ", ${it.trimEnd('.')}" } ?: "")
                },
            )
            if (p.tasksRunning > 0) append(". ${p.tasksRunning} running")
            if (p.needsYou > 0) append(". ${p.needsYou} waiting for you")
        }
        val mine = tasks.filter { it.project == p.path }.sortedByDescending { maxOf(it.activeUnix, it.createdUnix) }
        Project(
            lastActivity = p.lastActivity?.let { at -> age((nowSeconds - at).coerceAtLeast(0)).let { if (it == "now") "now" else "$it ago" } }.orEmpty(),
            needsYou = p.needsYou,
            name = p.name,
            about = p.path,
            state = st,
            passing = c?.passing ?: 0,
            total = c?.total ?: 0,
            // The gauge says how many pass; the line says what fails and what's going on.
            line = (listOfNotNull(failing?.let { "Failing: " + it.trimEnd('.') }) +
                p.line.split(" · ").drop(1)).joinToString(" · "),
            spokenLine = spoken,
            checks = c?.features?.map { (_, sentence, ok) -> Check(sentence.trimEnd('.'), ok) }.orEmpty(),
            failingSince = c?.failingSince?.let { "Failing for ${age(nowSeconds - it)}" }.orEmpty(),
            tasks = mine.map {
                ProjectTask(it.id, it.title, if (working(it)) "Working" else it.status, Chip.of(working(it), it.status))
            } + ideas[p.path].orEmpty().map { ProjectTask(null, it, "Idea", Chip.Idea) },
            // From the tasks themselves, which stay current, not the status page (read each minute).
            tasksRunning = mine.count { working(it) },
        )
    }

    /** Claude's weekly use, as Status shows it. */
    fun usage(page: LStatus, nowSeconds: Long): Usage {
        val used = page.weeklyUsed ?: return Usage("—", 0, "", 85)
        // The PC sends the share used, 0 to 1 (0.93 is 93%), and rounds it as the phone does here.
        val pct = Math.round(used.coerceIn(0.0, 1.0) * 100).toInt()
        val resets = page.weeklyResets?.let { r -> if (r > nowSeconds) "resets in ${age(r - nowSeconds)}" else "" }.orEmpty()
        return Usage("$pct%", pct, resets, 85)
    }

    /** An update card as the Updates page shows it (newest first comes from the PC). */
    fun update(c: io.github.odnwks.mimir.link.LCard, name: (String) -> String, heard: Boolean, nowSeconds: Long, lengthSeconds: Int?): Update {
        val ago = (nowSeconds - c.time).coerceAtLeast(0)
        val short = age(ago)
        // About 2.6 words a second until the player knows the real length.
        val words = c.text.split(Regex("\\s+")).count { it.isNotBlank() }
        return Update(
            id = c.id,
            project = if (c.project.isEmpty()) io.github.odnwks.mimir.ui.AI_NAME else name(c.project),
            whenShort = short,
            whenSpoken = short,
            text = c.text + (c.audioNote?.let { "\n($it)" } ?: ""),
            heard = if (heard) Heard.Heard else Heard.New,
            opens = when {
                c.task != null -> UpdateTarget.TaskOf(c.task)
                c.project.isNotEmpty() -> UpdateTarget.ProjectOf(name(c.project))
                else -> UpdateTarget.NeedsYou
            },
            lengthSeconds = lengthSeconds ?: (words / 2.6).toInt().coerceAtLeast(2),
        )
    }

    /**
     * What plays when an update's Play is tapped: that update, then each older one in turn (the
     * PC sends newest first, so Play on the newest is "Play latest"). Cards without audio on the
     * phone are skipped.
     */
    fun playlist(
        cards: List<io.github.odnwks.mimir.link.LCard>,
        startId: String,
        name: (String) -> String,
        file: (String) -> java.io.File?,
    ): List<io.github.odnwks.mimir.audio.Track> =
        cards.dropWhile { it.id != startId }.mapNotNull { c ->
            val f = c.audio?.let(file)?.takeIf { it.isFile } ?: return@mapNotNull null
            io.github.odnwks.mimir.audio.Track(c.id, c.text.take(80), if (c.project.isEmpty()) io.github.odnwks.mimir.ui.AI_NAME else name(c.project), f)
        }

    /** The player's speeds, in turn: 0.75×, 1×, 1.25×, 1.5× (pitch kept). */
    fun nextSpeed(now: Float): Float {
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f)
        val i = speeds.indexOfFirst { kotlin.math.abs(it - now) < 0.01f }
        return speeds[(i + 1).mod(speeds.size)]
    }

    /** The Claude choices the phone offers (the PC's own model ids). */
    val CLAUDE = listOf(
        Model("auto", "Auto", "Picks the best model for each message", free = false),
        Model("auto-capable", "Auto, stronger", "One step stronger, for harder work", free = false),
        Model("claude-fable-5-2", "Fable 5.2", "The hardest, longest work · uses the most", free = false),
        Model("claude-opus-5-5", "Opus 5.5", "Strong everyday choice for real work", free = false),
        Model("claude-sonnet-5-5", "Sonnet 5.5", "Fast and balanced", free = false),
        Model("claude-haiku-5-5", "Haiku 5.5", "Quick answers, lightest", free = false),
    )

    /** A task's model as the phone names it. */
    fun modelName(id: String?): String = when {
        id == null -> "The default choice"
        id.startsWith("ollama:") -> id.removePrefix("ollama:") + " (free)"
        else -> CLAUDE.firstOrNull { it.id == id }?.name ?: id
    }

    /** The Models screen from the PC's list and this phone's choice. */
    fun models(pc: io.github.odnwks.mimir.link.LModels?, chosen: String): Models {
        val free = pc?.free.orEmpty().map { Model(it.id, it.name, it.note, free = true, refusalsRemoved = it.refusalsRemoved) }
        val fallback = pc?.free?.firstOrNull { it.id == pc.freeModel }?.name ?: free.firstOrNull { !it.refusalsRemoved }?.name ?: "a free model"
        return Models(CLAUDE, free, chosen, pc?.switchToFree ?: false, fallback, weeklyCheck = pc?.weeklyCheck ?: true, power = Power.of(pc?.power), chatsEngine = pc?.chatsEngine, projectEngine = pc?.projectEngine, chatsModel = pc?.chatsModel, chatModels = pc?.chatModels.orEmpty(), seesPictures = pc?.seesPictures)
    }
}
