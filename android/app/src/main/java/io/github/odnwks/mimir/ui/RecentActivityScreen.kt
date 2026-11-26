package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.LocalState
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import java.util.Calendar

/*
 * Recent activity (P17, owner 2026-11-25, mock-up 10): it takes the place of Updates in the side menu.
 * Every conversation, newest first: chats and project tasks together, each in its own two-line bubble.
 */

const val MENU_RECENT = "Recent activity"
const val RECENT_TITLE = "Recent activity"
const val RECENT_TODAY = "TODAY"
const val RECENT_EARLIER = "EARLIER"
const val RECENT_EMPTY = "Nothing yet. Your chats and project work show here."

/** What colour an item's state is: green while it works, amber when it needs the owner, else dim. */
enum class RecentTone { Working, Needs, Dim }

/**
 * One conversation on the Recent activity page. [project] is null for a chat (its second line starts
 * "Chat"); [words] are the first words of its first message; [at] is when it last changed (ms since 1970).
 */
data class RecentItem(
    val key: String, val name: String, val project: String?, val words: String, val at: Long,
    val right: String, val tone: RecentTone, val task: String? = null, val chat: MenuChat? = null,
)

/** The first words of a task's first message (one line), or "" when it has none. */
fun firstWords(t: Task?): String =
    t?.messages?.firstOrNull { !it.fromClaude && !it.note }?.text.orEmpty().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/** How many tasks work right now: the menu row's "N working". */
fun recentWorking(state: StationState): Int = state.tasks.count { !it.whisper && it.stateWords in WORKING_WORDS }

private val WORKING_WORDS = setOf("Working", "Starting")

/** A task's state in the page's words: "working", "needs you", else its own word ("done", "stopped"). */
private fun taskRight(t: Task): Pair<String, RecentTone> = when (t.stateWords) {
    in WORKING_WORDS -> "working" to RecentTone.Working
    "Needs you" -> "needs you" to RecentTone.Needs
    else -> t.stateWords.lowercase() to RecentTone.Dim
}

/** How long ago a chat last changed, in a few letters: "now", "5 min", "3 h" today, then "yesterday", the weekday or the date. */
fun recentAgo(at: Long, now: Long): String {
    if (at <= 0) return ""
    val mins = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        !recentIsToday(at, now) -> menuTime(at, now)
        mins < 1 -> "now"
        mins < 60 -> "$mins min"
        else -> "${mins / 60} h"
    }
}

/** Whether [at] is on the same day as [now] on this phone's clock. */
fun recentIsToday(at: Long, now: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = at }
    val b = Calendar.getInstance().apply { timeInMillis = now }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

/** Every conversation, newest first: this phone's chats, the PC's chats and the project tasks (no Whisper). */
fun recentActivity(state: StationState, local: LocalState?, now: Long): List<RecentItem> {
    val chats = menuChats(state, local, now).map { c ->
        val at = if (c.open && c.at <= 0) now else c.at
        val words = if (c.mimir) {
            val chat = local?.let { l -> (listOf(l.chat) + l.earlier).firstOrNull { it.id == c.id } }
            chat?.turns?.firstOrNull { it.user }?.text.orEmpty()
        } else state.pastChats.firstOrNull { it.id == c.id }?.let { p -> p.first.ifEmpty { p.words } }.orEmpty()
        RecentItem("chat:${c.id}", c.title.ifBlank { "New chat" }, null, words.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty(), at, recentAgo(at, now), RecentTone.Dim, chat = c)
    }
    val tasks = state.tasks.filter { !it.whisper }.map { t ->
        val (right, tone) = taskRight(t)
        val at = maxOf(t.activeUnix, t.createdUnix).let { if (it > 0) it * 1000 else now }
        RecentItem("task:${t.id}", t.title, t.project, firstWords(t), at, right, tone, task = t.id)
    }
    return (chats + tasks).sortedByDescending { it.at }
}

/** Opens a chat from a list: this phone's own, or one kept on the PC (the menu's rule). */
fun openListedChat(c: MenuChat, state: StationState, engine: Engine?, station: Station, nav: Navigator) {
    val one = !state.paired || state.pcTalks
    if (c.mimir) {
        if (!c.open) engine?.open(c.id)
        if (state.paired && !one) nav.open(Screen.Standalone) else nav.selectTab(Tab.Chat)
    } else {
        if (!c.open) station.openPastChat(c.id)
        nav.selectTab(Tab.Chat)
    }
}

/** The page's colours (mock-up 10). */
private object RecentColors {
    val Project = Color(0xFF9DB8EE)
    val Green = Color(0xFF7FD69A)
    val Amber = Color(0xFFF2C46D)
    val Dim = Color(0xFF8A8F98)
}

/** The Recent activity page: the title with the menu button, then TODAY and EARLIER. */
@Composable
fun RecentActivityScreen(state: StationState, station: Station, engine: Engine?, nav: Navigator, now: Long = System.currentTimeMillis()) {
    val local = engine?.state?.collectAsStateWithLifecycle()?.value
    val items = recentActivity(state, local, now)
    val tap = LocalTapTracker.current
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuButton()
            Text(RECENT_TITLE, style = ScreenTitleStyle, color = Palette.Text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { heading() })
            Spacer(Modifier.size(48.dp))
        }
        if (items.isEmpty()) {
            Text(
                RECENT_EMPTY, style = Type.Body, color = Palette.TextSoft, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            )
            return@Column
        }
        val (today, earlier) = items.partition { recentIsToday(it.at, now) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
            for ((head, list) in listOf(RECENT_TODAY to today, RECENT_EARLIER to earlier)) {
                if (list.isEmpty()) continue
                item("head-$head") { CapsLabel(head, Modifier.padding(start = 2.dp, top = 8.dp, bottom = 4.dp)) }
                items(list, key = { it.key }) { item ->
                    RecentBubble(item) {
                        if (item.task != null) nav.open(Screen.Task(item.task), tap.last)
                        else item.chat?.let { openListedChat(it, state, engine, station, nav) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentBubble(item: RecentItem, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.padding(vertical = 3.dp).fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(Palette.Card).border(1.dp, Palette.Line, shape)
            .clickable(role = Role.Button, onClickLabel = "Open", onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.name, style = TextStyle(fontSize = 16.sp, lineHeight = 21.sp), color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (item.right.isNotEmpty()) Text(
                item.right, style = TextStyle(fontSize = 13.sp, lineHeight = 17.sp), maxLines = 1,
                color = when (item.tone) { RecentTone.Working -> RecentColors.Green; RecentTone.Needs -> RecentColors.Amber; RecentTone.Dim -> RecentColors.Dim },
            )
        }
        val line = buildAnnotatedString {
            if (item.project != null) { pushStyle(SpanStyle(color = RecentColors.Project)); append(item.project); pop() } else append("Chat")
            if (item.words.isNotEmpty()) { append(" · "); append(item.words) }
        }
        Text(line, style = TextStyle(fontSize = 13.sp, lineHeight = 17.sp), color = RecentColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
