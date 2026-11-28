package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.RowKind
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.ui.adaptive.forDevice
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import kotlinx.coroutines.delay

/*
 * Press and hold on a row (P29): its menu, "Delete forever" with a sum and a 10-second wait, and
 * the one picker that takes a chat's words (or a task) to a project.
 */

const val ROW_TOP = "Move to the top"
const val ROW_TOP_BACK = "Move back down"
const val ROW_COLLAPSE_PROJECTS = "Collapse projects"
const val ROW_COLLAPSE_CHATS = "Collapse chats"
const val ROW_COLLAPSE_DONE = "Collapse finished tasks"
const val ROW_SHOW_DONE = "Show finished tasks"
const val ROW_ARCHIVE = "Archive"
const val ROW_BRING_BACK = "Bring back"
const val ROW_DELETE = "Delete forever…"
const val ROW_DELETE_LATER = "Delete forever (when it stops)"
const val ROW_TAKE = "Take to a project…"
const val ROW_TAKE_ABOUT = "Its words go into that project's box. Nothing starts until you send."
const val ROW_MOVE_TASK = "Move to another project…"
const val ROW_RENAME = "Rename"
const val ARCHIVED_ROW = "Archived"
const val PROJECT_WORK = "Do this as Project work"
const val DELETE_YES = "Yes, delete it forever"
const val DELETE_KEEP = "Keep it"
const val DELETE_SUM = "Your answer"
const val DELETE_SECONDS = 10

fun deletingIn(seconds: Int) = "Deleting in $seconds s…"
fun deleteSumWords(a: Int, b: Int) = "To go on, type what $a + $b makes."

/** What goes and what stays, said before anything is deleted. */
fun deleteWords(kind: RowKind, tasks: Int, pcAway: Boolean, phoneOnly: Boolean = false): String = when (kind) {
    RowKind.Project -> "This removes it from $AI_NAME" +
        (if (tasks == 0) "" else if (tasks == 1) ", with its 1 task and that conversation" else ", with its $tasks tasks and their conversations") +
        ". There is no undo.\n\nThe project folder and its files stay on your PC."
    RowKind.Chat -> if (phoneOnly) "It is removed from this phone's list. There is no undo." else "The whole conversation and anything attached to it are removed. There is no undo." +
        (if (pcAway) "\n\nYour PC removes its copy when it's back." else "")
    RowKind.Task -> "Its conversation and the files sent with it on your PC go too. Changes it made to your project stay. There is no undo."
}

/** The words a chat takes to a project: what was said, the newest kept when it is long. Pictures and files don't go. */
fun chatWords(chat: LocalChat, limit: Int = 6_000): String {
    val lines = chat.turns.filter { it.text.isNotBlank() }.map { (if (it.user) "Me: " else "$AI_NAME: ") + it.text.trim() }
    val all = lines.joinToString("\n\n")
    return "From a chat:\n\n" + if (all.length <= limit) all else "…" + all.takeLast(limit)
}

private val NEEDS_PC_ASKED = Regex(
    "hand-?off|\\b(on|from) (the|my) (desktop|pc|computer)\\b|\\b(run|rerun) (the |my )(tests?|build|checks?|script)\\b|" +
        "\\b(my|the) (repo|repository|code ?base|source code|project folder)\\b",
    RegexOption.IGNORE_CASE,
)
private val OFFERED_PROJECT_WORK = Regex(
    "project work|\\btask in (one of )?your (own )?projects?\\b|\\bas a (project )?task\\b|\\bcan(no|')t\\b[^.!?\\n]{0,80}\\bfrom (this|the) chat\\b",
    RegexOption.IGNORE_CASE,
)

/** Whether this question and its answer are about something only a task on the PC can do. */
fun needsProjectWork(question: String, answer: String): Boolean =
    NEEDS_PC_ASKED.containsMatchIn(question) || OFFERED_PROJECT_WORK.containsMatchIn(answer.replace('’', '\''))

private fun localChat(engine: Engine?, id: String): LocalChat? =
    engine?.state?.value?.let { s -> (listOf(s.chat) + s.earlier).firstOrNull { it.id == id } }

@Composable
private fun rowEngine(state: StationState): Engine? {
    val given = LocalPhoneEngine.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ok = LocalStandaloneOk.current
    return given ?: if (keepsChatsHere(ok, state.paired)) Engine.get(ctx) else null
}

/** The menu of the row that was held. */
@Composable
fun RowMenuSheet(sheet: Sheet.RowMenu, state: StationState, station: Station, nav: Navigator) {
    val keep = LocalRowKeep.current
    val fold = LocalMenuFold.current
    val undo = LocalUndo.current
    val standIn = LocalStandIn.current
    val delete = { nav.openSheet(Sheet.DeleteForever(sheet.kind, sheet.id, sheet.name, sheet.mimir)) }
    Menu(sheet.name) {
        when (sheet.kind) {
            RowKind.Project -> {
                if (sheet.id in keep.archivedProjects) {
                    MenuRow(Glyphs.Folder, ROW_BRING_BACK) { keep.bringBackProject(sheet.id); nav.closeSheet() }
                } else {
                    if (sheet.id in keep.topProjects) MenuRow(Glyphs.Down, ROW_TOP_BACK) { keep.takeOffTop(sheet.id); nav.closeSheet() }
                    else MenuRow(Glyphs.Up, ROW_TOP, note = "Stays above the others") { keep.putOnTop(sheet.id); nav.closeSheet() }
                    if (fold != null && sheet.inMenu) MenuRow(Glyphs.Up, ROW_COLLAPSE_PROJECTS, note = "Fold the whole list") { if (fold.projectsOpen) fold.toggleProjects(); nav.closeSheet() }
                    MenuRow(Glyphs.Folder, ROW_ARCHIVE, note = "Hidden from the menu. Nothing is deleted.") {
                        keep.archiveProject(sheet.id); nav.closeSheet()
                        undo(Undoable("Archived ${sheet.name}", undo = { keep.bringBackProject(sheet.id) }))
                    }
                }
                if (station.canDeleteProjects()) MenuRow(Glyphs.Close, ROW_DELETE, Palette.Red) { delete() }
                else MenuRow(Glyphs.Close, "Delete forever", Palette.TextDim, note = if (state.pcAway) "Your PC is away." else "Update $AI_NAME on your PC first.") {}
            }
            RowKind.Chat -> {
                val engine = rowEngine(state)
                val chat = if (sheet.mimir) localChat(engine, sheet.id) else null
                if (chat != null && state.paired && state.owner && chat.turns.isNotEmpty()) {
                    MenuRow(Glyphs.Move, ROW_TAKE, note = ROW_TAKE_ABOUT) { nav.openSheet(Sheet.TakeToProject(chatWords(chat))) }
                }
                if (fold != null && sheet.inMenu && fold.chatsOpen) MenuRow(Glyphs.Up, ROW_COLLAPSE_CHATS, note = "Fold the whole list") { fold.toggleChats(); nav.closeSheet() }
                val key = (if (sheet.mimir) "" else "p-") + sheet.id
                if (key in keep.archivedChats) MenuRow(Glyphs.Folder, ROW_BRING_BACK) { keep.bringBackChat(key); nav.closeSheet() }
                else MenuRow(Glyphs.Folder, ROW_ARCHIVE, note = "Hidden from the menu. Nothing is deleted.") {
                    keep.archiveChat(key); nav.closeSheet()
                    undo(Undoable("Archived ${sheet.name}", undo = { keep.bringBackChat(key) }))
                }
                MenuRow(Glyphs.Steps, ROW_RENAME) { nav.openSheet(Sheet.RenameChat(sheet.id, sheet.name, sheet.mimir)) }
                MenuRow(Glyphs.Close, ROW_DELETE, Palette.Red) { delete() }
            }
            RowKind.Task -> {
                val task = state.task(sheet.id)
                if (task?.status == "Working") MenuRow(Glyphs.Move, "Move to another project (when it stops)", Palette.TextDim) {}
                else MenuRow(Glyphs.Move, ROW_MOVE_TASK) { nav.openSheet(Sheet.MoveTaskTo(sheet.id)) }
                sheet.project?.let { p ->
                    val folded = p in keep.foldedDone
                    MenuRow(if (folded) Glyphs.Down else Glyphs.Up, if (folded) ROW_SHOW_DONE else ROW_COLLAPSE_DONE) { keep.toggleDone(p); nav.closeSheet() }
                }
                MenuRow(Glyphs.Folder, ROW_ARCHIVE) {
                    station.archiveTask(sheet.id); nav.closeSheet()
                    undo(Undoable("Archived ${sheet.name}", undo = { station.unarchiveTask(sheet.id) }))
                }
                MenuRow(Glyphs.Steps, "$ROW_RENAME…") { nav.openSheet(Sheet.RenameTask(sheet.id)) }
                // The PC refuses while it works or waits on the person, so the menu says when instead.
                if (task?.showsStop == true) MenuRow(Glyphs.Close, ROW_DELETE_LATER, Palette.TextDim) { standIn("Stop this task first, then delete it") }
                else MenuRow(Glyphs.Close, ROW_DELETE, Palette.Red) { delete() }
            }
        }
    }
}

/** Rename a chat from its row. */
@Composable
fun RenameChatSheet(sheet: Sheet.RenameChat, state: StationState, station: Station, nav: Navigator) {
    val engine = rowEngine(state)
    var title by rememberSaveable(sheet.chatId) { mutableStateOf(sheet.name) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Handle() }
        SectionTitle("Rename this chat", style = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold))
        MessageBox(title, { title = it }, "Chat name", "Chat name", Modifier.fillMaxWidth(), autoFocus = true)
        BigButton(ROW_RENAME, Look.Primary, {
            if (sheet.mimir) engine?.rename(title.trim(), sheet.chatId) else station.renamePastChat(sheet.chatId, title.trim())
            nav.closeSheet()
        }, height = 48.dp, enabled = title.isNotBlank())
        BigButton("Cancel", Look.Outline, { nav.closeSheet() }, height = 48.dp)
    }
}

/** The projects, to take [words] to (they go into that project's box, unsent) or to move [taskId] to. */
@Composable
fun PickProjectSheet(title: String, state: StationState, nav: Navigator, leaveOut: String?, pick: (String) -> Unit) {
    val names = state.projects.map { it.name }.filter { it != leaveOut }
    Menu(title) {
        if (names.isEmpty()) Text("No other project yet. Make one from the side menu: $MENU_NEW_PROJECT", style = Type.Label, color = Palette.TextSoft, modifier = Modifier.padding(12.dp))
        names.forEach { n -> MenuRow(Glyphs.Folder, n) { pick(n) } }
    }
}

/**
 * "Delete forever": what goes and what stays, a small sum, then 10 seconds in which "Keep it" (or
 * Back, or leaving the app) stops it. The delete is asked for once, at the end of those seconds;
 * it is never kept to be sent later.
 */
@Composable
fun DeleteForeverSheet(sheet: Sheet.DeleteForever, state: StationState, station: Station, nav: Navigator) {
    val engine = rowEngine(state)
    val keep = LocalRowKeep.current
    val standIn = LocalStandIn.current
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val say = { words: String -> android.os.Handler(android.os.Looper.getMainLooper()).post { standIn(words) }; Unit }
    val a = rememberSaveable(sheet.id) { (6..19).random() }
    val b = rememberSaveable(sheet.id) { (6..19).random() }
    var answer by rememberSaveable(sheet.id) { mutableStateOf("") }
    var left by remember(sheet.id) { mutableStateOf<Int?>(null) }
    val needsPc = sheet.kind != RowKind.Chat
    val away = needsPc && state.pcAway
    val task = if (sheet.kind == RowKind.Task) state.task(sheet.id) else null
    val tasks = if (sheet.kind == RowKind.Project) (state.tasks + state.archived).count { it.project == sheet.id } else 0
    // Leaving the app during the wait stops it: nothing is deleted out of sight.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { if (left != null) { left = null; nav.closeSheet() } }
    val delete = delete@{
        // The sheet goes first: once closed, nothing here can ask a second time.
        if (nav.sheet != sheet) return@delete
        nav.closeSheet()
        when (sheet.kind) {
            RowKind.Chat -> {
                if (sheet.mimir) {
                    if (engine?.deleteForever(sheet.id) == true) keep.bringBackChat(sheet.id) else say("Not deleted: stop the reply first.")
                } else {
                    station.deletePastChat(sheet.id); keep.bringBackChat("p-${sheet.id}")
                }
            }
            RowKind.Task -> {
                val open = (nav.current as? Screen.Task)?.taskId == sheet.id
                nav.hiddenTasks.add(sheet.id)
                if (open) nav.closeScreen()
                sendDelete(station, nav, ctx, sheet.id, say)
            }
            RowKind.Project -> {
                if ((nav.current as? Screen.Project)?.name == sheet.id) nav.closeScreen()
                // Said at once: a slow PC may take a while, and the person should know it was asked.
                standIn("Asking your PC to delete ${sheet.name}…")
                station.deleteProject(sheet.id) { why ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (why == null) { keep.forgetProject(sheet.id); standIn("Deleted ${sheet.name}. Its folder is still on your PC.") }
                        else standIn("Not deleted: $why")
                    }
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(left != null) {
        if (left == null) return@LaunchedEffect
        while ((left ?: return@LaunchedEffect) > 0) { delay(1_000); left = (left ?: return@LaunchedEffect) - 1 }
        delete()
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Handle() }
        SectionTitle("Delete “${sheet.name}” forever?", style = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold))
        val waiting = left
        if (waiting != null) {
            Text(deletingIn(waiting), style = Type.Body, color = Palette.Red, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BigButton(DELETE_KEEP, Look.Primary, { left = null; nav.closeSheet() })
        } else {
            Text(deleteWords(sheet.kind, tasks, state.paired && !state.pc.online, phoneOnly = !sheet.mimir).forDevice(), style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.45f), color = Palette.TextSoft)
            when {
                away -> Text("Your PC is away: it can be deleted when it's back.", style = Type.Label, color = Palette.AmberNote)
                task?.showsStop == true -> Text("Stop this task first, then delete it.", style = Type.Label, color = Palette.AmberNote)
                else -> {
                    Text(deleteSumWords(a, b), style = Type.Label, color = Palette.Text)
                    MessageBox(answer, { answer = it.filter(Char::isDigit).take(3) }, DELETE_SUM, DELETE_SUM, Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            val ready = !away && task?.showsStop != true && answer.toIntOrNull() == a + b
            // Drawn afresh when it turns on: the outline of a button that was off is otherwise lost.
            androidx.compose.runtime.key(ready) { BigButton(DELETE_YES, Look.Danger, { keyboard?.hide(); left = DELETE_SECONDS }, bold = false, enabled = ready) }
            BigButton(DELETE_KEEP, Look.Primary, { nav.closeSheet() })
        }
    }
}
