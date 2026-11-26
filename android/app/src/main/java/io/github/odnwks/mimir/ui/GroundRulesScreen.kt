package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.data.GroundRules
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/** The Models row that opens the ground rules, with whether they're on. */
@Composable
fun GroundRulesRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val r by GroundRules.rules.collectAsStateWithLifecycle()
    ListRow(
        "Rules for every chat", (if (r.sent != null) "On" else "Off") + " · what every answer should follow",
        onClick, modifier,
    )
}

/**
 * Settings › Who answers › Ground rules: on by default, the text to edit, Reset to default, and what
 * the rules mean for Claude and for the other models. Changes count from the next message.
 */
@Composable
fun GroundRulesScreen(nav: Navigator) {
    val r by GroundRules.rules.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        // Back always lands on Models, where the rules live, however they were reached (search, Privacy check, a chat).
        BackHeader("Rules for every chat", nav::back)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel {
                SwitchRow(r.on, { GroundRules.set(r.copy(on = it)) }) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Send these rules", style = Type.BodyTight, color = Palette.Text)
                        Text("Sent to $AI_NAME, Whispers and every task on your PC", style = Type.Small, color = Palette.TextDim)
                    }
                }
            }
            MessageBox(
                r.text, { GroundRules.set(r.copy(text = it)) }, "Rules for every chat", "What every answer should follow",
                Modifier.fillMaxWidth(), minLines = 4, maxLines = 20, shape = RoundedCornerShape(16.dp),
            )
            Text(GroundRules.NOTE, style = Type.Small.copy(lineHeight = Type.Small.fontSize * 1.45f), color = Palette.TextDim)
            if (r.text != GroundRules.DEFAULT) SmallButton("Reset to default", GroundRules::reset)
        }
    }
}
