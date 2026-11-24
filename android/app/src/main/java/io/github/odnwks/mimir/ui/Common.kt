package io.github.odnwks.mimir.ui

import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.ime
import io.github.odnwks.mimir.ui.adaptive.forDevice
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.nav.TapPoint
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.ui.theme.motionSpec

/** Shows a short note for a button whose feature arrives with the real link (e.g. "Read these aloud"). */
val LocalStandIn = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Something done that can still be taken back for eight seconds: [words] shows with Undo in the
 * one-line bar at the bottom. [later] runs when the time is up (a release is only sent then);
 * [undo] runs if Undo is tapped instead.
 */
class Undoable(val words: String, val later: () -> Unit = {}, val undo: () -> Unit = {})

/**
 * Asks for the phone's own unlock (fingerprint, face or PIN) with [title], then runs the action;
 * a cancel or a phone with no screen lock runs nothing. Only the activity provides a real one.
 */
val LocalUnlockConfirm = staticCompositionLocalOf<(title: String, ok: () -> Unit) -> Unit> { { _, _ -> } }

/** Shows an [Undoable] (the app's one undo bar). */
val LocalUndo = staticCompositionLocalOf<(Undoable) -> Unit> { { it.later() } }

/** The undo bar: what was done, a countdown and Undo, for eight seconds. */
@Composable
fun UndoBar(item: Undoable?, secondsLeft: Int, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val kept = androidx.compose.runtime.remember { arrayOf(item) }
    if (item != null) kept[0] = item
    AnimatedVisibility(
        visible = item != null, modifier = modifier,
        enter = expandVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + fadeIn(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
        exit = shrinkVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + fadeOut(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
    ) {
        val shown = kept[0] ?: return@AnimatedVisibility
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().heightIn(min = 52.dp)
                .clip(RoundedCornerShape(16.dp)).background(Palette.Card).border16(Palette.LineStrong)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Glyphs.Check, null, tint = Palette.Green, modifier = Modifier.size(18.dp))
            Text(shown.words, style = Type.Label, color = Palette.Text, modifier = Modifier.weight(1f))
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onUndo)
                    .semantics(mergeDescendants = true) { contentDescription = "Undo: ${shown.words}" }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Undo · $secondsLeft", style = Type.Label.copy(fontWeight = FontWeight.Bold), color = Palette.BlueText) }
        }
    }
}

/** Whether this phone can run a model itself; where it can't, nothing offers standalone mode. */
val LocalStandaloneOk = staticCompositionLocalOf { true }

/** The models on this phone as the Models screen shows them: their state, and choosing one. */
class PhoneModels(
    val state: kotlinx.coroutines.flow.StateFlow<io.github.odnwks.mimir.standalone.LocalState>,
    val choose: (String) -> Unit,
    /** The Model Store's buttons (download, delete, test); null: the list only. */
    val actions: PhoneModelActions? = null,
    /** This phone's memory in bytes, to say whether a model fits (0: unknown). */
    val phoneBytes: Long = 0,
)

/** The engine of the AI on this phone; null means the app's own (checks put a stand-in here). */
val LocalPhoneEngine = staticCompositionLocalOf<io.github.odnwks.mimir.standalone.Engine?> { null }

/** A stand-in for the phone's models (checks); null means the app's own engine. */
val LocalPhoneModels = staticCompositionLocalOf<PhoneModels?> { null }

/** The small tag on every model with no safety filter, in every list. */
@Composable
fun NoFilterTag(modifier: Modifier = Modifier) = Tag("Uncensored", Palette.TextSoft, Palette.Track, modifier)

/** Where the last finger touched the screen, so a tapped card can grow from there. */
class TapTracker { var last: TapPoint? = null }
val LocalTapTracker = staticCompositionLocalOf { TapTracker() }

// ---------- Headers ----------

/** The header of a home screen (Your PC, Updates): menu button, big title, one line under it. */
@Composable
fun RootHeader(title: String, subtitle: String, dot: (@Composable () -> Unit)? = null, onSettings: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // No bottom tabs: the menu button is how to get anywhere else (hidden while the menu is always open).
        if (!LocalPermanentMenu.current) Box(Modifier.padding(start = 4.dp, top = 12.dp)) { MenuButton() }
        RootHeaderText(title, subtitle, dot, Modifier.weight(1f))
        if (action != null) Box(Modifier.padding(top = 8.dp)) { action() }
        // Settings: rare, so up here, out of the thumb's way.
        if (onSettings != null) Box(Modifier.padding(top = 8.dp, end = 8.dp)) { IconTarget(Glyphs.Settings, "Settings", onSettings) }
    }
}

@Composable
private fun RootHeaderText(title: String, subtitle: String, dot: (@Composable () -> Unit)?, modifier: Modifier) {
    Column(modifier.padding(start = if (LocalPermanentMenu.current) 20.dp else 8.dp, end = 20.dp, top = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Type.PageTitle.copy(fontSize = 20.sp, lineHeight = 26.sp), color = Palette.Text, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dot != null) Box(Modifier.padding(top = firstLineCenter(Type.Small, 8.dp))) { dot() }
            // Two lines at most, even at the largest text size.
            Text(subtitle, style = Type.Small, color = Palette.TextDim, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** The header of an inner screen: a back (or close) button, the title and an optional action. */
@Composable
fun BackHeader(
    title: String,
    onBack: () -> Unit,
    backLabel: String = "Back",
    subtitle: String? = null,
    titleStyle: TextStyle = Type.TitleSmall,
    backIcon: ImageVector = Glyphs.Back,
    subtitleContent: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    /** A tap on the title (with its down-chevron) opens everything for this screen, named [titleLabel]. */
    onTitle: (() -> Unit)? = null,
    titleLabel: String = "Options",
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = if (action != null) 4.dp else 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // In a tablet's right pane with nothing to go back to, the arrow is left out rather than doing nothing.
        if (io.github.odnwks.mimir.ui.adaptive.LocalPaneBack.current) IconTarget(backIcon, backLabel, onBack)
        else Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (onTitle != null) TopicButton(title, titleLabel, onTitle, titleStyle, maxLines = 3, start = 0.dp)
            else Text(title, style = titleStyle, color = Palette.Text, modifier = Modifier.semantics { heading() })
            if (subtitleContent != null) subtitleContent()
            else if (subtitle != null) Text(subtitle, style = Type.Small, color = Palette.TextDim)
        }
        action?.invoke()
    }
}

/** A root screen's header: its title, and the connection line with its dot under it. */
@Composable
fun RootHeader(title: String, state: io.github.odnwks.mimir.data.StationState, subtitle: String = state.connectionLine, onSettings: (() -> Unit)? = null, onSearch: (() -> Unit)? = null) {
    RootHeader(
        title, subtitle, dot = { Dot(if (state.pc.online) Palette.Green else Palette.TextDim, hollow = !state.pc.online) }, onSettings = onSettings,
        // Search is always one tap away on Chat and Status, whatever the number of tasks.
        action = onSearch?.let { go -> { IconTarget(Glyphs.Search, "Search tasks and messages", go) } },
    )
}

/**
 * Shown under the header while the PC can't be reached: what still works, and on an inner screen
 * (no root header above it) the connection line first, in the same words as everywhere else.
 */
@Composable
fun ConnectionStrip(
    state: io.github.odnwks.mimir.data.StationState,
    modifier: Modifier = Modifier,
    inner: Boolean = true,
    onStandalone: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = state.pcAway,
        modifier = modifier,
        enter = expandVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + fadeIn(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
        exit = shrinkVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + fadeOut(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Asleep)
                .border(1.dp, Palette.Outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Glyphs.PhonePc, null, tint = Palette.BlueText, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    if (inner) Text(state.connectionLine, style = Type.Small.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                    state.whatStillWorks?.let { Text(it, style = Type.Small, color = Palette.TextSoft) }
                }
                if (onStandalone != null) LinkText("Chat on this phone instead", onStandalone)
            }
        }
    }
}

/**
 * What a page shows when there's nothing on it yet: one line of what will be here, and one
 * action. Never a blank page, never "0".
 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}, extra: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text, style = Type.Body, color = Palette.TextSoft, textAlign = TextAlign.Center)
        if (action != null) BigButton(action, Look.Primary, onAction, height = 48.dp)
        extra?.invoke()
    }
}

/** A bare 48 dp icon button (back, options). */
@Composable
fun IconTarget(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Palette.Text) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) }
}

/** A section heading inside a screen. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, color: Color = Palette.Text, style: TextStyle = Type.Section, maxLines: Int = Int.MAX_VALUE) {
    Text(text.forDevice(), style = style, color = color, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = modifier.semantics { heading() })
}

@Composable
fun Dot(color: Color, hollow: Boolean = false) {
    Box(
        Modifier.size(8.dp).clip(CircleShape)
            .then(if (hollow) Modifier.border(2.dp, color, CircleShape) else Modifier.background(color)),
    )
}

// ---------- Containers ----------

/** A rounded card with the boards' border; clickable when [onClick] is given. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    background: Color = Palette.Card,
    border: Color = Palette.Line,
    borderWidth: Dp = 1.dp,
    radius: Dp = 16.dp,
    padding: PaddingValues = PaddingValues(14.dp),
    spacing: Dp = 8.dp,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(background).border(borderWidth, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** A row like More's and Phones': 64 dp high, title and value, optional icon and chevron. */
@Composable
fun ListRow(
    title: String,
    value: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = { Icon(Glyphs.Next, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp)) },
    background: Color = Palette.Card,
    titleWeight: FontWeight = FontWeight.SemiBold,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape).background(background).border(1.dp, Palette.Line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title.forDevice(), style = Type.BodyTight.copy(fontWeight = titleWeight), color = Palette.Text)
            if (value != null) Text(value.forDevice(), style = Type.Small, color = Palette.TextDim)
        }
        trailing?.invoke()
    }
}

// ---------- Buttons ----------

enum class Look(val bg: Color, val fg: Color, val border: Color?) {
    Primary(Palette.Blue, Palette.White, null),
    Outline(Color.Transparent, Palette.Text, Palette.Outline),
    Danger(Color.Transparent, Palette.Red, Palette.Red),
    Amber(Palette.Amber, Palette.AmberInk, null),
    AmberOutline(Color.Transparent, Palette.AmberSoft, Palette.AmberButtonBorder),
    ReleaseOutline(Color.Transparent, Palette.AmberText, Palette.Amber),
    Whisper(Palette.WhisperButton, Palette.White, null),
    WhisperOutline(Color.Transparent, Palette.WhisperTitle, Palette.WhisperSwitch),
    Quiet(Palette.Bar, Palette.Text, Palette.Outline),
}

/** A full-width button; 52 dp for decisions, 48 dp inside cards. */
@Composable
fun BigButton(
    text: String,
    look: Look,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    radius: Dp = 12.dp,
    bold: Boolean = look == Look.Primary || look == Look.Amber || look == Look.Whisper,
    fontSize: Int = 16,
    icon: ImageVector? = null,
    description: String? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(radius)
    Row(
        modifier.fillMaxWidth().heightIn(min = height).clip(shape).background(look.bg)
            .then(if (look.border != null) Modifier.border(1.dp, look.border, shape) else Modifier)
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier else Modifier.alpha(0.5f))
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = look.fg, modifier = Modifier.size(22.dp))
        Text(
            text.forDevice(), color = look.fg, textAlign = TextAlign.Center,
            style = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.3f).sp,
                fontWeight = if (bold) FontWeight.Bold else if (look == Look.Danger || look == Look.ReleaseOutline) FontWeight.SemiBold else FontWeight.Normal),
        )
    }
}

/** A small outlined button beside a message ("Undo", "Move it", "Remove"). */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, description: String? = null, animate: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    // [animate]: a label that changes length grows or shrinks smoothly (not with "remove animations").
    val grow = animate && !LocalReduceMotion.current
    Box(
        Modifier.then(if (grow) Modifier.animateContentSize(tween(220)) else Modifier).heightIn(min = 48.dp).clip(shape).border(1.dp, Palette.Outline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.Label, color = Palette.Text) }
}

/** A round icon button: [filled] in blue (or [fill]), else outlined. */
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    filled: Boolean = false,
    fill: Color = Palette.Blue,
    surface: Color = Color.Transparent,
    iconSize: Dp = 22.dp,
    /** False: shown dimmed, and a tap does nothing. */
    enabled: Boolean = true,
    /** False: no ring (a small icon button, as on Mímir's message box). */
    outline: Boolean = true,
    /** Read out by TalkBack, and announced when the button turns up (Stop: "Task is working"). */
    stateText: String? = null,
) {
    Box(
        Modifier.size(size).alpha(if (enabled) 1f else 0.38f).clip(CircleShape)
            .background(if (filled) fill else surface)
            .then(if (!filled && outline) Modifier.border(1.dp, Palette.Outline, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (stateText != null) { stateDescription = stateText; liveRegion = LiveRegionMode.Polite }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (filled) Palette.White else Palette.Text, modifier = Modifier.size(iconSize)) }
}

/** A text link with a 48 dp target ("Show the change (2 lines)"). */
@Composable
fun LinkText(text: String, onClick: () -> Unit, bold: Boolean = false, color: Color = Palette.BlueText) {
    // At least 48 dp both ways, so a short word ("OK") is easy to hit the first time.
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            text, color = color,
            style = Type.Label.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        )
    }
}

// ---------- Switches and choices ----------

/** The boards' 52×32 switch; the whole row toggles it. */
@Composable
fun SwitchRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    on: Color = Palette.BlueLight,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        val track by animateColorAsState(if (checked) on else Palette.Outline, motionSpec(Motion.NEEDS_YOU_BAR_MS), label = "track")
        val thumb by animateDpAsState(if (checked) 20.dp else 0.dp, motionSpec(Motion.NEEDS_YOU_BAR_MS), label = "thumb")
        Box(Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(track).padding(3.dp)) {
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(thumb.roundToPx(), 0) }.size(26.dp).clip(CircleShape).background(Palette.White))
        }
    }
}

/** A round radio mark like the boards' (22 dp). */
@Composable
fun RadioMark(selected: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) Palette.BlueLight else Palette.Outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.BlueLight)) }
}

fun Modifier.choice(selected: Boolean, onClick: () -> Unit) = this.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)

// ---------- Chat pieces ----------

private val mineShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp)
private val theirShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp)

/** One of the owner's messages, with what became of it underneath. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MineBubble(
    text: String,
    modifier: Modifier = Modifier,
    bubble: Color = Palette.BlueBubble,
    dashed: Boolean = false,
    under: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("You", style = Type.Small, color = Palette.TextDim)
        io.github.odnwks.mimir.ui.theme.ChatText {
            Text(
                text, style = Type.Body, color = Palette.Text,
                modifier = Modifier.widthIn(max = 300.dp + 28.dp).clip(mineShape).background(bubble)
                    .then(if (dashed) Modifier.dashedBorder(Palette.BlueLight, 18.dp) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            )
        }
        if (under != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                under()
            }
        }
    }
}

/** A small icon and dim words ("Filed in Budget-App, Ideas"). */
@Composable
fun StatusWords(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.align(Alignment.Top).padding(top = 12.dp + firstLineCenter(Type.Small, 16.dp)).size(16.dp))
        Text(text, style = Type.Small, color = Palette.TextDim)
    }
}

/** A reply from Claude (or a free model), with its own play button. */
@Composable
fun TheirBubble(
    name: String,
    playLabel: String,
    /** Null: no play button (nothing to play). */
    onPlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 330.dp,
    background: Color = Palette.Card,
    border: Color = Palette.Line,
    /** The reply's words: then it has Read aloud (the phone's voice, or [onPlay]'s PC voice) and a long-press menu. */
    readText: String? = null,
    readId: String = "reply-${readText.hashCode()}",
    content: @Composable ColumnScope.() -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = Type.Small, color = Palette.TextDim)
            if (readText != null) ReadAloudButton(readId, readText, onPlay)
            else if (onPlay != null) RoundButton(Glyphs.Play, playLabel, onPlay, size = 48.dp, iconSize = 16.dp)
        }
        Box {
            io.github.odnwks.mimir.ui.theme.ChatText {
                Column(
                    Modifier.widthIn(max = maxWidth + 28.dp).clip(theirShape).background(background).border(1.dp, border, theirShape)
                        .then(if (readText != null) Modifier.longPressMenu { menu = true } else Modifier)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
            }
            if (readText != null) ReplyMenu(menu, { menu = false }, readId, readText, onPlay)
        }
    }
}

/** A long press opens a menu (a plain tap does nothing), for a message's Read aloud and Copy. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.longPressMenu(open: () -> Unit): Modifier =
    combinedClickable(onClickLabel = null, onLongClickLabel = "Read aloud or copy", onLongClick = open, onClick = {})

fun Modifier.dashedBorder(color: Color, radius: Dp) = drawBehind {
    drawRoundRect(
        color = color, cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

/** The rounded text box with a hidden label for TalkBack. */
@Composable
fun MessageBox(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    background: Color = Palette.Bar,
    minLines: Int = 1,
    maxLines: Int = 6,
    shape: Shape = RoundedCornerShape(24.dp),
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    /** A box the person came here to type in takes the cursor and the keyboard by itself (when Settings allows it). */
    autoFocus: Boolean = false,
) {
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val keys = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    if (autoFocus) androidx.compose.runtime.LaunchedEffect(Unit) {
        // After a sheet has put the keyboard away as it opens.
        kotlinx.coroutines.delay(150)
        if (keyboardAutoOn(ctx)) runCatching { focus.requestFocus(); keys?.show() }
    }
    // A box with words keeps the part being changed in view above the keyboard.
    val inView = androidx.compose.runtime.remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val keyboard = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    androidx.compose.runtime.LaunchedEffect(keyboard, value.length / 40) {
        if (value.isNotEmpty()) { kotlinx.coroutines.delay(60); runCatching { inView.bringIntoView() } }
    }
    BasicTextField(
        value = value,
        onValueChange = onValue,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = Type.Body.copy(color = Palette.Text, lineHeight = 16.sp * 1.4f),
        cursorBrush = SolidColor(Palette.BlueLight),
        minLines = minLines,
        maxLines = maxLines,
        modifier = modifier.heightIn(min = 48.dp).focusRequester(focus).bringIntoViewRequester(inView).semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier.clip(shape).background(background).border(1.dp, Palette.Outline, shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = Type.Body.copy(lineHeight = 16.sp * 1.4f), color = Palette.TextDim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

// ---------- The Needs-you bar and the bottom bar ----------

fun needsYouWords(n: Int) = if (n == 1) "1 thing needs you" else "$n things need you"

/**
 * The amber bar: every waiting question and permission, one tap away. It grows from zero height
 * while fading in, so the list above slides up to make room instead of jumping.
 */
@Composable
fun NeedsYouBar(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // The last count stays on the bar while it shrinks away.
    val kept = androidx.compose.runtime.remember { intArrayOf(count) }
    if (count > 0) kept[0] = count
    AnimatedVisibility(
        visible = count > 0,
        enter = expandVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS), expandFrom = Alignment.Top) + fadeIn(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
        exit = shrinkVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS), shrinkTowards = Alignment.Top) + fadeOut(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
    ) {
        Box(modifier) {
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(Palette.Amber)
                    .clickable(role = Role.Button, onClick = onClick)
                    // TalkBack hears it when it appears or its count changes.
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Glyphs.Alert, null, tint = Palette.AmberInk, modifier = Modifier.size(22.dp))
                Text(needsYouWords(kept[0]), style = TextStyle(fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold), color = Palette.AmberInk, modifier = Modifier.weight(1f))
                Icon(Glyphs.NextBold, null, tint = Palette.AmberInk, modifier = Modifier.size(20.dp))
            }
        }
    }
}

internal fun Tab.glyph() = when (this) {
    Tab.Chat -> Glyphs.Chat
    Tab.Status -> Glyphs.Gauge
    Tab.Updates -> Glyphs.Speaker
}

/** A small rounded label ("Running", "Free", "Needs you: 3"). */
@Composable
fun Tag(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Text(
        text, style = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold), color = fg,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** The bar pinned under a screen's scrolling content (Change's decisions, New task's Start). */
@Composable
fun PinnedBar(top: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.Bar)) {
        Spacer(Modifier.fillMaxWidth().size(1.dp).background(Palette.Line))
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, top = top, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** How far down a [size] mark sits to centre on the first line of [style]. */
@Composable
fun firstLineCenter(style: TextStyle, size: Dp): Dp = with(LocalDensity.current) {
    val line = if (style.lineHeight.isEm) style.fontSize * style.lineHeight.value else style.lineHeight
    ((line.toDp() - size) / 2).coerceAtLeast(0.dp)
}

/** The boards' 1 dp rounded border. */
fun Modifier.border16(color: Color, radius: Int = 16) = this.border(1.dp, color, RoundedCornerShape(radius.dp))

/** The little bar at the top of a sheet. */
@Composable
fun SheetHandle() {
    androidx.compose.foundation.layout.Spacer(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Outline))
}
