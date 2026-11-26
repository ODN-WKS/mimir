package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.odnwks.mimir.ui.adaptive.messageBoxKeys
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.ComposeTo
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/**
 * The one reply box (owner's standard, 2026-11-25: the task screen's box): the words on top, one
 * line that grows to six, and under them one row ([ReplyRow]): + on the left, the "Model · Effort"
 * chip in the centre, a small round Send on the right. There is no mic in the box: voice typing is
 * in the screen's three-dots menu, and while it listens a mic shows beside the words as the way to
 * finish ([mic]: a screen without its three dots keeps the mic there). Send is dimmed while the box is empty.
 */
@Composable
fun Composer(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    label: String,
    voice: VoiceInput,
    micLabel: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    /** A Whisper: the box turns teal like the chat (the colour fades in). */
    whisper: Boolean = false,
    /** Pictures and files for this message (the + on the left, the chips above the words); null: this box takes none. */
    attach: AttachState? = null,
    /** What the attach menu says about pictures (the PC takes them on a task's message). */
    attachSight: Sight = Sight.Sees,
    /** The "Model · Effort" chip (null: not shown) and what it opens. */
    optionsChip: String? = null,
    onOptions: () -> Unit = {},
    /** Shorter words for the chip when there is too little room ([optionsShorter]). */
    optionsShort: List<String> = emptyList(),
    /** Said under the row when this PC can't take the choice yet. */
    optionsNote: String? = null,
    /** A new task needs words (they name it); a reply can go with only a picture. */
    needsWords: Boolean = false,
    /** The mic beside the words even when not listening: for a screen whose three dots are hidden (a Whisper in Chat). */
    mic: Boolean = false,
) {
    val shape = RoundedCornerShape(24.dp)
    val sendable = value.isNotBlank() || (attach?.any == true && !needsWords)
    val surface by androidx.compose.animation.animateColorAsState(if (whisper) Palette.WhisperBar else Palette.Bar, io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.SCREEN_MS), label = "box")
    val haptic = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }
    // "Ask Claude" on the tablet's rail (or Ctrl+N) puts the cursor here.
    val ask = io.github.odnwks.mimir.ui.adaptive.LocalAskTick.current
    LaunchedEffect(ask) { if (ask > 0) runCatching { focus.requestFocus() } }
    // A reply box opens with the cursor in it (when Settings allows it).
    val keyCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); if (keyboardAutoOn(keyCtx)) runCatching { focus.requestFocus() } }
    Column(
        modifier.fillMaxWidth().testTag(COMPOSER_TAG).clip(shape).background(surface).border(1.dp, Palette.Outline, shape)
            .padding(start = 6.dp, end = 6.dp, bottom = 2.dp),
    ) {
        attach?.Note()
        if (attach?.any == true) Box(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) { attach.Chips() }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Voice typing started from the three dots: the mic shows only while it listens, to finish
            // (or always, with [mic], where the three dots are hidden).
            if (mic || voice.state == Speaking.Listening) Box(Modifier.padding(bottom = 4.dp)) { MicButton(voice, micLabel, filled = false, surface = Palette.Card) }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                textStyle = Type.Body.copy(color = Palette.Text, lineHeight = 16.sp * 1.4f),
                cursorBrush = SolidColor(Palette.BlueLight),
                maxLines = 6,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = label }
                    .padding(horizontal = 8.dp)
                    .focusRequester(focus)
                    // A real keyboard: Enter sends, Shift+Enter is a new line, Tab leaves the box.
                    .messageBoxKeys { if (sendable) onSend() },
                decorationBox = { inner ->
                    Box(Modifier.padding(top = 12.dp, bottom = 4.dp), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(hint, style = Type.Body.copy(lineHeight = 16.sp * 1.4f), color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
        }
        ReplyRow(
            plus = { attach?.Button(attachSight) },
            chip = { if (optionsChip != null) OptionsChip(optionsChip, "Model and effort for this task: $optionsChip. Change", optionsShort, onClick = onOptions) },
            send = {
                SendButton(sendable) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onSend()
                }
            },
        )
        if (optionsNote != null) Text(optionsNote, style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
    }
}

/**
 * The reply box's one row under the words (owner, 2026-11-25), the same on every screen: [plus]
 * on the left, [chip] in the centre, [send] on the right, close to the box's bottom edge.
 */
@Composable
fun ReplyRow(plus: @Composable () -> Unit, chip: @Composable () -> Unit, send: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag(REPLY_ROW_TAG), verticalAlignment = Alignment.CenterVertically) {
        // 48 dp: the + is touched at 48 dp or more.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { plus() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { chip() }
        Box(Modifier.widthIn(min = 40.dp).padding(end = 4.dp), contentAlignment = Alignment.CenterEnd) { send() }
    }
}

/** The reply box's Send: drawn [SEND_SIZE] (touched at 48 dp), dimmed until there is something to send. */
@Composable
fun SendButton(ready: Boolean, fill: androidx.compose.ui.graphics.Color = Palette.Blue, onSend: () -> Unit) {
    androidx.compose.animation.Crossfade(ready, animationSpec = io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.SCREEN_MS), label = "send") { r ->
        RoundButton(Glyphs.Send, "Send", { if (r) onSend() }, size = SEND_SIZE, iconSize = 16.dp, filled = true, fill = fill, enabled = r, drawnTag = SEND_DOT_TAG)
    }
}

/**
 * The "Opus 5.5 · Default Effort ⌄" chip in the centre of the reply box's row: a tap opens the model
 * and effort sheet. [shorter] lists shorter whole words, the last being the model alone. With too little
 * room (large text, a narrow phone like the S8 at 320 dp) the chip keeps the model in sight and never cuts
 * a word, on one line: the first of [words] and [shorter] (but the last) that fits, then the same as a
 * compact chip (no arrow, a little smaller), then the model alone (the effort goes before the model).
 */
@Composable
fun OptionsChip(words: String, description: String, shorter: List<String> = emptyList(), onClick: () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val forms = listOf(words) + shorter
        val both = if (shorter.isEmpty()) forms else forms.dropLast(1)
        // The chip's edges and arrow: 10 + 6 dp of padding, 4 dp of gap, 16 dp of arrow; compact: 8 + 8 dp of padding.
        fun fits(t: String, compact: Boolean): Boolean {
            val room = constraints.maxWidth - with(density) { (if (compact) 16.dp else 36.dp).roundToPx() }
            return measurer.measure(t, chipStyle(compact), maxLines = 1).size.width <= room
        }
        val shown = both.firstOrNull { fits(it, false) }?.let { it to false }
            ?: both.firstOrNull { fits(it, true) }?.let { it to true }
            ?: forms.last().let { it to !fits(it, false) }
        OptionsChipBody(shown.first, description, shown.second, onClick)
    }
}

/** The chip's words: bold small type, 12 sp when compact. */
private fun chipStyle(compact: Boolean) = Type.Small.copy(fontWeight = FontWeight.Bold).let { if (compact) it.copy(fontSize = 12.sp) else it }

@Composable
private fun OptionsChipBody(words: String, description: String, compact: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Palette.LineStrong, RoundedCornerShape(12.dp))
                .padding(start = if (compact) 8.dp else 10.dp, end = if (compact) 8.dp else 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(words, style = chipStyle(compact), color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (!compact) Icon(Glyphs.Down, null, tint = Palette.TextDim, modifier = Modifier.size(16.dp))
        }
    }
}

/** Send's drawn size on the reply box (owner, 2026-11-25). */
val SEND_SIZE = 28.dp

/** Test tags: the reply box's row, and Send's drawn circle. */
const val REPLY_ROW_TAG = "reply-row"
const val SEND_DOT_TAG = "send-dot"

/** Taps this soon after the composer's button changes are ignored. */
const val BUTTON_SETTLE_MS = 800L

/** The chip's words for where a message goes: "Auto", a project, plus what's chosen for this task. */
fun ComposeTo.chipWords(): String = listOfNotNull(
    project.ifEmpty { "Auto" },
    "Plan first".takeIf { plan },
    "Whisper".takeIf { whisper },
).joinToString(" · ")

/**
 * Sends what's in Chat's composer where its chip says: Auto lets the PC file it (a status
 * question, an idea, a new task or one already open); a chosen project or option starts a task
 * there and opens it. The options are for this one message: the chip goes back to Auto after.
 * Returns false when nothing went (the words stay in the box).
 */
fun sendFromChat(
    text: String, state: StationState, station: Station, nav: Navigator, engine: io.github.odnwks.mimir.standalone.Engine? = null,
    pictures: List<io.github.odnwks.mimir.standalone.TurnPicture> = emptyList(), files: List<io.github.odnwks.mimir.standalone.TurnFile> = emptyList(),
): Boolean {
    val to = nav.composeTo
    if (to.auto && state.paired && answerEveryday(text, state, station, nav, engine)) return true
    if (to.auto) { station.send(text); return true }
    val id = station.startTask(text.trim(), to.project.ifEmpty { state.newTaskProject }, to.whisper, to.plan, to.model.ifEmpty { null }, pictures, files, to.effort.ifEmpty { null })
    if (id.isEmpty()) return false
    nav.composeTo = ComposeTo()
    if (id == FakeStation.INCOGNITO) nav.open(Screen.IncognitoTask) else nav.open(Screen.Task(id))
    return true
}

/**
 * To: Auto, an everyday message (the weather, a question, a hello) is answered directly rather
 * than filed in a project: the weather from Open-Meteo, right here; anything else by $AI_NAME on
 * this phone (with its lookups), or else as a general chat in the PC's ${io.github.odnwks.mimir.online.Everyday.GENERAL}
 * project. Only clear project work goes to the PC to be filed. Returns false when it isn't one.
 */
fun answerEveryday(text: String, state: StationState, station: Station, nav: Navigator, engine: io.github.odnwks.mimir.standalone.Engine?): Boolean {
    val words = text.trim()
    val route = io.github.odnwks.mimir.online.Everyday.route(words, state.pickable) ?: return false
    val online = io.github.odnwks.mimir.online.Online.allowed(io.github.odnwks.mimir.online.ChatMode.Normal)
    if (route is io.github.odnwks.mimir.online.Everyday.Route.Weather && online) {
        val named = route.place
        val saved = io.github.odnwks.mimir.online.Online.settings.value.place
        station.answerHere(words, (named ?: saved?.name)?.let { "Weather for $it…" } ?: "Looking up the weather…") { weatherWords(named, saved, words) }
        return true
    }
    // Mímir on this phone, in its normal mode (a Whisper is never mixed with this).
    val local = engine?.state?.value
    if (local != null && local.ready && !local.writing && !local.whisper && local.testing == null) {
        // A chat left a while ago isn't continued: a fresh one starts.
        if (local.chat.turns.isNotEmpty() && System.currentTimeMillis() - local.chat.at > EVERYDAY_SAME_CHAT_MS) engine.newChat()
        engine.send(words)
        nav.open(io.github.odnwks.mimir.nav.Screen.Standalone)
        return true
    }
    val general = state.pickable.firstOrNull { it.equals(io.github.odnwks.mimir.online.Everyday.GENERAL, ignoreCase = true) }
    if (general != null && state.pc.online) {
        val id = station.startTask(words, general, incognito = false)
        if (id.isEmpty()) return false
        nav.open(io.github.odnwks.mimir.nav.Screen.Task(id))
        return true
    }
    return false
}

/** An everyday question within this long of the last one goes on in the same $AI_NAME chat. */
const val EVERYDAY_SAME_CHAT_MS = 30L * 60 * 1000

/** The weather in plain words for the paired chat: for [named] if given, else the [saved] place. */
fun weatherWords(named: String?, saved: io.github.odnwks.mimir.online.Place?, ask: String = ""): List<String> =
    // The place and its forecast together within one lookup's time (the place is kept a day).
    io.github.odnwks.mimir.online.Net.within(io.github.odnwks.mimir.online.Net.BUDGET_MS) { weatherNow(named, saved, ask) }

private fun weatherNow(named: String?, saved: io.github.odnwks.mimir.online.Place?, ask: String): List<String> {
    val mode = io.github.odnwks.mimir.online.ChatMode.Normal
    val place = when {
        named != null -> io.github.odnwks.mimir.online.Weather.find(named, mode)
            ?: return listOf("I couldn't find a place called \u201c$named\u201d. Try its name with the country, like \u201cweather in Portland, USA\u201d.")
        saved != null -> saved
        else -> return listOf(
            "Weather for where? Name a town in your message, like \u201cweather in Portland\u201d.",
            "To keep a town for every weather question: Settings › Who answers › Looking things up.",
        )
    }
    val f = io.github.odnwks.mimir.online.Weather.forecast(place, mode, ask = ask)
    return answerFirst(f.block.lines().filter { it.isNotBlank() }) + "From ${f.sources.joinToString(" · ") { it.site }}"
}

private val RAIN_VERDICT = Regex("^Start the answer with \"(Yes|Maybe|No)\" \\(the chance of rain or snow (.+?) is (\\d+)%.*The question was whether it will (\\w+)\\.$")

/**
 * "Will it rain tomorrow?" answered first: the forecast's Yes, Maybe or No (50% or more yes,
 * 30 to 49 maybe, the same rule as Mímir's own answer) becomes the first line in plain words,
 * then the details. Other forecasts are left as they are.
 */
fun answerFirst(lines: List<String>): List<String> {
    val i = lines.indexOfFirst { RAIN_VERDICT.matches(it) }
    if (i < 0) return lines
    val (word, `when`, pct, verb) = RAIN_VERDICT.find(lines[i])!!.destructured
    return listOf("$word: a $pct% chance of $verb ${`when`}.") + lines.filterIndexed { n, _ -> n != i }
}

/** "Where should this go?" (board 03): Auto or a project, and More options for this one task. */
@Composable
fun WhereToSheet(state: StationState, nav: Navigator, canEffort: Boolean = false) {
    val to = nav.composeTo
    var more by rememberSaveable { mutableStateOf(to.plan || to.model.isNotEmpty() || to.whisper || to.effort.isNotEmpty()) }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 12.dp)) {
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) { SheetHandle() }
        SectionTitle("Where should this go?", Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            style = Type.SheetTitle)
        Column(Modifier.selectableGroup()) {
            WhereRow("Auto", "$AI_NAME picks the project", to.project.isEmpty(), recommended = true) { nav.composeTo = to.copy(project = "") }
            state.pickable.take(5).forEach { p ->
                WhereRow(p, null, to.project == p) { nav.composeTo = to.copy(project = p) }
            }
            // A project further down the list, or a new one: the picker hands it back.
            if (to.project.isNotEmpty() && to.project !in state.pickable.take(5)) WhereRow(to.project, null, true) {}
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { nav.openSheet(Sheet.ProjectPicker(to.project, back = Sheet.WhereTo)) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Glyphs.Plus, null, tint = Palette.BlueText, modifier = Modifier.size(22.dp))
            Text("More projects, or a new one…", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.BlueText)
        }
        Spacer(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().size(1.dp).background(Palette.Line))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { more = !more }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("More options", style = Type.Label.copy(fontWeight = FontWeight.Bold), color = Palette.TextSoft)
            Icon(if (more) Glyphs.Up else Glyphs.Down, null, tint = Palette.TextSoft, modifier = Modifier.size(18.dp))
        }
        androidx.compose.animation.AnimatedVisibility(
            more,
            enter = androidx.compose.animation.expandVertically(io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.NEEDS_YOU_BAR_MS)) +
                androidx.compose.animation.fadeIn(io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.NEEDS_YOU_BAR_MS)),
            exit = androidx.compose.animation.shrinkVertically(io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.NEEDS_YOU_BAR_MS)) +
                androidx.compose.animation.fadeOut(io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.NEEDS_YOU_BAR_MS)),
        ) {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SwitchRow(to.plan, { nav.composeTo = nav.composeTo.copy(plan = it) }) {
                    Column(Modifier.weight(1f)) {
                        Text("Plan first", style = Type.BodyTight, color = Palette.Text)
                        Text("Mímir writes a plan and changes nothing until you say OK", style = Type.Small, color = Palette.TextDim)
                    }
                }
                val model = (state.models.claude + state.models.local).firstOrNull { it.id == to.model }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { nav.openSheet(Sheet.NewTaskModel()) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Model for this task", style = Type.BodyTight, color = Palette.Text)
                        Text(model?.let { "${it.name} · for this task only" } ?: "${state.models.selectedName} · your usual", style = Type.Small, color = Palette.TextDim)
                    }
                    Icon(Glyphs.Next, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
                }
                // Effort for this task: only a PC that takes it with the task (task_reply_options).
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).then(if (canEffort) Modifier.clickable(role = Role.Button) { nav.openSheet(Sheet.NewTaskModel()) } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Effort for this task", style = Type.BodyTight, color = if (canEffort) Palette.Text else Palette.TextSoft)
                        Text(
                            if (canEffort) TaskEffort.of(to.effort.ifEmpty { null }).let { if (it == TaskEffort.Usual) "Usual · its own choice" else "${it.words} · ${it.about}" } else UPDATE_PC_FOR_OPTIONS,
                            style = Type.Small, color = Palette.TextDim,
                        )
                    }
                    if (canEffort) Icon(Glyphs.Next, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
                }
                // Whisper mode needs a free model on the PC: without one it says why, and where to add one.
                if (state.models.local.isEmpty()) Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MimirEye(EyeMode.Whisper, Modifier.size(28.dp).alpha(0.5f))
                    Column(Modifier.weight(1f)) {
                        Text("Whisper mode", style = Type.BodyTight, color = Palette.TextSoft)
                        Text("Needs a model on your PC: on the PC, $AI_NAME › Settings › Models adds one.", style = Type.Small, color = Palette.TextDim)
                    }
                }
                else SwitchRow(to.whisper, { nav.composeTo = nav.composeTo.copy(whisper = it) }, on = Palette.WhisperSwitch) {
                    MimirEye(EyeMode.Whisper, Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Whisper mode", style = Type.BodyTight, color = Palette.Text)
                        Text("A model on your PC, internet cut off, nothing kept", style = Type.Small, color = Palette.TextDim)
                    }
                }
            }
        }
        BigButton("Done", Look.Primary, { nav.closeSheet() }, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), height = 48.dp)
    }
}

@Composable
private fun WhereRow(title: String, about: String?, selected: Boolean, recommended: Boolean = false, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).choice(selected, choose).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RadioMark(selected)
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.BodyTight, color = Palette.Text)
            if (about != null) Text(about, style = Type.Small, color = Palette.TextDim)
        }
        if (recommended) Tag("Default", Palette.BlueText, Palette.BlueIcon)
    }
}
