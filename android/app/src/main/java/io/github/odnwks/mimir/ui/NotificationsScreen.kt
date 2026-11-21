package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.data.Answer
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.NeedsKind
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.data.StayMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/**
 * Notifications (board 9): how things look on the lock screen. The real notifications come with
 * the wake-up push; until then this page shows them, and its buttons do what theirs will.
 */
@Composable
fun NotificationsScreen(state: StationState, station: Station, nav: Navigator) {
    val standIn = LocalStandIn.current
    val permission = state.needsYou.firstOrNull { it.kind == NeedsKind.Permission }
    val fix = state.needsYou.firstOrNull { it.kind == NeedsKind.CheckFix }
    val newest = state.updates.firstOrNull { it.heard == io.github.odnwks.mimir.data.Heard.New }
    Column(Modifier.fillMaxSize().background(Palette.NotificationGround)) {
        BackHeader("Notifications", nav::back, backLabel = "Back to Settings")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Staying in touch", Modifier.padding(start = 8.dp, top = 4.dp))
            StayMode.entries.forEach { m ->
                val on = state.stayMode == m
                Panel(
                    background = if (on) Palette.BlueCard else Palette.Card,
                    border = if (on) Palette.BlueBorder else Palette.Line,
                    onClick = { station.setStayMode(m) },
                    modifier = Modifier.semantics { selected = on; role = Role.RadioButton },
                ) {
                    Text(m.title, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                    Text(m.about, style = Type.Label, color = Palette.TextSoft)
                }
            }
            Text(
                // One plain line: when something that needs you reaches this phone (Stage 1, 4.4).
                when {
                    state.stayMode == io.github.odnwks.mimir.data.StayMode.BatterySaver -> "With the app closed, things that need you show when you open it."
                    else -> "With the app closed, things that need you show within about 5 minutes."
                },
                style = Type.Label, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                if (state.stayMode == io.github.odnwks.mimir.data.StayMode.BatterySaver)
                    "Battery saver: things that need you still show on the lock screen with a sound; other updates wait until you open the app."
                else "Alerts show on the lock screen, even with the app closed. Things that need you make a sound, and updates stay quiet.",
                style = Type.Label, color = Palette.TextDim, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            )
            // The cards below show how alerts look, made from what's waiting now; said plainly so
            // nobody takes them for alerts of their own.
            if (permission != null || fix != null || newest != null || state.player.updateId != null) {
                SectionTitle("What alerts look like", Modifier.padding(start = 8.dp, top = 8.dp))
                Text(
                    "Samples, made from what's waiting now. Their buttons work like the real ones.",
                    style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (permission != null) Notice("${permission.project} · Needs you · ${permission.age}", true, permission.text, "Mímir is paused until you answer.") {
                permission.taskId?.let { id -> Action("Open the task") { nav.open(Screen.Task(id)) } }
            }
            if (fix != null) Notice("${fix.project} · Needs you · ${fix.age}", true, fix.text, "Tell Mímir whether it worked.") {
                Action("It works") { station.answer(fix.id, Answer.ItWorks) }
                Action("Still broken") { station.answer(fix.id, Answer.StillBroken) }
            }
            NowPlaying(state, station)
            Text(
                "The Allow and Don't allow buttons on an alert open $AI_NAME first, and you unlock this phone before anything happens. The pause and skip buttons on your headphones control the update that is playing.".forDevice(),
                style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Notice(head: String, loud: Boolean, title: String, body: String, actions: @Composable () -> Unit) {
    Panel(
        background = Palette.Notification, border = Color.Transparent, borderWidth = 0.dp, radius = 22.dp,
        padding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp), spacing = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (loud) Palette.Amber else Palette.Track),
                contentAlignment = Alignment.Center,
            ) {
                if (loud) Text("!", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = Palette.AmberInk)
                else Icon(Glyphs.SpeakerLow, null, tint = Palette.Text, modifier = Modifier.size(14.dp))
            }
            Text(head, style = Type.Small, color = Palette.TextDim)
        }
        SectionTitle(title, style = Type.BodyTight.copy(fontWeight = FontWeight.Bold))
        Text(body, style = Type.Label, color = Palette.TextSoft)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
    }
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.BlueText) }
}

@Composable
private fun ColumnScope.NowPlaying(state: StationState, station: Station) {
    val u = state.updates.firstOrNull { it.id == state.player.updateId } ?: return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.NotificationPlayer).padding(14.dp)
            .semantics { contentDescription = "Now playing" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Now playing · ${u.project} update", style = Type.Small, color = Palette.TextDim)
        Text(u.text.substringBefore(". ").trimEnd('.'), style = Type.BodyTight.copy(fontWeight = FontWeight.Bold), color = Palette.Text, maxLines = 2)
        Bar(state.player.positionSeconds.toFloat() / u.lengthSeconds, 6.dp, Palette.NotificationTrack, Palette.BlueText)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Glyphs.Back10, "Back 10 seconds", station::back10, surface = Color.Transparent)
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Palette.Text)
                    .clickable(role = Role.Button) { station.playPause(u.id) }
                    .semantics { contentDescription = if (state.player.playing) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) { Icon(if (state.player.playing) Glyphs.Pause else Glyphs.Play, null, tint = Palette.Ground, modifier = Modifier.size(22.dp)) }
            RoundButton(Glyphs.SkipNext, "Next update", station::playNext)
        }
    }
}
