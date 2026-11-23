package io.github.odnwks.mimir.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.alerts.AlertChoices
import io.github.odnwks.mimir.alerts.AlertStore
import io.github.odnwks.mimir.alerts.Notifier
import io.github.odnwks.mimir.alerts.OutsideMode
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/** The words of "What to tell me" (kept in one place so the tests and the search read the same). */
object AlertWords {
    const val TITLE = "What to tell me"
    const val NEEDS = "Needs you"
    const val NEEDS_NOTE = "Questions and permissions. So a task never waits on you without you knowing."
    const val FINISHED = "A task finished"
    const val FINISHED_NOTE = "One note for each task, when it is done."
    const val OUTSIDE = "Sessions from other apps on your PC"
    const val OUTSIDE_NOTE = "Work started outside Mímir, like in a terminal. Your PC can tell when one waits for you in most cases, not in every one."
    const val UPDATES = "Updates to Mímir"
    const val UPDATES_NOTE = "Quiet. A new version is ready."
    const val WORKING = "Show \"Working\" while a task runs"
    const val WORKING_NOTE = "Keeps alerts instant. Off, there is no ongoing note, and alerts can come a few minutes later."
    const val SOUNDS = "Sounds and vibration for each kind: Android's settings"
}

/** Settings > Notifications > What to tell me: one plain switch per kind; each applies at once and is kept. */
@Composable
fun AlertChoicesSection() {
    val context = LocalContext.current
    var c by remember { mutableStateOf(AlertStore.get(context)) }
    fun change(f: (AlertChoices) -> AlertChoices) {
        c = AlertStore.change(context, f)
        Notifier.refreshWorking(context)
        if (!c.showWorking) io.github.odnwks.mimir.alerts.LinkKeepService.stopNow(context)
    }
    SectionTitle(AlertWords.TITLE, Modifier.padding(start = 8.dp, top = 4.dp))
    Panel(background = Palette.Card, border = Palette.Line) {
        Switch(AlertWords.NEEDS, AlertWords.NEEDS_NOTE, c.needsYou) { v -> change { it.copy(needsYou = v) } }
        Switch(AlertWords.FINISHED, AlertWords.FINISHED_NOTE, c.taskFinished) { v -> change { it.copy(taskFinished = v) } }
        Text(AlertWords.OUTSIDE, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.padding(top = 8.dp))
        Text(AlertWords.OUTSIDE_NOTE, style = Type.Label, color = Palette.TextSoft)
        OutsideMode.entries.forEach { m ->
            val on = c.outside == m
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).choice(on) { change { it.copy(outside = m) } }.semantics { role = Role.RadioButton },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioMark(on)
                Text(m.title, style = Type.Body, color = Palette.Text)
            }
        }
        Switch(AlertWords.UPDATES, AlertWords.UPDATES_NOTE, c.updates) { v -> change { it.copy(updates = v) } }
        Switch(AlertWords.WORKING, AlertWords.WORKING_NOTE, c.showWorking) { v -> change { it.copy(showWorking = v) } }
    }
    Text(
        AlertWords.SOUNDS,
        style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.BlueText,
        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp).fillMaxWidth()
            .clickable(role = Role.Button) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
    )
}

@Composable
private fun Switch(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(checked, onChange, Modifier.heightIn(min = 56.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
            Text(note, style = Type.Label, color = Palette.TextSoft)
        }
    }
}
