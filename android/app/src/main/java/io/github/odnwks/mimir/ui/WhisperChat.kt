package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/*
 * The eye in the chat's top bar shows the mode the chat is in (owner, 2026-11-19): open in normal
 * mode, closed with the scar in a Whisper. Its words say the mode first, then what a tap does.
 * A third mode, a Whisper with internet (closed eye plus a small globe), is designed in
 * docs/designs/phone-app/mimir_online.md and joins [EyeMode] when it's built.
 */

/** Normal mode on this phone's own chat. */
const val START_WHISPER = "Normal mode: $AI_NAME, with your rules for every chat on, and chats are kept. Tap for your chats, a new chat or a new Whisper: a private, incognito chat on this phone that's never kept"
/** A Whisper on this phone. */
const val END_WHISPER = "Whisper mode: private, offline, nothing kept. Tap for your chats, a new chat (this Whisper is forgotten) or a new Whisper"

/** A Whisper with internet on this phone. */
const val END_WEB_WHISPER = "$WHISPER_O, a Whisper online: lookups go by a hidden route, nothing kept. Tap for your chats, a new chat (this Whisper is forgotten) or a new Whisper"

/** The three modes the eye shows. */
enum class EyeMode {
    /** Open eye: the usual chat, ground rules in force. */
    Normal,
    /** Closed eye with the scar: private, offline, nothing kept. */
    Whisper,
    /** The same closed eye with a small globe: lookups only, through Tor; nothing kept. */
    WhisperWeb,
}

/** The short words a long press on the eye shows (TalkBack hears the full label). */
private fun EyeMode.short() = when (this) {
    EyeMode.Normal -> "Normal mode · tap for chats, a new chat or a Whisper"
    EyeMode.Whisper -> "Whisper: private, offline, nothing kept · tap for chats or a new chat"
    EyeMode.WhisperWeb -> "$WHISPER_O: online by a hidden route, nothing kept · tap for chats or a new chat"
}

/**
 * The eye, always in the chat's top bar, showing the mode: open in normal mode, closed with the
 * scar in a teal circle in a Whisper. A tap switches; the eye changes with a short fade.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WhisperButton(
    on: Boolean, onClick: () -> Unit, label: String = if (on) END_WHISPER else START_WHISPER.forDevice(), web: Boolean = false,
    /** A long press in a Whisper (about half a second): the emergency exit. Null: none here. */
    onHold: (() -> Unit)? = null,
) {
    val mode = when { on && web -> EyeMode.WhisperWeb; on -> EyeMode.Whisper; else -> EyeMode.Normal }
    val circle by androidx.compose.animation.animateColorAsState(
        if (on) Palette.WhisperChip else Palette.WhisperChip.copy(alpha = 0f),
        io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.SCREEN_MS), label = "eye-circle",
    )
    val eye: @Composable () -> Unit = {
        androidx.compose.animation.Crossfade(
            mode, label = "eye",
            animationSpec = io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.SCREEN_MS),
        ) { m ->
            MimirEye(m, Modifier.size(28.dp))
        }
    }
    val box = Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(circle)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // In a Whisper with an emergency exit, a long press is that exit, so the tooltip (also a long
    // press) isn't offered; the box keeps the same shape either way.
    val exit = on && onHold != null
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(mode.short()) } },
        state = androidx.compose.material3.rememberTooltipState(),
        enableUserInput = !exit,
    ) {
        androidx.compose.foundation.layout.Box(
            box.combinedClickable(
                role = androidx.compose.ui.semantics.Role.Button, onClick = onClick,
                onLongClickLabel = if (exit) EMERGENCY_EXIT else null,
                onLongClick = if (exit) ({ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onHold?.invoke(); Unit }) else null,
            ).semantics { contentDescription = label },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { eye() }
    }
}

/** What a long press on the eye does in a Whisper (also its screen-reader action). */
const val EMERGENCY_EXIT = "End this Whisper and leave the app at once"

/** The eye menu's first row in a Whisper. */
const val END_WHISPER_ROW = "End Whisper"
/** The same, in a Whisper online. */
const val END_WEB_WHISPER_ROW = "End Whisper-O"
const val END_WHISPER_ABOUT = "Forget this Whisper now, no question asked"

/** Shown on the fresh chat for about two seconds after one tap on End Whisper. */
const val WHISPER_ENDED = "Whisper ended. Nothing was kept."

/** The eye on the paired chat: a Whisper on this phone or on the PC, whichever there is. */
const val START_WHISPER_PAIRED = "Normal mode: Mímir, with internet, and chats are kept. Tap for your chats, a new chat or a new Whisper: a private, incognito chat that's never kept"
const val END_PC_WHISPER = "Whisper mode: your PC's free model, internet cut off, nothing kept. Tap for your chats, a new chat (back to normal) or a new Whisper"
const val OPEN_PC_WHISPER = "Whisper mode: a Whisper is running on your PC. Tap to open it"
const val WHISPER_ON_PHONE = "Whisper on this phone"
const val WHISPER_ON_PC = "Whisper on your PC"

/** Where a paired phone can start a Whisper. */
enum class WhisperPlace { Phone, Pc }

/** Where a Whisper starts when asked for from the side menu or the eye. */
enum class WhisperStart { Pc, Phone, NeedsModel, Nowhere }

/**
 * Paired with a free model on the PC, the PC (it is never kept anywhere); this phone's own when the PC is away and the
 * phone can answer; when only the phone can, the one-time download is offered; otherwise there's nowhere to start one.
 */
fun whisperStartFor(paired: Boolean, pcFree: Boolean, pcOnline: Boolean, phoneReady: Boolean, phoneSupported: Boolean): WhisperStart = when {
    paired && pcFree && (pcOnline || !phoneReady) -> WhisperStart.Pc
    phoneReady -> WhisperStart.Phone
    phoneSupported -> WhisperStart.NeedsModel
    else -> WhisperStart.Nowhere
}

const val WHISPER_NOWHERE = "A Whisper needs a model on your PC, or $AI_NAME on this phone. Neither is ready."
const val WHISPER_WEB_PHONE_ONLY = "A Whisper with internet runs on this phone, and this phone can't run $AI_NAME."

/** The places a Whisper can start right now: [phone] when Mímir is on it, [pc] when the PC has a free model. */
fun whisperPlaces(phone: Boolean, pc: Boolean): List<WhisperPlace> =
    listOfNotNull(WhisperPlace.Phone.takeIf { phone }, WhisperPlace.Pc.takeIf { pc })

/** Paired, with both a Whisper on this phone and one on the PC: one choice, then it starts. */
@Composable
fun WhisperWhere(open: Boolean, onPhone: () -> Unit, onPc: () -> Unit, onClose: () -> Unit) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.WhisperPanel,
        title = { Text("Start a Whisper", style = Type.Section, color = Palette.WhisperTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WhisperChoice(WHISPER_ON_PHONE.forDevice(), "$AI_NAME answers here. Nothing leaves this phone.".forDevice(), Glyphs.Lock, onPhone)
                WhisperChoice(WHISPER_ON_PC, "Your PC's free model, with the internet cut off. Nothing is kept.", Glyphs.Shield, onPc)
            }
        },
        confirmButton = { SmallButton("Not now", onClose) },
    )
}

@Composable
private fun WhisperChoice(title: String, about: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListRow(
        title, about, onClick, background = Palette.WhisperCard,
        leading = { Icon(icon, null, tint = Palette.Whisper, modifier = Modifier.size(22.dp)) },
    )
}

/** Paired, with no Whisper possible anywhere: what's needed, and the way to each. */
@Composable
fun WhisperNeedsAModel(open: Boolean, phoneCanRun: Boolean, size: String?, onGetMimir: () -> Unit, onAddPc: () -> Unit, onClose: () -> Unit) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.WhisperPanel,
        title = { Text("A Whisper needs a private model", style = Type.Section, color = Palette.WhisperTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    (if (phoneCanRun) "Get $AI_NAME on this phone (a one-time download${size?.let { " of $it" } ?: ""}), or add a model on your PC. Either keeps a Whisper private."
                    else "Add a model on your PC: a Whisper runs there with the internet cut off, and nothing is kept.").forDevice(),
                    style = Type.BodyTight, color = Palette.WhisperSoft,
                )
                if (phoneCanRun) BigButton("Get $AI_NAME", Look.Whisper, onGetMimir, Modifier.fillMaxWidth(), height = 48.dp)
                BigButton("Add a model on your PC", if (phoneCanRun) Look.WhisperOutline else Look.Whisper, onAddPc, Modifier.fillMaxWidth(), height = 48.dp)
            }
        },
        confirmButton = { SmallButton("Not now", onClose) },
    )
}

/** The Whisper screen's own green tint (redesign R10). */
object WhisperTint {
    val Ground = androidx.compose.ui.graphics.Color(0xFF0B1513)
    val Card = androidx.compose.ui.graphics.Color(0xFF10201D)
    val Border = androidx.compose.ui.graphics.Color(0xFF1F4640)
    val Bubble = androidx.compose.ui.graphics.Color(0xFF17403A)
    val Send = androidx.compose.ui.graphics.Color(0xFF17403A)
}

const val WHISPER_NAME = "Whisper"
const val WHISPER_END = "End"
const val WHISPER_CARD_TITLE = "Nothing here is kept."
const val WHISPER_NET_SWITCH = "Use the internet (hidden route)"
/** What End does, said for a Whisper with internet and for one without (and where it is forgotten). */
fun whisperCardBody(web: Boolean, paired: Boolean): String {
    val forgotten = if (paired) "When you tap End, this chat is forgotten on this phone and on your PC." else "When you tap End, this chat is forgotten on this phone."
    return forgotten + if (web) " Lookups go by a hidden route, so websites see less about who is asking." else " This Whisper doesn't use the internet."
}
const val END_WHISPER_BUTTON = "End Whisper. Nothing of it is kept"

/**
 * The Whisper's top bar: the menu button, a centred teal "Whisper" with the closed eye, and a red
 * End. One tap on End ends it at once; a long press is the emergency exit (when [onHold] is given).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WhisperTopBar(onEnd: () -> Unit, onHold: (() -> Unit)? = null, waiting: Int = LocalMenuCount.current) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp).heightIn(min = 48.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.align(androidx.compose.ui.Alignment.CenterStart)) { MenuButton(waiting) }
        // Centred on the screen, whatever the buttons' widths.
        androidx.compose.foundation.layout.Row(
            Modifier.align(androidx.compose.ui.Alignment.Center), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(Glyphs.EyeClosed, null, tint = Palette.Whisper, modifier = Modifier.size(24.dp))
            Text(WHISPER_NAME, style = io.github.odnwks.mimir.ui.ScreenTitleStyle, color = Palette.Whisper, modifier = Modifier.semantics { heading() })
        }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        androidx.compose.foundation.layout.Box(
            Modifier.align(androidx.compose.ui.Alignment.CenterEnd).heightIn(min = 48.dp).widthIn(min = 48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .combinedClickable(
                    role = androidx.compose.ui.semantics.Role.Button, onClick = onEnd,
                    onLongClickLabel = if (onHold != null) EMERGENCY_EXIT else null,
                    onLongClick = if (onHold != null) ({ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onHold(); Unit }) else null,
                ).padding(horizontal = 12.dp).semantics { contentDescription = END_WHISPER_BUTTON },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { Text(WHISPER_END, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Red) }
    }
}

/**
 * The card at the top of a Whisper: nothing is kept, and whether it uses the internet. While the
 * Whisper is still empty ([onWeb] given) a switch picks lookups through Tor; it is off by default.
 */
@Composable
fun WhisperCard(web: Boolean, paired: Boolean, onWeb: ((Boolean) -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(WhisperTint.Card)
            .border16(WhisperTint.Border, 16).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(WHISPER_CARD_TITLE, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Whisper, modifier = Modifier.semantics { heading() })
        Text(whisperCardBody(web, paired), style = Type.Label, color = Palette.WhisperSoft)
        if (onWeb != null) SwitchRow(web, onWeb, on = Palette.WhisperSwitch) {
            Text(WHISPER_NET_SWITCH, style = Type.BodyTight, color = Palette.WhisperBody, modifier = Modifier.weight(1f))
        }
    }
}

/** An empty Whisper: what it is, once, where the conversation will be. */
@Composable
fun WhisperHello(web: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MimirEye(if (web) EyeMode.WhisperWeb else EyeMode.Whisper, Modifier.padding(bottom = 2.dp).size(32.dp))
        Text(if (web) "A $WHISPER_O" else "A Whisper", style = Type.Title, color = Palette.WhisperTitle, modifier = Modifier.semantics { heading() })
        Text(
            (if (web) "$AI_NAME answers on this phone and may look things up, only by a hidden route. Nothing of it is kept. " +
                "It ends, and is forgotten, when you start a new chat from the eye. Long-press the eye to end and leave at once."
            else "$AI_NAME answers on this phone, nothing is sent anywhere, and nothing of it is kept. " +
                "It ends, and is forgotten, when you start a new chat from the eye. Long-press the eye to end and leave at once.").forDevice(),
            style = Type.Label, color = Palette.WhisperSoft,
        )
    }
}

/** Ending a Whisper that has messages: one question, Cancel the main button. */
@Composable
fun EndWhisperQuestion(open: Boolean, onCancel: () -> Unit, onEnd: () -> Unit) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.WhisperPanel,
        title = { Text("End and forget this Whisper?", style = Type.Section, color = Palette.WhisperTitle) },
        text = { Text("Nothing of it is kept, on this phone or anywhere.".forDevice(), style = Type.BodyTight, color = Palette.WhisperSoft) },
        dismissButton = { SmallButton("End and forget", onEnd) },
        confirmButton = { BigButton("Keep whispering", Look.Primary, onCancel, height = 48.dp) },
    )
}

/** The eye with no model on the phone yet: what's needed, and the way to get it. */
@Composable
fun WhisperNeedsMimir(open: Boolean, size: String, onGet: () -> Unit, onClose: () -> Unit) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.WhisperPanel,
        title = { Text("Whisper needs $AI_NAME on this phone".forDevice(), style = Type.Section, color = Palette.WhisperTitle) },
        text = { Text("A Whisper runs only on this phone, so $AI_NAME has to be here first: a one-time download of $size.".forDevice(), style = Type.BodyTight, color = Palette.WhisperSoft) },
        dismissButton = { SmallButton("Not now", onClose) },
        confirmButton = { BigButton("Get $AI_NAME", Look.Primary, onGet, height = 48.dp) },
    )
}

/** The small "hidden route" chip beside the eye in a Whisper with internet. */
@Composable
fun ViaTorChip() {
    Text(
        "hidden route", style = Type.Small, color = Palette.WhisperTitle,
        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(Palette.WhisperChip)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** What a Whisper with internet can't promise (in its first notice and its hello). */
const val TOR_LIMITS = "Private enough for everyday questions, not for secrets. The site you search still sees what you search, " +
    "and what you search can say who you are (a place, a name). " +
    "Lookups are slower, some sites block this route, and a page could try to mislead $AI_NAME."

/** The first time a Whisper with internet starts: Tor's real connect time on this phone, said once. */
@Composable
fun TorNotice(connectMs: Long, onOk: () -> Unit) {
    Panel(spacing = 8.dp, background = Palette.WhisperCard) {
        Text("A $WHISPER_O uses a hidden route", style = Type.Section, color = Palette.WhisperTitle, modifier = Modifier.semantics { heading() })
        Text(
            if (connectMs > 0) "Connecting took ${(connectMs + 999) / 1000} s on this phone. Each lookup is slower than usual, a few seconds more."
            else "Connecting by a hidden route… Each lookup is slower than usual, a few seconds more.",
            style = Type.Label, color = Palette.WhisperSoft,
        )
        Text(TOR_LIMITS, style = Type.Small, color = Palette.WhisperSoft)
        LinkText("OK", onOk)
    }
}

/**
 * A Whisper with internet's own lines in the chat: the one-time notice with Tor's connect time
 * measured on this phone, and Cancel while a lookup has waited on Tor past about 20 s.
 */
@Composable
fun TorLines(s: io.github.odnwks.mimir.standalone.LocalState, engine: io.github.odnwks.mimir.standalone.Engine) {
    if (!s.web) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = androidx.compose.runtime.remember { ctx.getSharedPreferences("phone_chat", 0) }
    var done by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean(TOR_NOTICE_DONE, false)) }
    var ms by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(io.github.odnwks.mimir.online.TorLink.lastConnectMs) }
    androidx.compose.runtime.LaunchedEffect(done) {
        while (!done && ms == 0L) { kotlinx.coroutines.delay(500); ms = io.github.odnwks.mimir.online.TorLink.lastConnectMs }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.torSlow) TorCancel(engine::cancelTor)
        if (!done) TorNotice(ms) { done = true; prefs.edit().putBoolean(TOR_NOTICE_DONE, true).apply() }
    }
}

const val TOR_NOTICE_DONE = "tor_notice_done"

/** While a lookup waits on Tor past about 20 s: Cancel (Mímir answers without the internet). */
@Composable
fun TorCancel(onCancel: () -> Unit) {
    SmallButton("Cancel", onCancel, description = "Stop waiting: $AI_NAME answers without the internet")
}

/** "Whisper ended. Nothing was kept." for two seconds on the fresh chat, then it fades away. */
@Composable
fun WhisperEndedNote(shown: Boolean, onDone: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(shown) { if (shown) { kotlinx.coroutines.delay(2000); onDone() } }
    androidx.compose.animation.AnimatedVisibility(
        shown,
        enter = androidx.compose.animation.fadeIn(io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.SCREEN_MS)),
        exit = androidx.compose.animation.fadeOut(io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.SCREEN_MS)),
    ) {
        Text(
            WHISPER_ENDED, style = Type.Label, color = Palette.WhisperSoft,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        )
    }
}

/** The phone's clipboard for the emergency exit. */
fun clipboardWipe(ctx: android.content.Context) = object : io.github.odnwks.mimir.standalone.ClipboardWipe {
    private val cm get() = ctx.getSystemService(android.content.ClipboardManager::class.java)
    override fun text(): String? = runCatching { cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
    override fun clear() {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) cm?.clearPrimaryClip()
            else cm?.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        }
    }
}

/**
 * The emergency exit's last step: the Whisper is wiped (see [io.github.odnwks.mimir.standalone.WhisperExit]),
 * then the app closes and leaves Recents, so the phone shows its home screen.
 */
fun emergencyExit(ctx: android.content.Context, engine: io.github.odnwks.mimir.standalone.Engine, reader: io.github.odnwks.mimir.audio.ReadAloud?) {
    io.github.odnwks.mimir.standalone.WhisperExit.run(engine, reader, clipboardWipe(ctx))
    (ctx as? android.app.Activity)?.finishAndRemoveTask()
}

/** Settings › Privacy check: "End Whispers when I leave the app" (off unless chosen). */
const val END_ON_LEAVE = "end_whispers_on_leave"
const val PHONE_CHAT_PREFS = "phone_chat"

/** A Whisper open: no screenshots, a blank picture in Recents, and a keyboard that doesn't learn. */
@Composable
fun WhisperHygiene(on: Boolean) {
    if (on) NoScreenshots()
}

/** The keyboard flag "no personalised learning" (Android 8 and later; older keyboards ignore it). */
private const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x01000000

/**
 * In a Whisper ([on]) the keyboard is told not to learn from what's typed in [content]'s boxes
 * (the flag is added to the box's own input settings), and the box turns auto-correct off
 * (see [whisperKeyboard]).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun NoKeyboardLearning(on: Boolean, content: @Composable () -> Unit) {
    if (!on) { content(); return }
    androidx.compose.ui.platform.InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            nextHandler.startInputMethod(object : androidx.compose.ui.platform.PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection {
                    val c = request.createInputConnection(outAttrs)
                    outAttrs.imeOptions = outAttrs.imeOptions or IME_FLAG_NO_PERSONALIZED_LEARNING
                    return c
                }
            })
        },
        content = content,
    )
}

/** The message box's keyboard in a Whisper: no auto-correct (and see [NoKeyboardLearning]). */
fun whisperKeyboard(on: Boolean) =
    if (on) androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false) else androidx.compose.foundation.text.KeyboardOptions.Default
