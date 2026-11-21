package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.audio.ReadSpeed
import io.github.odnwks.mimir.data.Heard
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.data.Update
import io.github.odnwks.mimir.data.UpdateTarget
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

const val UPDATES_EMPTY = "No updates yet. When $AI_NAME finishes something on your PC, you'll hear about it here."

/** "Play 2 new updates" (one: "Play 1 new update"). */
fun playNewWords(n: Int): String = "Play $n new ${if (n == 1) "update" else "updates"}"

/** An update's plain title: its first sentence. */
fun updateTitle(u: Update): String = u.text.substringBefore(". ").trimEnd('.')

/** "Recipe box · 6:40 am · 20 sec" as the board has it (project, when, length). */
fun updateLine(u: Update): String = "${u.project} · ${u.whenShort} · ${u.lengthSeconds} sec"

/** "Voice: Ingrid · Speed: normal" (a speed other than 100% is said as its percent). */
fun voiceLine(voice: String?, speed: Float): String =
    "Voice: ${voice ?: "this phone's"} · Speed: " + (if (ReadSpeed.snap(speed) == 1f) "normal" else ReadSpeed.percent(speed))

/** Updates (board 7): one big button to play everything new, then NEW and HEARD. */
@Composable
fun UpdatesScreen(state: StationState, station: Station, nav: Navigator) {
    val tap = LocalTapTracker.current
    val voice = rememberReadAloud()
    val new = state.updates.filter { it.heard == Heard.New }
    val heard = state.updates.filter { it.heard == Heard.Heard }
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        CenteredTitleBar("Updates", nav)
        // While the PC can't be reached: the same words as everywhere else.
        ConnectionStrip(state, Modifier.padding(bottom = 6.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // A newer app came with the PC's newer version: one line to About, where it can be fetched.
            if (state.appUpdate) Panel(Modifier.padding(bottom = 4.dp), background = Palette.BlueCard, border = Palette.BlueBorder, onClick = { nav.open(Screen.About, tap.last) }) {
                Text("A new version of this app is out", style = Type.BodyTight.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Palette.BlueText)
            }
            if (state.updates.isEmpty()) {
                Text(
                    UPDATES_EMPTY, style = Type.Body, color = Palette.TextSoft, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 28.dp),
                )
                if (!state.paired) BigButton("Connect your PC", Look.Primary, { nav.open(Screen.Pair, tap.last) }, height = 48.dp)
                return@Column
            }
            if (new.isNotEmpty()) {
                BigButton(playNewWords(new.size), Look.Primary, { station.playPause(new.first().id) }, icon = Glyphs.Play, height = 52.dp, radius = 16.dp)
                CapsLabel("NEW", Modifier.padding(top = 10.dp))
                new.forEach { UpdateRow(it, state, station, nav, isNew = true) }
            }
            if (heard.isNotEmpty()) {
                CapsLabel("HEARD", Modifier.padding(top = 10.dp))
                heard.forEach { UpdateRow(it, state, station, nav, isNew = false) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(voiceLine(voice.voiceName, state.player.speed) + " · ", style = Type.Small, color = Palette.TextDim)
            LinkText("Change", { nav.open(Screen.Voice, tap.last) })
        }
    }
}

@Composable
private fun UpdateRow(u: Update, state: StationState, station: Station, nav: Navigator, isNew: Boolean) {
    val playingThis = state.player.updateId == u.id && state.player.playing
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (isNew) Modifier else Modifier.alpha(0.6f)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.weight(1f).heightIn(min = 56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Open") { open(u.opens, nav) }.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(updateTitle(u), style = Type.BodyTight, color = Palette.Text, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(updateLine(u), style = Type.Small, color = Palette.TextDim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (isNew) Palette.Blue else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(role = Role.Button) { station.playPause(u.id) }
                .semantics { contentDescription = (if (playingThis) "Pause " else "Play ") + updateTitle(u) },
            contentAlignment = Alignment.Center,
        ) { Icon(if (playingThis) Glyphs.Pause else Glyphs.Play, null, tint = if (isNew) Palette.White else Palette.TextDim, modifier = Modifier.size(20.dp)) }
    }
}

private fun open(t: UpdateTarget, nav: Navigator) = when (t) {
    is UpdateTarget.TaskOf -> nav.open(Screen.Task(t.taskId))
    UpdateTarget.Incognito -> nav.open(Screen.IncognitoTask)
    UpdateTarget.Models -> nav.open(Screen.Models)
    is UpdateTarget.ProjectOf -> nav.open(Screen.Project(t.name))
    UpdateTarget.NeedsYou -> nav.open(Screen.NeedsYou)
}
