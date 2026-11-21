package io.github.odnwks.mimir.ui.adaptive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/** One line of a menu. [key] is a hint shown only while a keyboard is attached. */
data class MenuAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val key: String? = null,
)

/**
 * From 600 dp of width, a list of actions is a menu dropping from the button that was pressed
 * (or at the pointer, with [offset], for a right-click or long press) instead of a sheet 20 cm away.
 * Put it inside the same Box as its button. Esc and a tap outside close it.
 */
@Composable
fun AnchoredMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<MenuAction>,
    offset: DpOffset = DpOffset.Zero,
    showKeys: Boolean = false,
) {
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss, offset = offset,
        containerColor = Palette.Card, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(min = 220.dp, max = Limits.ButtonMax),
    ) {
        actions.forEach { a ->
            val color = if (a.danger) Palette.Red else Palette.Text
            DropdownMenuItem(
                text = { Text(a.label, color = color, style = Type.Body) },
                onClick = { onDismiss(); a.onClick() },
                leadingIcon = a.icon?.let { { Icon(it, null, tint = color, modifier = Modifier.size(22.dp)) } },
                trailingIcon = if (showKeys && a.key != null) ({ KeyHint(a.key) }) else null,
                modifier = Modifier.heightIn(min = Limits.Target).hoverLight(RoundedCornerShape(0.dp)),
            )
        }
    }
}

/** Dialog times from the motion table: fade and a 4 dp rise. */
const val DIALOG_IN_MS = 200

/**
 * From 600 dp of width, a choice that asks first (Stop, Release, End and forget, Unpair, Always allow) is
 * a dialog 520 dp wide in the middle of the window, with the page dimmed behind it.
 * The two answers sit side by side at the same size. The safe one is filled, last, starts with the
 * keyboard's ring and is the one Enter presses; Esc and a tap outside also choose it.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    risky: String,
    safe: String,
    onRisky: () -> Unit,
    onSafe: () -> Unit,
    danger: Boolean = true,
) {
    val reduce = LocalReduceMotion.current
    val shown = remember { Animatable(if (reduce) 1f else 0f) }
    val safeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { safeFocus.requestFocus() }
        shown.animateTo(1f, tween(DIALOG_IN_MS, easing = Motion.Ease))
    }
    val rise = with(LocalDensity.current) { 4.dp.toPx() }
    Dialog(onDismissRequest = onSafe, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = Limits.Dialog).fillMaxWidth()
                .graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * rise }
                .clip(RoundedCornerShape(20.dp)).background(Palette.Card)
                .border(1.dp, Palette.Line, RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = Palette.Text, style = Type.TitleSmall, modifier = Modifier.semantics { heading() })
            Text(body, color = Palette.TextSoft, style = Type.Body)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogButton(
                    risky, onRisky, Modifier.weight(1f),
                    fg = if (danger) Palette.Red else Palette.Text, bg = Color.Transparent,
                    border = if (danger) Palette.RedBorder else Palette.Outline,
                )
                DialogButton(safe, onSafe, Modifier.weight(1f).focusRequester(safeFocus), fg = Palette.White, bg = Palette.Blue, border = null, bold = true)
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, onClick: () -> Unit, modifier: Modifier, fg: Color, bg: Color, border: Color?, bold: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(min = 52.dp).focusRing(shape).clip(shape).background(bg)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .hoverLight(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = fg, textAlign = TextAlign.Center,
            style = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold),
        )
    }
}
