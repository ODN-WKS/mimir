package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.theme.Type
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.data.Heard
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.ChatAsk
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.LocalState
import io.github.odnwks.mimir.ui.adaptive.forDevice
import io.github.odnwks.mimir.ui.theme.Palette
import java.util.Calendar

/*
 * The side menu (redesign R3): everything that used to be the bottom tabs, the cog and the eye's menu
 * lives here. A drawer from the left on a phone; always open on the left of a wide window.
 */

/** Opens the side menu (a no-op while it is always open). */
val LocalOpenMenu = staticCompositionLocalOf<() -> Unit> { {} }

/** How many things wait (what needs the owner plus unheard updates): the number on the menu button. */
val LocalMenuCount = staticCompositionLocalOf { 0 }

/** True while the menu is always open on the left (a wide window): the menu button hides. */
val LocalPermanentMenu = staticCompositionLocalOf { false }

/** How many things wait for the owner: what needs them plus updates not yet heard. */
fun menuCount(state: StationState): Int = state.needsYou.size + unheardUpdates(state)

fun unheardUpdates(state: StationState): Int = state.updates.count { it.heard == Heard.New }

/** The menu's colours (from the mock-up). */
private object MenuColors {
    val Background = Color(0xFF15181D)
    val Dim = Color(0xFF9AA1AD)
    val Amber = Color(0xFFF3C969)
    val AmberDeep = Color(0xFFE0A92B)
    val Blue = Color(0xFF3B82F6)
    val Border = Color(0xFF2B2F37)
    val Tint = Color(0xFF1C2744)
}

private val RowText = TextStyle(fontSize = 15.5.sp, lineHeight = 20.sp)

/** Which sections are folded: remembered (in [prefs]) between runs. Chats starts open, Projects folded. */
class MenuFold(private val prefs: SharedPreferences?) {
    var chatsOpen by mutableStateOf(prefs?.getBoolean(CHATS_KEY, true) ?: true)
        private set
    var projectsOpen by mutableStateOf(prefs?.getBoolean(PROJECTS_KEY, false) ?: false)
        private set

    fun toggleChats() { chatsOpen = !chatsOpen; prefs?.edit()?.putBoolean(CHATS_KEY, chatsOpen)?.apply() }
    fun toggleProjects() { projectsOpen = !projectsOpen; prefs?.edit()?.putBoolean(PROJECTS_KEY, projectsOpen)?.apply() }

    companion object {
        const val PREFS = "side_menu"
        const val CHATS_KEY = "chats_open"
        const val PROJECTS_KEY = "projects_open"
    }
}

/** One earlier chat as the menu lists it. */
data class MenuChat(val key: String, val id: String, val title: String, val at: Long, val mimir: Boolean, val open: Boolean)

/** The chats the menu lists, newest first: this phone's own, and (an older PC) the PC's. */
fun menuChats(state: StationState, local: LocalState?, now: Long): List<MenuChat> {
    val one = !state.paired || state.pcTalks
    return buildList {
        if (local != null) {
            if (!local.whisper && local.chat.turns.isNotEmpty()) add(MenuChat("m-${local.chat.id}", local.chat.id, local.chat.title, local.chat.at, mimir = true, open = true))
            local.earlier.forEach { add(MenuChat("m-${it.id}", it.id, it.title, it.at, mimir = true, open = false)) }
        }
        if (state.paired && !one) state.pastChats.forEach { add(MenuChat("p-${it.id}", it.id, it.title, it.at, mimir = false, open = false)) }
    }.sortedByDescending { if (it.open && it.at <= 0) now else it.at }
}

/** A chat's time in a few letters: today, yesterday, the weekday this week, else the date. */
fun menuTime(at: Long, now: Long): String {
    if (at <= 0) return ""
    fun day(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val days = ((day(now) - day(at)) / 86_400_000L).toInt()
    val c = Calendar.getInstance().apply { timeInMillis = at }
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[c.get(Calendar.DAY_OF_WEEK) - 1]
        else -> arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[c.get(Calendar.MONTH)] + " " + c.get(Calendar.DAY_OF_MONTH)
    }
}

/** What a project's row says on its right, and whether it is amber (needs you), green (running) or dim. */
fun projectState(p: io.github.odnwks.mimir.data.Project): Pair<String, ProjectTone> = when {
    p.needsYou > 0 -> "needs you" to ProjectTone.Needs
    p.tasksRunning > 0 -> "● ${p.tasksRunning} task${if (p.tasksRunning == 1) "" else "s"} running" to ProjectTone.Running
    else -> "idle" to ProjectTone.Idle
}

enum class ProjectTone { Needs, Running, Idle }

const val MENU_SEARCH = "Search chats"
const val MENU_NEW_CHAT = "New chat"
const val MENU_NEEDS_YOU = "Needs you"
const val MENU_UPDATES = "Updates"
const val MENU_PC = "Your PC"
const val MENU_WHISPER = "Whisper"
const val MENU_CHATS = "Chats"
const val MENU_PROJECTS = "Projects"
const val MENU_SETTINGS = "Settings"
const val MENU_BUTTON = "Menu"

/**
 * The menu, top to bottom: search, New chat, Needs you, Updates, Your PC, Whisper, the folding Chats
 * and Projects (paired only), and Settings pinned at the bottom. [close] shuts the drawer after a choice.
 */
@Composable
fun SideMenu(
    state: StationState, local: LocalState?, station: Station, engine: Engine?, nav: Navigator, fold: MenuFold,
    modifier: Modifier = Modifier, close: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val now = System.currentTimeMillis()
    val chats = menuChats(state, local, now)
    val q = query.trim().lowercase()
    val shownChats = if (q.isEmpty()) chats else chats.filter { q in it.title.lowercase() }
    // A few of the newest; the rest are one tap away in Chats (or by searching here).
    val capped = if (q.isEmpty()) shownChats.take(MENU_CHATS_SHOWN) else shownChats
    val go = { action: () -> Unit -> action(); close() }
    val needs = state.needsYou.size
    val unheard = unheardUpdates(state)
    val one = !state.paired || state.pcTalks

    Column(modifier.background(MenuColors.Background).fillMaxSize()) {
        MessageBox(query, { query = it }, MENU_SEARCH, MENU_SEARCH, Modifier.fillMaxWidth().focusOnlyWhenTapped().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), background = Palette.Card, shape = RoundedCornerShape(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item("new") {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(MenuColors.Tint)
                        .clickable(role = Role.Button) { go { nav.selectTab(Tab.Chat); nav.askChat(ChatAsk.NewChat) } }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Glyphs.Plus, null, tint = Palette.Text, modifier = Modifier.size(18.dp))
                    Text(MENU_NEW_CHAT, style = RowText.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                }
            }
            if (state.owner) item("needs") { MenuRow(MENU_NEEDS_YOU, badge = needs, badgeColor = MenuColors.AmberDeep) { go { nav.open(Screen.NeedsYou) } } }
            item("updates") { MenuRow(MENU_UPDATES, badge = unheard, badgeColor = MenuColors.Blue) { go { nav.selectTab(Tab.Updates) } } }
            item("pc") {
                MenuRow(MENU_PC, right = if (state.paired && state.pc.online) "connected" else "not connected") {
                    go { if (state.paired) nav.selectTab(Tab.Status) else nav.open(Screen.Pair) }
                }
            }
            // Press and hold: a Whisper with internet (Mímir on this phone, through Tor), the old "New Whisper-O".
            item("whisper") { MenuRow(MENU_WHISPER, right = "a chat that's never kept", onLongClick = { go { nav.selectTab(Tab.Chat); nav.askChat(ChatAsk.StartWebWhisper) } }) { go { nav.selectTab(Tab.Chat); nav.askChat(ChatAsk.StartWhisper) } } }
            item("chats-head") {
                Section(MENU_CHATS, if (shownChats.isEmpty()) "" else "${shownChats.size}", fold.chatsOpen, amber = false, fold::toggleChats)
            }
            if (fold.chatsOpen) {
                items(capped, key = { "chat-${it.key}" }) { c ->
                    val openIt = {
                        if (c.mimir) {
                            if (!c.open) engine?.open(c.id)
                            if (state.paired && !one) nav.open(Screen.Standalone) else nav.selectTab(Tab.Chat)
                        } else {
                            if (!c.open) station.openPastChat(c.id)
                            nav.selectTab(Tab.Chat)
                        }
                    }
                    MenuRow(
                        c.title.ifBlank { "New chat" }, right = menuTime(c.at, now), dim = false, ellipsize = true,
                        // Press and hold: that chat opens, with its own menu (Rename, Copy, Rules, What was sent, Delete).
                        onLongClick = {
                            go {
                                openIt()
                                nav.openSheet(io.github.odnwks.mimir.nav.Sheet.ChatOptions(
                                    topic = c.title.ifBlank { null }, canRename = c.mimir, whisper = false, canWhisper = false,
                                    pc = state.paired && one && (local?.usePc == true || local?.supported == false) && io.github.odnwks.mimir.online.OnlineModels.answering() == null,
                                    online = io.github.odnwks.mimir.online.OnlineModels.answering()?.let { io.github.odnwks.mimir.online.OnlineModels.serviceOf(it).company },
                                ))
                            }
                        },
                    ) { go { openIt() } }
                }
                if (q.isEmpty() && chats.size > MENU_CHATS_SHOWN) item("all-chats") { MenuRow("All chats", right = "${chats.size}", dim = true) { go { nav.open(Screen.Chats) } } }
                if (q.isNotEmpty() && capped.isEmpty()) item("no-chats") { Text("No chat has that in its name.", style = RowText, color = MenuColors.Dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            }
            if (state.paired && state.owner) {
                val needing = state.projects.count { it.needsYou > 0 }
                item("projects-head") {
                    Section(
                        MENU_PROJECTS,
                        if (!fold.projectsOpen && needing > 0) "${state.projects.size} · $needing needs you" else "${state.projects.size}",
                        fold.projectsOpen, amber = !fold.projectsOpen && needing > 0, fold::toggleProjects,
                    )
                }
                if (fold.projectsOpen) items(state.projects, key = { "project-${it.name}" }) { p ->
                    val (words, tone) = projectState(p)
                    MenuRow(p.name, right = words, rightColor = when (tone) { ProjectTone.Needs -> MenuColors.Amber; ProjectTone.Running -> Palette.GreenText; ProjectTone.Idle -> MenuColors.Dim }, ellipsize = true) {
                        go { nav.open(Screen.Project(p.name)) }
                    }
                }
            }
        }
        HorizontalDivider(color = MenuColors.Border)
        MenuRow(MENU_SETTINGS, right = "voice, models, privacy", modifier = Modifier.padding(vertical = 4.dp)) { go { nav.open(Screen.Settings) } }
    }
}

/** The newest chats the menu lists; the rest are under "All chats". */
const val MENU_CHATS_SHOWN = 8

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MenuRow(
    title: String, modifier: Modifier = Modifier, right: String = "", badge: Int = 0, badgeColor: Color = MenuColors.AmberDeep,
    dim: Boolean = false, rightColor: Color = MenuColors.Dim, ellipsize: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .combinedClickable(role = Role.Button, onLongClickLabel = if (onLongClick != null) "Menu for this one" else null, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 20.dp)
            .semantics(mergeDescendants = true) {
                if (badge > 0) stateDescription = "$badge"
            },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title, style = RowText, color = if (dim) MenuColors.Dim else Palette.Text, maxLines = 1,
            overflow = if (ellipsize) TextOverflow.Ellipsis else TextOverflow.Clip, modifier = Modifier.weight(1f, fill = !ellipsize || right.isEmpty()),
        )
        if (badge > 0) Box(Modifier.size(22.dp).clip(CircleShape).background(badgeColor), contentAlignment = Alignment.Center) {
            Text("$badge", style = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold), color = Palette.AmberInk)
        }
        if (right.isNotEmpty()) Text(right, style = TextStyle(fontSize = 13.sp, lineHeight = 16.sp), color = rightColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Section(title: String, count: String, open: Boolean, amber: Boolean, onToggle: () -> Unit) {
    Column {
        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MenuColors.Border)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onToggle).padding(horizontal = 20.dp)
                .semantics(mergeDescendants = true) {
                    heading()
                    contentDescription = "$title, $count"
                    stateDescription = if (open) "Open" else "Folded"
                },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (open) Glyphs.Down else Glyphs.Next, null, tint = MenuColors.Dim, modifier = Modifier.size(16.dp))
            Text(title.uppercase(), style = Type.MenuHeading, color = MenuColors.Dim, modifier = Modifier.weight(1f))
            if (count.isNotEmpty()) {
                // "3 · 1 needs you": the part after the dot is amber.
                val shown = androidx.compose.ui.text.buildAnnotatedString {
                    append(count.substringBefore(" · "))
                    if (" · " in count) { append(" · "); pushStyle(androidx.compose.ui.text.SpanStyle(color = MenuColors.Amber)); append(count.substringAfter(" · ")); pop() }
                }
                Text(shown, style = TextStyle(fontSize = 13.sp, lineHeight = 16.sp), color = MenuColors.Dim)
            }
        }
    }
}

/**
 * The chat screen's top bar: the menu button (with what waits) at the left, who answers in the middle
 * (a tap opens [picker]), and a pencil for a new chat at the right. Never the app's name or an eye.
 */
@Composable
fun MenuTopBar(
    waiting: Int, answerer: String, online: Boolean?, onNewChat: (() -> Unit)?, answererDescription: String = "$answerer. Change who answers",
    answererColor: Color = Palette.Text, onAnswererHold: (() -> Unit)? = null,
    /** Instead of the list, the answerer does this (a Whisper running on the PC: it opens it). */
    instead: (() -> Unit)? = null,
    /** Beside the name (the Whisper's "via Tor" mark). */
    trailing: (@Composable () -> Unit)? = null,
    /** A tap on who answers opens the "Who should answer?" sheet (null: nothing to choose). */
    onPick: (() -> Unit)? = null,
    /** The "⋯" at the right (left of the pencil): the open chat's menu, only while a chat with messages is open. */
    onMore: (() -> Unit)? = null,
    picker: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    // Three parts: the menu button, who answers, and the right group ("⋯" and the pencil). Who answers is centred on
    // the screen when there is room, and otherwise takes all the room between the two sides, so it is never cut.
    androidx.compose.ui.layout.Layout(
        content = {
            Box { MenuButton(waiting) }
        Box(contentAlignment = Alignment.TopCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .let { m ->
                            if (picker == null && onPick == null) m
                            else if (onAnswererHold != null) m.combinedClickableRole({ if (instead != null) instead() else if (onPick != null) onPick() else open = true }, onAnswererHold)
                            else m.clickable(role = Role.Button) { if (instead != null) instead() else if (onPick != null) onPick() else open = true }
                        }
                        .padding(horizontal = 12.dp)
                        .semantics(mergeDescendants = true) { contentDescription = answererDescription },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (online != null) Dot(if (online) Palette.Green else Palette.TextDim, hollow = false)
                    Text(answerer.forDevice(), style = TextStyle(fontSize = 15.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), color = answererColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    trailing?.invoke()
                    if (picker != null || onPick != null) Icon(Glyphs.Down, null, tint = Palette.TextDim, modifier = Modifier.size(16.dp))
                }
            }
            if (picker != null) DropdownMenu(open, { open = false }, containerColor = Palette.Card) { picker { open = false } }
        }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onMore != null) Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onMore).semantics { contentDescription = CHAT_MENU },
                    contentAlignment = Alignment.Center,
                ) { Icon(Glyphs.More, null, tint = Palette.Text, modifier = Modifier.size(24.dp)) }
                if (onNewChat != null) IconTarget(Glyphs.NewChat, "New chat", onNewChat) else Box(Modifier.size(48.dp))
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val left = measurables[0].measure(androidx.compose.ui.unit.Constraints())
        val right = measurables[2].measure(androidx.compose.ui.unit.Constraints())
        val mid = measurables[1].measure(androidx.compose.ui.unit.Constraints(maxWidth = topBarMidMax(w, left.width, right.width)))
        val h = maxOf(left.height, mid.height, right.height, 52.dp.roundToPx())
        layout(w, h) {
            left.placeRelative(0, (h - left.height) / 2)
            right.placeRelative(w - right.width, (h - right.height) / 2)
            val x = topBarMidX(w, left.width, right.width, mid.width)
            mid.placeRelative(x, (h - mid.height) / 2)
        }
    }
}

/** The room who-answers may take: everything between the menu button and the right group. */
fun topBarMidMax(w: Int, left: Int, right: Int) = (w - left - right).coerceAtLeast(0)

/** Where who-answers starts: centred on the bar when it fits, else pushed against the nearer side, never under a button. */
fun topBarMidX(w: Int, left: Int, right: Int, mid: Int) = ((w - mid) / 2).coerceIn(left, (w - right - mid).coerceAtLeast(left))

/** What the "⋯" says to TalkBack: the menu of things about this chat. */
const val CHAT_MENU = "This chat's menu"

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableRole(onClick: () -> Unit, onLong: () -> Unit): Modifier =
    this.combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLong)

/**
 * The menu button (two lines) with a small amber count of what waits; nothing while the menu is always open.
 * It keeps its 48 dp place either way, so the bar's middle never moves.
 */
@Composable
fun MenuButton(waiting: Int = LocalMenuCount.current) {
    val permanent = LocalPermanentMenu.current
    val openMenu = LocalOpenMenu.current
    Box(Modifier.size(48.dp)) {
        if (!permanent) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = openMenu)
                    .semantics { contentDescription = if (waiting > 0) "$MENU_BUTTON, $waiting waiting" else MENU_BUTTON },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyphs.Menu, null, tint = Palette.Text, modifier = Modifier.size(24.dp)) }
            if (waiting > 0) Box(
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp).sizeIn(minWidth = 18.dp, minHeight = 18.dp).clip(CircleShape).background(MenuColors.AmberDeep),
                contentAlignment = Alignment.Center,
            ) { Text("$waiting", style = TextStyle(fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold), color = Palette.AmberInk, modifier = Modifier.padding(horizontal = 4.dp)) }
        }
    }
}

/**
 * The search box takes focus only once it is tapped (QA2 #31, #35): a drawer or side list that opens
 * with the keyboard up hides the very chats it lists.
 */
@Composable
fun Modifier.focusOnlyWhenTapped(): Modifier {
    var tapped by remember { mutableStateOf(false) }
    return this
        .focusProperties { canFocus = tapped }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                tapped = true
            }
        }
}
