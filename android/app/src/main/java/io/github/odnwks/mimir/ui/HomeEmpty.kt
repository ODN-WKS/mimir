package io.github.odnwks.mimir.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle as DayStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The phone's empty chat (redesign R4): the very first time a hello that says plainly it is private;
 * every day after, only the question, a few starters and the box.
 */

/** Who is answering this chat: the words of the first hello follow it, so they stay true. */
enum class Answerer { Phone, Pc, Claude, Online }

/** The first hello shows only while no chat exists yet and no message was ever sent on this phone. */
fun isFirstOpen(hasChatted: Boolean, anyChat: Boolean): Boolean = !hasChatted && !anyChat

const val HELLO = "Hi, I'm Mímir."
const val EVERYDAY_LEAD = "What can I help with?"
private const val INTERNET_LINE = "When I need the internet, like for the weather, I look things up discreetly: only the few words needed, with no name or account attached."

fun firstOpenLead(a: Answerer, online: io.github.odnwks.mimir.online.Service? = null): String = when (a) {
    Answerer.Online -> "Ask me anything. ${online?.name ?: "An online model"} answers."

    Answerer.Phone -> "Ask me anything. Private by design."
    Answerer.Pc -> "Ask me anything. Private by design."
    Answerer.Claude -> "Ask me anything. Online answers, through your PC."
}

fun firstOpenFine(a: Answerer, online: io.github.odnwks.mimir.online.Service? = null): String = when (a) {
    Answerer.Online -> "${online?.name ?: "An online model"} answers this chat, so your messages go to ${online?.company ?: "that company"}."

    Answerer.Phone -> "Your chats stay on this phone. Nobody else can see them. $INTERNET_LINE"
    Answerer.Pc -> INTERNET_LINE
    Answerer.Claude -> "An online model answers this chat, so your messages go to Anthropic."
}

/** What tapping a starter does. */
sealed interface StarterAction {
    data class Send(val text: String) : StarterAction
    data object OpenUpdates : StarterAction
    data class OpenChat(val id: String) : StarterAction
}

data class Starter(val title: String, val sub: String, val action: StarterAction)

/** "yesterday", "Tue", "Nov 9": when a chat was last used. */
fun dayWord(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (at <= 0) return null
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val ago = java.time.temporal.ChronoUnit.DAYS.between(day, today)
    return when {
        ago <= 0 -> "earlier today"
        ago == 1L -> "yesterday"
        ago < 7 -> day.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.getDefault())
        else -> day.month.getDisplayName(DayStyle.SHORT, Locale.getDefault()) + " " + day.dayOfMonth
    }
}

/** The starters above the box: the first open's three, or only the personal ones that apply. */
fun startersFor(first: Boolean, paired: Boolean, unheard: Int, lastTitle: String?, lastChatId: String?, lastAt: Long = 0, now: Long = System.currentTimeMillis()): List<Starter> {
    val weather = Starter("What's the weather?", "Today and tomorrow", StarterAction.Send("What's the weather?"))
    if (first) return listOf(
        weather,
        Starter("Explain it simply", "Anything, in plain words", StarterAction.Send("Explain it simply")),
        Starter("Help me write", "A message or a note", StarterAction.Send("Help me write")),
    )
    return listOfNotNull(
        Starter("Read me my updates", "$unheard new", StarterAction.OpenUpdates).takeIf { unheard > 0 },
        if (lastTitle != null && lastChatId != null) Starter(lastTitle, dayWord(lastAt, now)?.let { "Carry on from $it" } ?: "Carry on", StarterAction.OpenChat(lastChatId)) else null,
        Starter("What's running?", "On your PC", StarterAction.Send("What's running?")).takeIf { paired },
        weather,
    ).distinctBy { it.title.trim().lowercase() } // a last chat named like a starter shows once (the chat wins)
}

/** The amber pill shows while something needs the owner, unless it was swiped away for this very count. */
fun pillVisible(count: Int, dismissedFor: Int): Boolean = count > 0 && count != dismissedFor

private val PillFill = Color(0xFF2A2213)
private val PillBorder = Color(0xFF6B5420)
private val PillText = Color(0xFFF3C969)
private val StarterFill = Color(0xFF1A1D23)
private val StarterBorder = Color(0xFF2B2F37)
private val LeadColor = Color(0xFFC3C8D1)
private val FineColor = Color(0xFF9AA1AD)

/** The first hello's eye: about 250dp, smaller only when the screen is short so the words still fit. */
fun helloEyeDp(availableDp: Float, fontScale: Float): Float {
    val textDp = 212f * fontScale + 18f // hello, lead, small line and the gaps, at their tallest
    return (availableDp - textDp).coerceIn(64f, 250f)
}

/** The middle of the empty phone chat. [first]: the hello; otherwise the question. [keyboardOpen]: the eye and small line step aside. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeEmpty(
    first: Boolean, answerer: Answerer, modifier: Modifier = Modifier, online: io.github.odnwks.mimir.online.Service? = null,
    keyboardOpen: Boolean = androidx.compose.foundation.layout.WindowInsets.isImeVisible,
) {
    val startup = LocalEyeStartup.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val eye = helloEyeDp(maxHeight.value, LocalDensity.current.fontScale)
        // The first hello: the words wait for the eye's last act. Everyday: no eye, so the words are simply there.
        val words = Modifier.startupWords(startup, on = first)
        Column(Modifier.fillMaxSize().padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (first) {
                if (!keyboardOpen) {
                    if (startup.used) StartupEye(startup, Modifier.size(eye.dp).testTag("hello-eye"))
                    else MimirEye(EyeMode.Normal, Modifier.size(eye.dp).testTag("hello-eye"))
                    Box(Modifier.size(8.dp))
                }
                Text(HELLO, style = Type.Hello, color = Palette.Text, textAlign = TextAlign.Center, modifier = words.semantics { heading() })
                Box(Modifier.size(10.dp))
                Text(firstOpenLead(answerer, online), style = Type.Body.copy(fontSize = 16.sp, lineHeight = 23.sp), color = LeadColor, textAlign = TextAlign.Center, modifier = words)
                if (!keyboardOpen) {
                    Box(Modifier.size(12.dp))
                    Text(firstOpenFine(answerer, online), style = Type.Body.copy(fontSize = 13.5.sp, lineHeight = 20.sp), color = FineColor, textAlign = TextAlign.Center, modifier = words)
                }
            } else {
                Text(EVERYDAY_LEAD, style = Type.Hello.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), color = Palette.Text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            }
        }
        // The everyday screen has no eye: on a fresh start the eye plays over it, in the hello's place, then fades into the question.
        if (!first && startup.playing) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = overlayAlpha(startup.t) }.background(Palette.Ground).testTag("startup-overlay"),
                contentAlignment = Alignment.Center,
            ) { StartupEye(startup, Modifier.size(eye.dp)) }
        }
    }
}

/** The starters: one row just above the box, scrolling sideways. */
@Composable
fun StarterRow(starters: List<Starter>, onTap: (Starter) -> Unit, modifier: Modifier = Modifier) {
    if (starters.isEmpty()) return
    val shape = RoundedCornerShape(16.dp)
    LazyRow(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        itemsIndexed(starters, key = { i, st -> "$i|${st.title}" }) { _, st ->
            Column(
                Modifier.heightIn(min = 48.dp).clip(shape).background(StarterFill).border(1.dp, StarterBorder, shape)
                    .clickable(role = Role.Button) { onTap(st) }
                    .semantics(mergeDescendants = true) { contentDescription = "${st.title}. ${st.sub}" }
                    .padding(horizontal = 13.dp, vertical = 10.dp),
            ) {
                Text(st.title, style = Type.Small.copy(fontSize = 13.5.sp, lineHeight = 18.sp), color = Palette.Text, maxLines = 1)
                Text(st.sub, style = Type.Small.copy(fontSize = 13.sp, lineHeight = 16.sp), color = FineColor, maxLines = 1)
            }
        }
    }
}

/**
 * The amber pill under the top bar: "1 thing needs you ›". A tap opens Needs you; a swipe sideways
 * dismisses it (until the count changes, which the caller keeps in [dismissedFor]).
 */
@Composable
fun NeedsYouPill(count: Int, dismissedFor: Int, onDismiss: (Int) -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    // The last count stays on the pill while it fades.
    val kept = remember { intArrayOf(count) }
    if (count > 0) kept[0] = count
    val shown = pillVisible(count, dismissedFor)
    AnimatedVisibility(shown, modifier.fillMaxWidth()) {
        var drag by remember { mutableFloatStateOf(0f) }
        val limit = with(LocalDensity.current) { 72.dp.toPx() }
        val shape = RoundedCornerShape(22.dp)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.offset { IntOffset(drag.roundToInt(), 0) }.heightIn(min = 48.dp)
                    .pointerInput(count) { detectHorizontalDragGestures(onDragEnd = { if (abs(drag) > limit) onDismiss(count) else drag = 0f }, onDragCancel = { drag = 0f }) { _, d -> drag += d } }
                    .clip(shape).background(PillFill).border(1.dp, PillBorder, shape)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite; contentDescription = needsYouWords(kept[0]) + ". Swipe sideways to hide" }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(needsYouWords(kept[0]), style = Type.Small.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), color = PillText)
                Text("›", style = Type.Small.copy(fontSize = 13.5.sp), color = PillText)
            }
        }
    }
}

/** Under the box, first open and unpaired only: a way to the PC, and a ✕ that hides it for good. */
@Composable
fun ConnectHint(onConnect: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text("Have a PC with $AI_NAME? ", style = Type.Small, color = FineColor)
        LinkText("Connect it", onConnect)
        IconTarget(Glyphs.Close, "Hide this", onHide, tint = FineColor)
    }
}
