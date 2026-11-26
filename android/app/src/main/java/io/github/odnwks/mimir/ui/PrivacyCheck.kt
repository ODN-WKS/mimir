package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.data.NetUse
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import kotlinx.coroutines.delay

const val AIRPLANE_LINE = "Turn on Airplane mode: $AI_NAME and Whisper keep working without the internet. Only lookups (like the weather) need it, and a Whisper never looks anything up."
const val NOTHING_SENT = "Nothing sent during this chat"

/** How often the counts on screen are brought up to date. */
private const val LOOK_MS = 2_000L

/**
 * The open Mímir or Whisper chat, for its live line: what this app had sent when the chat opened.
 * Null while no such chat is open.
 */
object OpenChat {
    var sentAtOpen by mutableStateOf<Long?>(null)
}

/** Marks a Mímir or Whisper chat ([key]) as open while it's on screen. */
@Composable
fun WatchWhatThisChatSends(key: Any) {
    DisposableEffect(key) {
        OpenChat.sentAtOpen = NetUse.sentNow() ?: -1L
        onDispose { OpenChat.sentAtOpen = null }
    }
}

/** "Nothing sent during this chat", kept up to date while it shows (or what the app did send). */
@Composable
fun ChatSentLine(modifier: Modifier = Modifier, pc: Boolean = false) {
    val start = OpenChat.sentAtOpen ?: return
    var sent by remember { mutableStateOf(0L) }
    LaunchedEffect(start) {
        while (true) {
            sent = NetUse.sentNow()?.let { now -> if (start < 0 || now < start) 0L else now - start } ?: 0L
            delay(LOOK_MS)
        }
    }
    Row(
        modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(if (sent == 0L) Glyphs.Check else Glyphs.Info, null, tint = if (sent == 0L) Palette.Green else Palette.TextDim, modifier = Modifier.size(22.dp))
        Text(
            if (sent == 0L) NOTHING_SENT
            else "${sizeWords(sent)} sent by this app while this chat was open: " +
                (if (pc) "your messages go only to your PC" else "your messages stay on the phone") + "; lookups are listed in Settings › Privacy",
            style = Type.Small, color = Palette.TextSoft, modifier = Modifier.weight(1f),
        )
    }
}

/** The Settings row's line: what this app has sent, in a few words. */
fun privacyLine(u: NetUse.Use): String =
    if (!u.counted) "See exactly what this app has sent"
    else "See exactly what this app has sent: ${sizeWords(u.sinceReset.sent)} since the last clear"

/** Lookups made since this morning (the log's own entries). */
fun lookupsToday(entries: List<io.github.odnwks.mimir.online.LookupLog.Entry>, now: Long = System.currentTimeMillis()): List<io.github.odnwks.mimir.online.LookupLog.Entry> {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
    return entries.filter { it.at >= cal.timeInMillis }
}

fun lookupsTodayWords(n: Int) = if (n == 1) "1 lookup today" else "$n lookups today"
fun stayedWords(paired: Boolean) = "Everything else stayed between this phone and your PC.".takeIf { paired } ?: "Everything else stayed on this phone."
const val PRIVACY_WENT_OUT = "WHAT WENT OUT"
const val PRIVACY_HOW = "HOW IT WORKS"
const val CLEAR_THIS_LIST = "Clear this list"

/**
 * Settings › Privacy: a card with today's lookups, the lookups themselves (the words, the site, the
 * time), how chats and Claude's answers travel, and below them the byte counts and the other
 * privacy switches. "Clear this list" starts the list and the counts again.
 */
@Composable
fun PrivacyCheckScreen(state: StationState, nav: Navigator) {
    val u by NetUse.use.collectAsStateWithLifecycle()
    val all by io.github.odnwks.mimir.online.LookupLog.entries.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { NetUse.sample(); delay(LOOK_MS) } }
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader("Privacy", nav::back)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel(spacing = 4.dp, padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
                Text(lookupsTodayWords(lookupsToday(all).size), style = Type.Section.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 24.sp), color = Palette.Text, modifier = Modifier.semantics { heading() })
                Text(stayedWords(state.paired).forDevice(), style = Type.Label, color = Palette.TextSoft)
            }
            // The switches for looking things up, and the hidden route, live here and nowhere else.
            LookupSettings(Modifier.padding(top = 4.dp))
            CapsLabel("LOCK")
            AppLockSetting()
            CapsLabel(PRIVACY_WENT_OUT)
            if (all.isEmpty()) Text("Nothing yet. When $AI_NAME looks something up for you (like the weather), it is listed here with the words that were sent.", style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp))
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
            all.take(30).forEach { e ->
                ListRow(
                    if (e.words.isNotEmpty()) "\u201c${e.words}\u201d" else "A lookup", "to " + e.site + " \u00b7 " + time.format(java.util.Date(e.at)) + " \u00b7 no name or account",
                    null, trailing = null,
                )
            }
            if (all.size > 30) Text("and ${all.size - 30} earlier", style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp))
            CapsLabel(PRIVACY_HOW)
            Panel(spacing = 4.dp) {
                Text("Your chats", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                Text(
                    (if (state.paired) "Go only between this phone and your PC, kept private. Chats on this phone's own model never leave it."
                    else "Stay on this phone. Nothing is sent anywhere to answer them.").forDevice(),
                    style = Type.Small, color = Palette.TextSoft,
                )
            }
            Panel(spacing = 4.dp) {
                Text("When online answers", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                Text(
                    if (state.paired) "That message goes from your PC to Anthropic. Answers from this phone or your PC's models do not."
                    else "Online answers go through your PC. Until one is connected, nothing goes to Anthropic.",
                    style = Type.Small, color = Palette.TextSoft,
                )
            }
            if (all.isNotEmpty() || u.counted) BigButton(CLEAR_THIS_LIST, Look.Outline, { NetUse.reset(); io.github.odnwks.mimir.online.LookupLog.clear() }, height = 48.dp, description = "Clear the list of lookups and start counting again")
            CapsLabel("THE NUMBERS")
            Text(
                "Everything this app sent and received over the network, as this phone counts it.".forDevice(),
                style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.45f), color = Palette.TextSoft,
            )
            EndWhispersOnLeave()
            if (!u.counted) Panel { Text("This phone doesn't report how much an app sends, so the numbers aren't shown. Check Android's own data usage screen instead.".forDevice(), style = Type.Label, color = Palette.TextSoft) }
            else {
                val date = { ms: Long -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms)) }
                val same = u.resetAt == 0L || u.sinceReset == u.sinceInstall
                if (same) Counts(
                    if (u.since == 0L) "Since counting began" else "Since ${date(u.since)}", u.sinceInstall, u.downloads, u.downloadsSent, state.paired,
                ) { SmallButton("Reset", { NetUse.reset(); io.github.odnwks.mimir.online.LookupLog.clear() }, description = "Start counting again from now") }
                else {
                    Counts(if (u.since == 0L) "Since counting began" else "Since ${date(u.since)}", u.sinceInstall, u.downloads, u.downloadsSent, state.paired)
                    Counts(
                        "Since ${date(u.resetAt)}", u.sinceReset, u.downloadsSinceReset, u.downloadsSentSinceReset, state.paired,
                    ) { SmallButton("Reset", { NetUse.reset(); io.github.odnwks.mimir.online.LookupLog.clear() }, description = "Start counting again from now") }
                }
                Text(
                    (if (state.paired) "With your PC paired, the connection to it stays open: it sends a little now and then to stay in touch, kept private."
                    else "Not paired with a PC, the app uses the network only for downloads you start (a model or a voice) and for $AI_NAME's lookups, listed above. Your chats are never sent.").forDevice(),
                    style = Type.Small.copy(lineHeight = Type.Small.fontSize * 1.45f), color = Palette.TextDim,
                )
            }
            Panel(spacing = 6.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Glyphs.Shield, null, tint = Palette.BlueText, modifier = Modifier.size(22.dp))
                    Text(AIRPLANE_LINE, style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.45f), color = Palette.TextSoft)
                }
            }
        }
    }
}

/** One period's sent and received, and what the received was for where it's known. */
@Composable
private fun Counts(title: String, b: NetUse.Bytes, downloads: Long, downloadsSent: Long, paired: Boolean, action: (@Composable () -> Unit)? = null) {
    Panel(spacing = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.weight(1f).semantics { heading() })
            action?.invoke()
        }
        val rest = if (paired) "  Your PC and everything else" else "  Everything else"
        Line("Sent", b.sent)
        val up = downloadsSent.coerceAtMost(b.sent)
        if (up > 0) Line("  Downloads you started", up, dim = true)
        Line(rest, (b.sent - up).coerceAtLeast(0), dim = true)
        Line("Received", b.received)
        val dl = downloads.coerceAtMost(b.received)
        if (dl > 0) Line("  Downloads you started", dl, dim = true)
        Line(rest, (b.received - dl).coerceAtLeast(0), dim = true)
    }
}

@Composable
private fun Line(label: String, bytes: Long, dim: Boolean = false) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = Type.Label, color = if (dim) Palette.TextDim else Palette.TextSoft, modifier = Modifier.weight(1f))
        Text(sizeWords(bytes), style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = if (dim) Palette.TextDim else Palette.Text)
    }
}

/** "End Whispers when I leave the app": off unless chosen. */
@Composable
private fun EndWhispersOnLeave() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = androidx.compose.runtime.remember { ctx.getSharedPreferences(PHONE_CHAT_PREFS, 0) }
    var on by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean(END_ON_LEAVE, false)) }
    Panel(padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)) {
        SwitchRow(on, { on = it; prefs.edit().putBoolean(END_ON_LEAVE, it).apply() }, Modifier.heightIn(min = 56.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("End Whispers when I leave the app", style = Type.BodyTight, color = Palette.Text)
                Text("An open Whisper is forgotten as soon as the app goes off screen (opening the camera or a file counts too)", style = Type.Small, color = Palette.TextDim)
            }
        }
    }
}
