package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.devlog.DevLogRunner
import io.github.odnwks.mimir.ui.adaptive.forDevice
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

const val DEV_LOG_TITLE = "Notes for fixing problems"
const val DEV_LOG_CAPTION = "Keeps a week of notes on this phone (a month for errors) so problems can be fixed. Never your Whispers."
const val DEV_CHATS_TITLE = "Include my normal chats"
const val DEV_CHATS_CAPTION = "Also keeps your normal chats in these notes, so problems with answers can be seen. Whispers are never kept."

/** More › Labs: the developer log's two switches. Off deletes what each one wrote at once. */
@Composable
fun DeveloperLogRows() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(DevLogRunner.enabled(ctx)) }
    var chats by remember { mutableStateOf(DevLogRunner.chatsPref(ctx)) }
    Panel(padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), spacing = 10.dp) {
        SwitchRow(on, { on = it; DevLogRunner.setEnabled(ctx, it) }, Modifier.heightIn(min = 56.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(DEV_LOG_TITLE, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                Text(DEV_LOG_CAPTION.forDevice(), style = Type.Small, color = Palette.TextDim)
            }
        }
        SwitchRow(on && chats, { if (on) { chats = it; DevLogRunner.setChats(ctx, it) } }, Modifier.heightIn(min = 56.dp).alpha(if (on) 1f else 0.5f)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(DEV_CHATS_TITLE, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = if (on) Palette.Text else Palette.TextDim)
                Text(DEV_CHATS_CAPTION, style = Type.Small, color = Palette.TextDim)
            }
        }
    }
}
