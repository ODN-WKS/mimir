package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * A task on a project's page: tap opens it; a long press gives Archive and Delete; pulling the row
 * left uncovers the same two buttons (nothing happens by the swipe alone). TalkBack has both as
 * actions. Archive can be undone at once; Delete asks first and can be undone for 8 seconds.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    id: String, title: String, meta: String?, state: String, stateFg: Color, stateBg: Color,
    onOpen: (() -> Unit)?, onArchive: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier,
) {
    var menu by rememberSaveable(id) { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val buttonDp = 84.dp
    val reveal = with(LocalDensity.current) { (buttonDp * 2).toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val settle = { to: Float -> scope.launch { if (reduce) offset.snapTo(to) else offset.animateTo(to, tween(Motion.CARD_OPEN_MS, easing = Motion.Ease)) } }
    Box(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (offset.value < -1f) Row(Modifier.matchParentSizeSafe(), horizontalArrangement = Arrangement.End) {
            SwipeButton("Archive", Palette.Track, Palette.Text, buttonDp, "Archive $title") { settle(0f); onArchive() }
            SwipeButton("Delete", Palette.RedBorder, Palette.Text, buttonDp, "Delete $title") { settle(0f); onDelete() }
        }
        val density = LocalDensity.current
        Box(
            Modifier.padding(end = with(density) { (-offset.value).toDp() })
                .draggable(
                    rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(-reveal, 0f)) } },
                    Orientation.Horizontal,
                    onDragStopped = { v -> settle(if (offset.value < -reveal / 2 || v < -800f) -reveal else 0f) },
                ),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Card)
                    .border(1.dp, Palette.Line, RoundedCornerShape(16.dp))
                    .combinedClickable(
                        role = Role.Button, onLongClickLabel = "Archive or delete", onLongClick = { menu = true },
                        onClick = { if (offset.value < -1f) settle(0f) else onOpen?.invoke() },
                    )
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Archive") { onArchive(); true },
                            CustomAccessibilityAction("Delete") { onDelete(); true },
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title.forDevice(), style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                    if (meta != null) Text(meta.forDevice(), style = Type.Small, color = Palette.TextDim)
                }
                Tag(state, stateFg, stateBg)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Archive") }, { menu = false; onArchive() })
                DropdownMenuItem({ Text("Delete", color = Palette.Red) }, { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun SwipeButton(text: String, bg: Color, fg: Color, width: androidx.compose.ui.unit.Dp, description: String, onClick: () -> Unit) {
    Box(
        Modifier.width(width).fillMaxHeight().padding(start = 4.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(role = Role.Button, onClickLabel = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = fg, modifier = Modifier.clearAndSetSemantics {}) }
}

private fun Modifier.matchParentSizeSafe(): Modifier = this.fillMaxWidth().fillMaxHeight()
