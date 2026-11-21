package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

const val STATUS_EXPLAINED = "Status shows what Mímir is doing on your PC: what needs your answer, the tasks at work, and how your projects' checks stand."
const val UPDATES_EXPLAINED = "Updates are short spoken notes from Mímir on your PC, each time it finishes something. You can listen to them here."

/** A PC tab before pairing: what it's for, Connect your PC, and that Mímir needs none of it. */
@Composable
fun ConnectYourPc(explained: String, nav: Navigator) {
    val tap = LocalTapTracker.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(explained, style = Type.Body, color = Palette.TextSoft)
        BigButton("Connect your PC", Look.Primary, { nav.open(Screen.Pair, tap.last) }, height = 48.dp)
        Text("Needs $AI_NAME on your PC. On the Chat tab, $AI_NAME works on this phone without one.", style = Type.Small, color = Palette.TextDim)
        LinkText("Chat with $AI_NAME", { nav.selectTab(Tab.Chat) })
    }
}
