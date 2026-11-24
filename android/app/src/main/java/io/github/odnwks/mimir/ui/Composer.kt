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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
 * The one composer (proposal 3.3): a rounded box with an optional "To:" chip over one line of
 * text that grows to six. The mic has its own spot at the start of the line, away from Send, so
 * a tap meant for Send or Stop can never start listening; what it hears goes into the box. Send
 * is dimmed while the box is empty. While a task is [working] a red Stop button sits beside Send,
 * typed words or not; a tap within 800 ms of Stop turning up or going is ignored.
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
    chip: String? = null,
    chipLabel: String = "",
    onChip: () -> Unit = {},
    /** A Whisper: the box turns teal like the chat (the colour fades in). */
    whisper: Boolean = false,
    working: Boolean = false,
    onStop: (() -> Unit)? = null,
    /** Pictures and files for this message (the + beside the mic, the chips above the words); null: this box takes none. */
    attach: AttachState? = null,
    /** What the attach menu says about pictures (the PC takes them on a task's message). */
    attachSight: Sight = Sight.Sees,
    /** The "Model · Effort" chip for this task (null: not shown) and what it opens. */
    optionsChip: String? = null,
    onOptions: () -> Unit = {},
    /** Said in place of the chip when this PC can't take them yet. */
    optionsNote: String? = null,
    /** A new task needs words (they name it); a reply can go with only a picture. */
    needsWords: Boolean = false,
) {
    val shape = RoundedCornerShape(20.dp)
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
            .padding(start = 4.dp, end = 4.dp, top = if (optionsNote != null) 4.dp else 0.dp, bottom = 0.dp),
    ) {
        attach?.Note()
        if (attach?.any == true) Box(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)) { attach.Chips() }
        if (optionsNote != null) Text(optionsNote, style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        // One slim chip (28 dp, a 48 dp touch): on the same line as the words, or on a line above with large text.
        val slim = chip?.let { it to chipLabel } ?: optionsChip?.let { it to "Model and effort for this task: $it. Change" }
        val bigText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
        if (slim != null && bigText) SlimChip(slim.first, slim.second, if (chip != null) onChip else onOptions, Modifier.padding(start = 4.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            if (attach != null) attach.Button(attachSight)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                textStyle = Type.Body.copy(color = Palette.Text, lineHeight = 16.sp * 1.4f),
                cursorBrush = SolidColor(Palette.BlueLight),
                maxLines = 6,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = label }
                    .focusRequester(focus)
                    // A real keyboard: Enter sends, Shift+Enter is a new line, Tab leaves the box.
                    .messageBoxKeys { if (sendable) onSend() },
                decorationBox = { inner ->
                    Box(Modifier.padding(start = 6.dp, top = 12.dp, bottom = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(hint, style = Type.Body.copy(lineHeight = 16.sp * 1.4f), color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
            if (slim != null && !bigText) SlimChip(slim.first, slim.second, if (chip != null) onChip else onOptions)
            MicButton(voice, micLabel, filled = false, surface = Color.Transparent, size = 36.dp, outline = false)
            val kind = if (sendable) ButtonKind.Send else ButtonKind.Empty
            // Stop is there the whole time something works, typed words or not (owner, 2026-11-22).
            val showStop = working && onStop != null
            // A tap meant for the button that was there a moment ago does nothing: Stop turning up
            // under a thumb that was about to press something else.
            var armed by remember { mutableStateOf(true) }
            var before by remember { mutableStateOf(showStop) }
            val view = androidx.compose.ui.platform.LocalView.current
            LaunchedEffect(showStop) {
                // Only Stop turning up from nothing holds a tap back (not a change in why it works).
                val appeared = showStop && !before
                before = showStop
                if (!appeared) return@LaunchedEffect
                armed = false; kotlinx.coroutines.delay(BUTTON_SETTLE_MS); armed = true
            }
            val settled = { armed }
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                // Stop asks twice or for a 2 second hold (Settings › Look and size › Stop buttons).
                if (showStop) StopControl({
                    if (settled()) onStop?.invoke()
                    else {
                        // Too soon after Stop turned up: say so rather than doing nothing.
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        view.announceForAccessibility("Stop is getting ready. Tap again.")
                    }
                }, shape = StopShape.Round, stateText = "Task is working")
                androidx.compose.animation.Crossfade(kind, animationSpec = io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.SCREEN_MS), label = "send") { k ->
                    when (k) {
                        ButtonKind.Empty -> RoundButton(Glyphs.Send, "Send", {}, size = 36.dp, filled = true, enabled = false, iconSize = 20.dp)
                        ButtonKind.Send -> RoundButton(Glyphs.Send, "Send", {
                            if (sendable) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onSend()
                            }
                        }, size = 36.dp, filled = true, iconSize = 20.dp)
                    }
                }
            }
        }
    }
}

/** Marks the reply box, so a test can measure it. */
const val COMPOSER_TAG = "composer"

/** The one slim chip of the reply box: 28 dp high, touched at 48 dp, its words cut with "…". */
@Composable
internal fun SlimChip(text: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.heightIn(min = 48.dp).widthIn(max = 112.dp).clip(shape).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(28.dp).clip(shape).border(1.dp, Palette.LineStrong, shape).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text, style = Type.Small.copy(fontWeight = FontWeight.Bold), color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Glyphs.Down, null, tint = Palette.TextDim, modifier = Modifier.size(16.dp))
        }
    }
}

private enum class ButtonKind { Empty, Send }

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
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { nav.openSheet(Sheet.ComposeModel) },
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
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).then(if (canEffort) Modifier.clickable(role = Role.Button) { nav.openSheet(Sheet.ComposeEffort) } else Modifier),
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

/** The model for the task the composer starts (this task only; the usual one stays). */
@Composable
fun ComposeModelSheet(state: StationState, nav: Navigator) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 12.dp).selectableGroup()) {
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) { SheetHandle() }
        SectionTitle("Model for this task", Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), style = Type.SheetTitle)
        val back = { id: String -> nav.composeTo = nav.composeTo.copy(model = id); nav.openSheet(Sheet.WhereTo) }
        WhereRow("Your usual", state.models.selectedName, nav.composeTo.model.isEmpty()) { back("") }
        (state.models.claude + state.models.local).forEach { m ->
            WhereRow(m.name, m.note, nav.composeTo.model == m.id) { back(m.id) }
        }
    }
}

/** The effort for the task the composer starts (this task only). */
@Composable
fun ComposeEffortSheet(nav: Navigator) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 12.dp).selectableGroup()) {
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) { SheetHandle() }
        SectionTitle("Effort for this task", Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), style = Type.SheetTitle)
        val now = TaskEffort.of(nav.composeTo.effort.ifEmpty { null })
        TaskEffort.entries.forEach { e ->
            WhereRow(e.words, e.about, now == e) { nav.composeTo = nav.composeTo.copy(effort = e.wire.orEmpty()); nav.openSheet(Sheet.WhereTo) }
        }
    }
}
