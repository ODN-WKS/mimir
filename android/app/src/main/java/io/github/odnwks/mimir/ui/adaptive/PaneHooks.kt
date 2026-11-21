package io.github.odnwks.mimir.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/**
 * Whether the screen's own pane has somewhere to go back to. False for what the right pane shows
 * on its own (Needs you beside Chat): its back arrow is left out instead of doing nothing.
 */
val LocalPaneBack = compositionLocalOf { true }

/** Goes up each time "Ask Claude" asks for the cursor; Chat's message box takes it. 0 means never asked. */
val LocalAskTick = compositionLocalOf { 0 }

/** The right pane before anything is picked on the left: one quiet line saying what goes there. */
@Composable
fun PanePlaceholder(line: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(Palette.Ground).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(line, style = Type.Label, color = Palette.TextDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = Limits.Dialog))
    }
}
