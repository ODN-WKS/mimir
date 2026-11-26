package io.github.odnwks.mimir.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The three home screens, reached from the side menu (there is no bottom bar): Chat is home, Status is
 * "Your PC", Updates stays. Settings is its own row at the bottom of the menu.
 */
enum class Tab(val label: String, val root: Screen) {
    Chat("Chat", Screen.Chat),
    Status("Your PC", Screen.Status),
    Updates("Updates", Screen.Updates),
    ;

    companion object {
        /** Every place stays in the menu, paired or not: Updates never hides (an empty one says so itself). */
        @Suppress("UNUSED_PARAMETER")
        fun shown(paired: Boolean, anyUpdates: Boolean): List<Tab> = entries
    }
}

/** Every screen of the app (one per approved board; Confirms is [Sheet], Trouble is Chat with the PC asleep). */
sealed interface Screen {
    /** Whether the screen clears the phone's bottom bar (there is no tab bar any more; kept for the screens' bottom room). */
    val bottomBar: Boolean get() = true
    /** One of the three tab pages (shown in the pager, which follows the finger). */
    val tabPage: Boolean get() = Tab.entries.any { it.root == this }

    data object Chat : Screen
    data object Status : Screen
    data object Updates : Screen
    /** Settings, behind the cog on each tab: Voice, Who answers, Your PC, Privacy, Look and size, Storage, Help and about. */
    data object Settings : Screen
    data object NeedsYou : Screen
    data class Task(val taskId: String) : Screen { override val bottomBar get() = false }
    data class Change(val taskId: String) : Screen { override val bottomBar get() = false }
    data class Project(val name: String) : Screen
    data object Models : Screen
    /** One model's page in the Model Store (Settings › Who answers). */
    data class ModelDetails(val id: String) : Screen
    data object PhonePc : Screen
    /** Your PC › PC settings: its name, how phones reach it, the phones, Disconnect (a PC that lists its sessions). */
    data object PcSettings : Screen
    data object Notifications : Screen
    data object IncognitoTask : Screen { override val bottomBar get() = false }
    data object Pair : Screen { override val bottomBar get() = false }
    data object PairDone : Screen { override val bottomBar get() = false }
    data object PairError : Screen { override val bottomBar get() = false }
    /** Standalone mode: a model on this phone (Part C). */
    data object Standalone : Screen { override val bottomBar get() = false }
    data object StandaloneModels : Screen { override val bottomBar get() = false }
    /** Every task: search, a state filter, and archived tasks (from Status › All tasks or a magnifier). */
    data class AllTasks(val archived: Boolean = false) : Screen
    /** Settings › General: Help. */
    data object General : Screen
    /** Settings › Storage: what the app keeps on this phone, each with Delete. */
    data object Storage : Screen
    /** Settings › Voice: read-aloud speed, and Talk (voice typing). */
    data object Voice : Screen
    /** Settings › Voice › Voices (also in Models): the natural voices to download for read aloud. */
    data object Voices : Screen
    /** Settings › About: the version and Check for a new version. */
    data object About : Screen
    /** Settings › Privacy check: what the app sent and received over the network. */
    data object PrivacyCheck : Screen
    /** Settings › Who answers › Ground rules: what every model is told first. */
    data object GroundRules : Screen
    /** Settings › Help and about: questions people ask, each with a short answer, and the version. */
    data object Help : Screen
    /** Settings › Help and about › Labs: the notes for fixing problems, Run checks, and Report a problem. */
    data object Labs : Screen
    /** One Help question's answer. */
    data class HelpAnswer(val id: String) : Screen
    /** Chats: every earlier conversation (from the chat's eye, or a swipe in from the left edge). */
    data object Chats : Screen { override val bottomBar get() = false }
    /** The app's own browser: a page the owner opens from a chat (search results, a source). */
    data class Browse(val url: String) : Screen { override val bottomBar get() = false }
}

/**
 * With two panes, whether this screen opens on the right of [left] instead of over it:
 * a task, its change, a project and Needs you always do; Settings' own pages do beside Settings.
 */
fun Screen.opensOnRight(left: Screen): Boolean = when (this) {
    is Screen.Task, is Screen.Change, is Screen.Project, Screen.NeedsYou -> left != this
    Screen.General, Screen.Models, Screen.PhonePc, Screen.PcSettings, Screen.Notifications,
    Screen.Storage, Screen.Voice, Screen.About, Screen.Help, Screen.Labs, Screen.GroundRules, Screen.PrivacyCheck -> left == Screen.Settings
    Screen.Voices, is Screen.HelpAnswer -> left == Screen.Settings
    else -> false
}

/** The choices that ask first: each slides up from the bottom, near the thumb. */
sealed interface Sheet {
    data class TaskOptions(val taskId: String) : Sheet
    data class StopTask(val taskId: String) : Sheet
    /** "Delete this task?": the task, its conversation and its files on the PC go (after 8 seconds to undo). */
    data class DeleteTask(val taskId: String) : Sheet
    data class AlwaysAllow(val taskId: String) : Sheet
    /** What a task always allows, each with Stop allowing (from the task's ⋯). */
    data class AlwaysAllowed(val taskId: String) : Sheet
    data class Release(val taskId: String) : Sheet
    data object IncognitoOptions : Sheet
    data object EndIncognito : Sheet
    data object Unpair : Sheet
    data class RenameTask(val taskId: String) : Sheet
    data class TaskModel(val taskId: String) : Sheet
    /** The full YOLO warning, every time, before the phone's unlock. */
    data class Yolo(val taskId: String) : Sheet
    /**
     * Everything for the open phone chat, from a tap on its topic: Rename, Delete, read-aloud
     * speed, Whisper and how it stays private. What the chat allows is taken when it opens.
     */
    /** [pc]: the paired PC answers this chat (its privacy words say so). */
    data class ChatOptions(val topic: String?, val canRename: Boolean, val whisper: Boolean, val canWhisper: Boolean, val pc: Boolean = false, /** The company an online model belongs to, when it answers this chat: its messages go there. */ val online: String? = null,
        /** Bytes of this chat sent to the PC and the lookups made in it, when this chat counts them (-1: not counted here). */
        val sentToPc: Long = -1, val lookups: Int = 0,
        /** Chat's "To:" words (it opens "Where should this go?"): the box has no room for it. Null: not offered. */
        val where: String? = null,
        /** What "Talk (voice typing)" asks of the open chat ([TALK_CHAT] or [TALK_MIMIR]); null: not offered. */
        val talk: String? = null) : Sheet
    /**
     * Choose a project (or make one on the PC). The choice goes back to [back] (the composer's
     * "Where should this go?"), opens the project's page ([open]), or goes to the screen that
     * asked (a task being moved).
     */
    data class ProjectPicker(val current: String, val newProject: Boolean = false, val back: Sheet? = null, val open: Boolean = false, val justChat: Boolean = false) : Sheet
    /** The composer's "Where should this go?": Auto or a project, and options for this one task. */
    data object WhereTo : Sheet
    /**
     * The model and effort for the task a box starts (the same sheet as a task's own): [project]
     * names the project page's box; null is Chat's box.
     */
    data class NewTaskModel(val project: String? = null) : Sheet
    /** The Mímir chat's model and effort (its own models, not a task's): what it lists is in [Navigator.chatChoices]. */
    data object ChatModel : Sheet
    /** A project page's three dots: voice typing for its box, and its checks. */
    data class ProjectOptions(val project: String) : Sheet
    /** A project's full list of checks (from its three dots or its slim checks line). */
    data class ProjectChecks(val project: String) : Sheet
}

/**
 * Where Chat's composer sends its next message: Auto (the PC files it) or a project, and for
 * that one task Plan first, a model, or Whisper mode.
 */
data class ComposeTo(val project: String = "", val plan: Boolean = false, val model: String = "", val whisper: Boolean = false, val effort: String = "") {
    val auto: Boolean get() = project.isEmpty() && !plan && model.isEmpty() && !whisper && effort.isEmpty()
}

/** One row of the model and effort sheet: its words, a line under them, whether it is the one in use, and the pick. */
class SheetChoice(val title: String, val note: String?, val chosen: Boolean, val pick: () -> Unit)

/**
 * The Mímir chat's own model and effort sheet: its [models], small [notes] under them, [more] (Settings
 * › Who answers) and its [efforts]. The same layout as a task's; the chat's own actions.
 */
class ModelEffortChoices(val models: List<SheetChoice>, val notes: List<String>, val more: () -> Unit, val efforts: List<SheetChoice>)

/** A project page's choice for its next task: a model and an effort ("": the usual ones). */
data class TaskChoice(val project: String = "", val model: String = "", val effort: String = "")

/** How the last screen change happened; it picks the animation. */
sealed interface Move {
    data object Forward : Move
    data object Back : Move
    /** To another section of the bottom bar; [toRight] when it lies to the right of the old one. */
    data class ToTab(val toRight: Boolean) : Move
    /** A tapped card grows into its screen from the tap point (fractions of the screen, 0..1). */
    data class CardOpen(val x: Float, val y: Float) : Move
    data object None : Move
}

/** What the phone chat's topic sheet hands back to the open chat. */
enum class ChatAsk { Rename, Delete, Whisper, StartWhisper, NewChat, StartWebWhisper, CopyChat, Rules, Sent }

/** One hand-over from the sheet (compared by identity, never equal to an earlier one). */
class ChatRequest(val ask: ChatAsk)

/** Where a finger touched, as fractions of the screen's width and height. */
data class TapPoint(val x: Float, val y: Float)

/** One screen on the back stack; [key] keeps two copies of the same screen apart. */
data class Entry(val screen: Screen, val key: Int)

/**
 * The back stack. Each section of the bottom bar starts its own stack; Back walks the stack, then
 * returns to Chat, then leaves the app. A sheet sits above the stack and Back closes it first.
 */
class Navigator(start: Screen = Screen.Chat) {
    private var nextKey = 0
    var stack: List<Entry> by mutableStateOf(listOf(Entry(start, nextKey++)))
        private set
    var sheet: Sheet? by mutableStateOf(null)
        private set
    var lastMove: Move by mutableStateOf(Move.None)
        private set
    /** The side menu is open (set by the app root), so no box takes the keyboard meanwhile. */
    var menuOpen: Boolean by mutableStateOf(false)
    /** Where Chat's composer sends the next message (the "To:" chip). */
    var composeTo: ComposeTo by mutableStateOf(ComposeTo())
    /** What the open Mímir chat offers in its model and effort sheet (set by the chat while it shows). */
    var chatChoices: ModelEffortChoices? by mutableStateOf(null)
    /** The model and effort for the next task a project page's box starts. */
    var newTaskChoice: TaskChoice by mutableStateOf(TaskChoice())
    /** The project just chosen in the picker, for the screen that opened it to take. */
    var pickedProject: String? by mutableStateOf(null)
    /** What the task's three-dots menu asked of the open task screen ([TASK_ASK_FIND] or [TASK_ASK_TALK]), for it to do. */
    var taskAsk: String? by mutableStateOf(null)
    /** Tasks deleted a moment ago: hidden from the lists while Undo still shows, then gone from the PC. */
    val hiddenTasks = mutableStateListOf<String>()
    /** What the phone chat's sheet asked of the open chat (rename, delete, Whisper), for the chat to do. */
    var chatAsk: ChatRequest? by mutableStateOf(null)

    /** Hands [ask] to the open chat: a new request each time, so asking the same twice still counts. */
    fun askChat(ask: ChatAsk) { chatAsk = ChatRequest(ask) }
    /** Open Pair with the paste box showing (from "Paste the code instead"). */
    var pastePairCode: Boolean by mutableStateOf(false)

    val current: Screen get() = stack.last().screen

    /**
     * Two panes (a tablet window 840 dp or wider, once paired): what is open on the right, beside
     * [current]. Empty means the right shows the section's own companion (Needs you beside Chat).
     */
    var detail: List<Entry> by mutableStateOf(emptyList())
        private set
    var twoPanes: Boolean by mutableStateOf(false)
        private set
    /** How the right pane last changed: true when something opened there (it slides in from the right). */
    var detailForward: Boolean by mutableStateOf(true)
        private set
    /** Goes up by one each time "Ask Claude" asks for the cursor in Chat's message box. */
    var askTick: Int by mutableIntStateOf(0)
        private set

    /**
     * The window gained or lost room for two panes (the tablet turned, or a split screen changed).
     * What was open stays open: an item shown full-screen moves to the right, and back again.
     */
    fun fitPanes(on: Boolean) {
        if (on == twoPanes) return
        twoPanes = on
        lastMove = Move.None
        if (on) {
            val i = (1 until stack.size).firstOrNull { stack[it].screen.opensOnRight(stack[it - 1].screen) }
            if (i != null) { detail = stack.drop(i); stack = stack.take(i) }
        } else if (detail.isNotEmpty()) {
            stack = stack + detail
            detail = emptyList()
        }
    }

    /** "Ask Claude": Chat, with the cursor in its message box. */
    fun ask() {
        if (current != Screen.Chat) selectTab(Tab.Chat)
        askTick++
    }

    /** False only on Chat with nothing open: then Back leaves the app. */
    val canGoBack: Boolean get() = sheet != null || detail.isNotEmpty() || stack.size > 1 || current != Screen.Chat
    val currentEntry: Entry get() = stack.last()

    /** The highlighted section, or null on Needs you (it belongs to every section). */
    val selectedTab: Tab?
        get() = if (current == Screen.NeedsYou) null else Tab.entries.firstOrNull { it.root == stack.first().screen } ?: Tab.Chat

    /** Opens [screen] on top. With [from] (a tap point as fractions of the screen) it grows from there. */
    fun open(screen: Screen, from: TapPoint? = null) {
        sheet = null
        if (screen == current && detail.isEmpty()) return
        Tab.entries.firstOrNull { it.root == screen }?.let { selectTab(it); return }
        if (twoPanes && screen.opensOnRight(current)) {
            if (detail.lastOrNull()?.screen == screen) return
            detailForward = true
            // A task's change and the pages inside a settings section sit on top of what is on the right;
            // a task, a project, Needs you or a settings section replaces it.
            val inner = screen is Screen.Change || screen is Screen.HelpAnswer || screen == Screen.GroundRules ||
                ((screen == Screen.About || screen == Screen.Labs) && detail.lastOrNull()?.screen == Screen.Help)
            detail = if (inner) detail + Entry(screen, nextKey++) else listOf(Entry(screen, nextKey++))
            return
        }
        detail = emptyList()
        lastMove = from?.let { Move.CardOpen(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) } ?: Move.Forward
        stack = stack + Entry(screen, nextKey++)
    }

    /** Swaps the top screen for [screen] (e.g. "No, they're different" turns Paired into Couldn't pair). */
    fun replace(screen: Screen) {
        sheet = null
        if (detail.isNotEmpty()) { detail = detail.dropLast(1) + Entry(screen, nextKey++); return }
        lastMove = Move.Forward
        stack = stack.dropLast(1) + Entry(screen, nextKey++)
    }

    /** Clears the stack and shows [screen] (e.g. after pairing, straight to Chat). */
    fun resetTo(screen: Screen) {
        sheet = null
        lastMove = Move.Forward
        detail = emptyList()
        stack = listOf(Entry(screen, nextKey++))
    }

    /** A bottom-bar section. Tapping the section already shown goes back to its first screen. */
    fun selectTab(tab: Tab) {
        sheet = null
        detail = emptyList()
        val old = selectedTab
        if (old == tab) {
            if (stack.size > 1) {
                lastMove = Move.Back
                stack = listOf(stack.first())
            }
            return
        }
        lastMove = Move.ToTab(toRight = (old?.ordinal ?: -1) < tab.ordinal)
        stack = listOf(Entry(tab.root, nextKey++))
    }

    fun openSheet(s: Sheet) { sheet = s }
    fun closeSheet() { sheet = null }

    /**
     * System Back or a screen's back arrow. Returns false when the app should close.
     *
     * The rule (0.09, item 5): Back goes to where you came from, one step. A page that lists things
     * (a project page, Needs you, All tasks, Settings, Your PC, Updates, Chats) goes straight to the
     * home chat, so from a task two Backs always reach home, however many pages came before.
     */
    fun back(): Boolean {
        if (sheet != null) { sheet = null; return true }
        if (detail.isNotEmpty()) { detailForward = false; detail = detail.dropLast(1); return true }
        if (stack.size > 1) {
            lastMove = Move.Back
            stack = if (current.backsToHome()) listOf(stack.first().takeIf { it.screen == Screen.Chat } ?: Entry(Screen.Chat, nextKey++))
            else stack.dropLast(1)
            return true
        }
        if (current != Screen.Chat) {
            lastMove = Move.ToTab(toRight = false)
            stack = listOf(Entry(Screen.Chat, nextKey++))
            return true
        }
        return false
    }
}

/** The pages that list things: Back from one of them reaches the home chat (see [Navigator.back]). */
fun Screen.backsToHome(): Boolean = when (this) {
    is Screen.Project, Screen.NeedsYou, is Screen.AllTasks, Screen.Settings, Screen.Status, Screen.Updates, Screen.Chats -> true
    else -> false
}

/** The task menu's "Find in this task". */
const val TASK_ASK_FIND = "find"
/** The task menu's "Talk (voice typing)". */
const val TASK_ASK_TALK = "talk"
/** "Talk (voice typing)" from Chat's three dots, for Chat's box. */
const val TALK_CHAT = "talk-chat"
/** "Talk (voice typing)" from the Mímir chat's three dots, for its box. */
const val TALK_MIMIR = "talk-mimir"
/** "Talk (voice typing)" from a project page's three dots, for its new-task box. */
const val TALK_PROJECT = "talk-project"
