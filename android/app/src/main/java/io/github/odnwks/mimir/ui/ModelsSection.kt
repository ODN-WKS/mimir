package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.Mapping
import io.github.odnwks.mimir.data.Model
import io.github.odnwks.mimir.data.Power
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.nav.Tab
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.data.count

/**
 * Models (board 13): always three groups, each with a line saying what it is: Claude (on your
 * PC), free models on your PC, and the open models that run on this phone. A group that can't be
 * used yet says why and what to do, instead of being left out.
 */
@Composable
fun ModelsScreen(state: StationState, station: Station, nav: Navigator, phone: PhoneModels?) {
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        val subtitle = when {
            !state.paired -> "Not paired with your PC yet"
            state.models.power == Power.FreeOnly || state.paidOn == false -> "Using your PC's models only"
            else -> "Online use this week: ${state.usage.percentText}"
        }
        BackHeader("Who answers", nav::back, backLabel = "Back", subtitle = subtitle)
        ModelsSection(state, station, nav, phone, Modifier.weight(1f))
        NeedsYouBar(state.needsYou.size, { nav.open(Screen.NeedsYou) }, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp))
    }
}

/**
 * Settings › Who answers, without a page around it (the Settings frame shows it in its own pane):
 * what powers your tasks, then the three groups, with chips that bring each into view. The
 * phone's own group is the Model Store's first page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsSection(state: StationState, station: Station, nav: Navigator, phone: PhoneModels?, modifier: Modifier = Modifier) {
    val m = state.models
    val power = m.power.takeIf { state.paired }
    val tap = LocalTapTracker.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current
    // Where each group starts, so the chips at the top can bring it into view.
    val tops = remember { mutableStateMapOf<String, Int>() }
    val group = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp)
    fun mark(key: String) = Modifier.onGloballyPositioned { tops[key] = it.positionInParent().y.toInt() }
    val claude: @Composable () -> Unit = {
        Column(mark("claude").selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupTitle("Online", "Uses your Anthropic plan · for new tasks on your PC", group)
            // With free models only, Claude is never called: the list would only mislead.
            if (power == Power.FreeOnly) GroupNote("Not used: your PC is set to its own models only, so online answers are never used.")
            // Before pairing the list is still shown; the choice is kept for when the PC is there.
            // Before pairing they're shown greyed, each saying what it takes; nothing is a dead choice.
            else m.claude.ifEmpty { Mapping.CLAUDE }.forEach { ModelRow(it, state.paired && it.id == m.selected, usable = state.paired) { station.selectModel(it.id) } }
            if (!state.paired) GroupNote("Online answers work through your PC.", "Connect your PC") { nav.open(Screen.Pair, tap.last) }
        }
    }
    val free: @Composable () -> Unit = {
        Column(mark("free").selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupTitle("Your PC's models", "Run on your PC's own graphics card · no online use", group)
            m.local.forEach { ModelRow(it, it.id == m.selected) { station.selectModel(it.id) } }
            when {
                !state.paired -> GroupNote("Your PC's models run on your PC's graphics card.", "Connect your PC") { nav.open(Screen.Pair, tap.last) }
                m.local.isEmpty() && !state.pc.online -> GroupNote("Your PC is away: its models show here when it's back.")
                m.local.isEmpty() -> GroupNote("No models on your PC yet. On the PC, $AI_NAME › Settings › Models can add one.")
            }
            if (power == Power.ClaudeOnly && m.local.isNotEmpty()) GroupNote("Not used for tasks: your PC is set to online only.")
            if (m.local.isNotEmpty() && power != Power.ClaudeOnly) {
                // The switch itself sits under Claude use on Status, the use it's about.
                ListRow(
                    "Switch to free near my limit", (if (m.switchToFree) "On" else "Off") + " · it's under Online use on Status",
                    { nav.selectTab(io.github.odnwks.mimir.nav.Tab.Status) }, Modifier.padding(top = 4.dp),
                )
                Text(
                    "Your PC's models suit small, clear jobs. A long task may be too much for them.",
                    style = Type.Small.copy(lineHeight = Type.Small.fontSize * 1.45f), color = Palette.TextDim, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
                )
            }
        }
    }
    val onPhone: @Composable () -> Unit = {
        Column(mark("phone"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val canRun = phone?.state?.collectAsStateWithLifecycle()?.value?.supported == true
            if (canRun) GroupTitle("On this phone".forDevice(), "Run here · no PC, no internet, nothing leaves the phone".forDevice(), group)
            else GroupTitle("Model store", "This phone can't run a model itself · free online ones instead".forDevice(), group)
            WeeklyCheck(state, station)
            PhoneStore(phone, nav, state.paired)
        }
    }
    // The devices that are yours come before Online: this phone, your PC, then Online.
    val order = listOf("phone" to onPhone, "free" to free, "claude" to claude)
    Column(modifier.fillMaxWidth().background(Palette.Ground)) {
        // The three groups at a glance; a chip brings its group into view.
        // One row of three equal chips; a label that can't fit goes to two lines inside its chip.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val names = mapOf("claude" to "Online", "free" to "Your PC", "phone" to "On this phone")
            order.forEach { (key, _) ->
                JumpChip(names.getValue(key), Modifier.weight(1f).fillMaxHeight()) {
                    val y = tops[key] ?: return@JumpChip
                    scope.launch { if (reduce) scroll.scrollTo(y) else scroll.animateScrollTo(y) }
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The PC's two lines (what answers chats, what does project work); an older PC keeps the old panel.
            if (state.paired && m.chatsEngine != null) EnginesPanel(m.chatsEngine, m.projectEngine, station::setEngines)
            else PowerPanel(state.paired, power)
            // What every model is told first, on the phone and on the PC.
            GroundRulesRow({ nav.open(Screen.GroundRules, tap.last) })
            order.forEach { (_, content) -> content() }
        }
    }
}

/**
 * The PC's weekly look for a better model for this phone: its latest "Worth switching?" card (the
 * same one Updates shows) and the switch that turns the look off. Only once paired: the PC looks.
 */
@Composable
private fun WeeklyCheck(state: StationState, station: Station) {
    if (!state.paired) return
    val on = state.models.weeklyCheck
    val card = state.worthSwitching?.takeIf { on }
    if (card != null) Panel(background = Palette.BlueCard, border = Palette.BlueBorder, spacing = 6.dp) {
        Text("Worth switching?", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.semantics { heading() })
        Text(card.text.removePrefix("Worth switching?").trim(), style = Type.Label, color = Palette.TextSoft)
        Text("From the weekly check · ${card.whenShort}", style = Type.Small, color = Palette.TextDim)
    }
    Panel(background = Palette.Bar, radius = 16.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        SwitchRow(on, station::setWeeklyCheck, Modifier.heightIn(min = 44.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Look for a better model each week", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                Text("Your PC checks once a week and tells you here and in Updates. Nothing changes by itself.", style = Type.Small, color = Palette.TextDim)
            }
        }
    }
}

/** A chip at the top of Models that brings a group into view. */
@Composable
private fun JumpChip(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Palette.BlueBorder, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Show $text", onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.BlueText,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * "What powers your tasks": the PC's setting, which counts once paired (it's changed on the PC,
 * in Settings › General). Before pairing, chats here always use the model on this phone.
 */
@Composable
private fun PowerPanel(paired: Boolean, power: Power?) {
    if (paired && power == null) return
    if (!paired) {
        // Before pairing there's nothing to choose: one quiet line says so.
        Text(
            "Not paired with a PC, so your chats here always use the model on this phone.".forDevice(),
            style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }
    Panel(background = Palette.Bar, radius = 16.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 4.dp) {
        Text("What powers your tasks", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.semantics { heading() })
        Text(power!!.words, style = Type.Label, color = Palette.TextSoft)
        Text("Set on your PC, in $AI_NAME › Settings › Models. Tasks from this phone follow it.", style = Type.Small, color = Palette.TextDim)
    }
}

/** Settings › Who answers: "Chats and lookups" (Free / Claude) and "Project work" (Free / Claude). */
@Composable
internal fun EnginesPanel(chats: String?, project: String?, set: (chats: String?, project: String?) -> Unit) {
    Panel(background = Palette.Bar, radius = 16.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 10.dp) {
        Text("Who answers what", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.semantics { heading() })
        EngineLine("Chats and lookups", listOf("free" to "Your PC's model", "paid" to "Online"), chats) { set(it, null) }
        EngineLine("Project work", listOf("free" to "Your PC's model", "claude" to "Online"), project) { set(null, it) }
        Text("Your PC's model runs on your PC and costs nothing; your messages stay between this phone and your PC. Online uses your Anthropic plan, and your messages go to Anthropic.".forDevice(), style = Type.Small, color = Palette.TextDim)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineLine(title: String, options: List<Pair<String, String>>, current: String?, pick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = Type.Label, color = Palette.TextSoft)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (wire, words) ->
                val on = wire == current
                Box(
                    Modifier.heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, if (on) Palette.BlueBorder else Palette.Line, RoundedCornerShape(12.dp))
                        .background(if (on) Palette.BlueCard else Palette.Bar)
                        .clickable(role = Role.RadioButton, onClickLabel = "$title: $words") { if (!on) pick(wire) }
                        .semantics { selected = on }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(words, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = if (on) Palette.BlueText else Palette.Text) }
            }
        }
    }
}

/** A group's title and the one line that says what it is. */
@Composable
internal fun GroupTitle(title: String, line: String, modifier: Modifier) {
    Column(modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(title, style = Type.Label.copy(fontWeight = FontWeight.Bold))
        Text(line, style = Type.Small, color = Palette.TextDim)
    }
}

/** Why a group is empty, and the one thing to do about it. */
@Composable
internal fun GroupNote(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Panel(background = Palette.Bar, radius = 16.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 10.dp) {
        Text(text, style = Type.Label, color = Palette.TextSoft)
        if (action != null) BigButton(action, Look.Primary, onAction, height = 48.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelRow(model: Model, selected: Boolean, usable: Boolean = true, select: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    if (!usable) {
        // A PC model before pairing: greyed, not a choice, with what it takes.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape).background(Palette.Card).border16(Palette.Line)
                .alpha(0.55f).semantics(mergeDescendants = true) {}.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.padding(top = 2.dp)) { RadioMark(false) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(plainModelName(model.name), style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                Text(PC_MODEL_NEEDS, style = Type.Small, color = Palette.TextDim)
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape)
            .background(if (selected) Palette.BlueCard else Palette.Card)
            .then(if (selected) Modifier.border16Width(Palette.BlueLight, 2) else Modifier.border16(Palette.Line))
            .choice(selected, select)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 2.dp)) { RadioMark(selected) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(plainModelName(model.name), style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
            Text(plainTestNote(model.note), style = Type.Small, color = Palette.TextDim)
            // The tags sit under the words, so large text never squeezes the name; a model with no
            // safety filter carries its tag here and the note is said once, below the list.
            if (model.free || model.refusalsRemoved) FlowRow(
                Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (model.free) Tag("Free", Palette.Ground, Palette.GreenText)
                if (model.refusalsRemoved) NoFilterTag()
            }
        }
    }
}

private fun Modifier.border16Width(color: Color, width: Int) = this.border(width.dp, color, RoundedCornerShape(16.dp))

/**
 * A model's name as a person reads it: "qwen3.6:35b-a3b-coding" is "Qwen 3.6 coding", and
 * "huihui_ai/Qwen3.6-abliterated:35b-a3b" is "Qwen 3.6 uncensored". A name with spaces is already plain.
 */
/** The speed note in plain words, as on the PC: "Fixed both test bugs in 30 s" says "Passed our coding check in 30 s". */
fun plainTestNote(note: String): String = note.replace("Fixed both test bugs in", "Passed our coding check in")

fun plainModelName(raw: String): String {
    if (raw.isBlank() || raw.any { it == ' ' } || !raw.any { it == ':' || it == '/' || it == '_' || it == '-' }) return raw
    val base = raw.substringAfterLast('/').substringBefore(':')
    val tag = raw.substringAfterLast('/').substringAfter(':', "")
    val size = Regex("""(?i)^(a?\d+(\.\d+)?[bkm]|q\d.*|fp\d+|latest|instruct|it|gguf)$""")
    fun words(part: String) = part.split('-', '_').filter { it.isNotBlank() }.map {
        when (it.lowercase()) { "abliterated", "uncensored", "heretic" -> "uncensored"; else -> it.replace(Regex("""(?<=[A-Za-z])(?=\d)"""), " ") }
    }
    fun keep(part: String) = part.split('-', '_').filter { it.isNotBlank() && !size.matches(it) }
    val all = (words(keep(base).joinToString("-")) + words(keep(tag).joinToString("-"))).distinct()
    return all.joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { raw }
}

/** What a PC model says before pairing. */
const val PC_MODEL_NEEDS = "Connect your PC to use"
