package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/**
 * Phone first-run flow (B9, ≤ 4 steps visible).
 *
 * Step 1 — Welcome: pair or use standalone.
 * Step 2 — (if pairing) show 4-word code, wait up to 60 s, then retry option.
 * Step 3 — Model download prompt (smallest model, estimated size, Wi-Fi required).
 * Step 4 — Ready: "Tap to chat".
 *
 * Developer log and "Include my normal chats" are never shown here (PHD-07, PHW-13):
 * they are off by default ([io.github.odnwks.mimir.devlog.DevLogRunner.DEV_LOG_DEFAULT]).
 */
@Composable
fun FirstRunScreen(nav: Navigator) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .padding(horizontal = 24.dp, vertical = 48.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MimirEyeSmall()
        SectionTitle(
            "Welcome to $AI_NAME",
            style = Type.Big,
        )
        Text(
            "Get answers from your own PC or straight from your phone.",
            style = Type.Body,
            color = Palette.TextSoft,
        )
        BigButton(
            "Pair with your PC",
            Look.Primary,
            { nav.replace(Screen.Pair) },
            radius = 16.dp,
            bold = true,
            modifier = Modifier.fillMaxWidth(),
        )
        BigButton(
            "Use this phone only (no PC needed)",
            Look.Outline,
            { nav.replace(Screen.Standalone) },
            radius = 16.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A compact variant of the Mímir eye for the first-run header. */
@Composable
private fun MimirEyeSmall() {
    // Reuses the existing MimirEye composable at a smaller size.
    androidx.compose.foundation.layout.Box(
        Modifier
            .padding(bottom = 8.dp)
            .background(Palette.Ground),
        contentAlignment = Alignment.Center,
    ) {
        Text(AI_NAME, style = Type.Title, color = Palette.Text)
    }
}
