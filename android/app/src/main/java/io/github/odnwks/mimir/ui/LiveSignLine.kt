package io.github.odnwks.mimir.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.LiveSign
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/**
 * The live line under a task: never blank while something is going on. A soft pulse (a fade that
 * cannot freeze the way a spinner can) and the words from [LiveSign], redrawn every second so the
 * time it has worked counts up and a long silence shows.
 */
@Composable
fun LiveSignLine(sign: LiveSign?, modifier: Modifier = Modifier) {
    if (sign == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sign) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val line = sign.line(now) ?: return
    val pulse by rememberInfiniteTransition(label = "live-sign").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).alpha(pulse).background(Palette.TextSoft, CircleShape))
        Text(line, style = Type.Small, color = Palette.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
