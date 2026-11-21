package io.github.odnwks.mimir.ui.adaptive

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.motionSpec

/**
 * One place on the rail: the phone's tab with the same icon and word.
 * [count] is the amber "needs you" number (Status); [dot] the blue "something unheard" dot (Updates).
 */
data class RailItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
    val count: Int = 0,
    val dot: Boolean = false,
    /** A key hint shown only while a keyboard is attached, e.g. "Ctrl+2". */
    val key: String? = null,
)

/** What a screen reader says for a rail item: its word, then what waits there. */
fun RailItem.spoken(): String = buildString {
    append(label)
    if (count == 1) append(", 1 thing needs you")
    if (count > 1) append(", $count things need you")
    if (dot) append(", new")
}

/** The rail's time to move its pill (tablet redesign, Section 8); the rail itself never moves. */
const val RAIL_MS = 180

/**
 * The rail (88 dp): at its top "Ask Claude", then the phone's tabs top to bottom, and Settings at its foot.
 * It shows from 600 dp of window width and stays put when the keyboard opens.
 */
@Composable
fun Rail(
    items: List<RailItem>,
    settings: RailItem?,
    modifier: Modifier = Modifier,
    /** Puts the cursor in the message box (opening Chat first if needed). Null leaves the button out. */
    onAsk: (() -> Unit)? = null,
    askIcon: ImageVector? = null,
    showKeys: Boolean = false,
) {
    Row(modifier.fillMaxHeight()) {
        Column(
            Modifier.width(PaneRules.RAIL.dp).fillMaxHeight().background(Palette.Bar)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                .padding(vertical = 12.dp).selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onAsk != null) AskButton(onAsk, askIcon)
            items.forEach { RailEntry(it, showKeys) }
            Spacer(Modifier.weight(1f))
            if (settings != null) RailEntry(settings, showKeys)
        }
        Spacer(Modifier.width(1.dp).fillMaxHeight().background(Palette.Line))
    }
}

@Composable
private fun AskButton(onAsk: () -> Unit, icon: ImageVector?) {
    Column(
        Modifier.width(72.dp).heightIn(min = 64.dp).focusRing(RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)).background(Palette.Blue)
            .hoverLight(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onAsk)
            .clearAndSetSemantics { contentDescription = "Ask"; role = Role.Button }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        if (icon != null) Icon(icon, null, tint = Palette.White, modifier = Modifier.size(22.dp))
        RailWord("Ask", Palette.White, bold = true, lines = 2)
    }
}

@Composable
private fun RailEntry(item: RailItem, showKeys: Boolean) {
    val on = item.selected
    val pill by animateColorAsState(if (on) Palette.BlueBubble else Color.Transparent, motionSpec(RAIL_MS), label = "rail pill")
    val spoken = item.spoken()
    Column(
        Modifier.width(80.dp).heightIn(min = 64.dp).focusRing(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .hoverLight(RoundedCornerShape(12.dp))
            .selectable(selected = on, role = Role.Tab, onClick = item.onClick)
            .semantics(mergeDescendants = true) { selected = on; contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(pill), contentAlignment = Alignment.Center) {
                Icon(item.icon, null, tint = if (on) Palette.White else Palette.TextDim, modifier = Modifier.size(22.dp))
            }
            if (item.count > 0) {
                Box(
                    Modifier.heightIn(min = 20.dp).clip(CircleShape).background(Palette.Amber).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (item.count > 99) "99+" else item.count.toString(), color = Palette.AmberInk,
                        style = TextStyle(fontSize = fixedSp(13f), lineHeight = fixedSp(16f), fontWeight = FontWeight.Bold),
                    )
                }
            } else if (item.dot) {
                Box(Modifier.padding(top = 2.dp, end = 8.dp).size(10.dp).clip(CircleShape).background(Palette.BlueLight))
            }
        }
        RailWord(item.label, if (on) Palette.White else Palette.TextDim, bold = on)
        if (showKeys && item.key != null) KeyHint(item.key)
    }
}

/** Rail words grow with the text size up to 1.2×, like the bottom bar's, so they are never cut off. */
@Composable
private fun RailWord(text: String, color: Color, bold: Boolean, lines: Int = 1) {
    Text(
        text, color = color, textAlign = TextAlign.Center, maxLines = lines, softWrap = lines > 1,
        overflow = TextOverflow.Visible,
        style = TextStyle(fontSize = cappedSp(14f, 1.2f), lineHeight = cappedSp(18f, 1.2f), fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    )
}

/** A small key shape on a button ("Ctrl+2"). Shown only while a keyboard is attached. */
@Composable
fun KeyHint(keys: String, modifier: Modifier = Modifier) {
    Text(
        keys, color = Palette.TextDim,
        style = TextStyle(fontSize = fixedSp(12f), lineHeight = fixedSp(16f), fontWeight = FontWeight.Medium),
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Palette.Card).padding(horizontal = 6.dp, vertical = 1.dp)
            .clearAndSetSemantics { },
    )
}

/** [size] sp that grows with the text size only up to [cap] times. */
@Composable
internal fun cappedSp(size: Float, cap: Float) = with(LocalDensity.current) { (size * minOf(fontScale, cap) / fontScale).sp }

/** [size] sp that does not grow with the text size (badges and key shapes, which have no room to). */
@Composable
internal fun fixedSp(size: Float) = with(LocalDensity.current) { (size / fontScale).sp }
