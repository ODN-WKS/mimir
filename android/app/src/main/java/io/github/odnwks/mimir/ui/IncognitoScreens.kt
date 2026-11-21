package io.github.odnwks.mimir.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.ui.theme.motionSpec

/** An Incognito task (board 11): purple, with the internet cut off and history deleted at the end. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IncognitoTaskScreen(state: StationState, station: Station, nav: Navigator) {
    NoScreenshots()
    val task = state.incognito ?: return MissingScreen("The Whisper task has ended", nav)
    val standIn = LocalStandIn.current
    var draft by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(WhisperTint.Ground)) {
        BackHeader(
            task.title, nav::back, titleStyle = Type.TaskTitle,
            subtitleContent = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.WhisperChip).padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        MimirEye(EyeMode.Whisper, Modifier.size(24.dp))
                        Text("Whisper mode", style = Type.Small.copy(fontWeight = FontWeight.Bold), color = Palette.WhisperTitle)
                    }
                    Text("${task.project} · ${task.model} on your PC", style = Type.Small, color = Palette.TextDim)
                }
            },
            onTitle = { nav.openSheet(Sheet.IncognitoOptions) }, titleLabel = TASK_OPTIONS,
        )
        Column(Modifier.padding(horizontal = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            WhisperCard(web = false, paired = true)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MineBubble(task.prompt, bubble = WhisperTint.Bubble)
            val ask = state.needsYou.firstOrNull { it.taskId == task.id && task.id.isNotEmpty() }
            if (ask != null) Panel(background = Palette.WhisperPanel, border = Palette.WhisperReplyLine, spacing = 10.dp) {
                Text(task.asking ?: ask.text, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.WhisperTitle)
                BigButton("Allow", Look.Whisper, { station.answer(ask.id, io.github.odnwks.mimir.data.Answer.Allow) }, height = 48.dp)
                BigButton("Don't allow", Look.WhisperOutline, { station.answer(ask.id, io.github.odnwks.mimir.data.Answer.DontAllow) }, height = 48.dp)
            }
            if (task.reply.isNotEmpty()) {
                TheirBubble(
                    "${task.model.substringBefore(' ')}, on your PC", "", null,
                    background = Palette.WhisperReplyCard, border = Palette.WhisperReplyLine,
                    // A Whisper is read by the phone's own voice only.
                    readText = markdownPlain(task.reply), readId = "whisper-reply",
                ) { MarkdownText(task.reply, style = Type.Body, color = Palette.Text, linksOpen = false) }
            }
            Panel(background = Palette.WhisperPanel, border = Palette.WhisperReplyLine, spacing = 10.dp) {
                SectionTitle("When you're done", color = Palette.WhisperTitle, style = Type.Label.copy(fontWeight = FontWeight.Bold))
                Text(
                    "Ending the Whisper forgets it: nothing of it is kept on the PC or this phone. Changed files stay changed.",
                    style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.45f), color = Palette.WhisperSoft,
                )
                BigButton("End and forget…", Look.WhisperOutline, { nav.openSheet(Sheet.EndIncognito) }, height = 48.dp, fontSize = 15, bold = true)
            }
        }
        Spacer(Modifier.fillMaxWidth().size(1.dp).background(Palette.WhisperBar))
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MessageBox(draft, { draft = it }, "Reply to this Whisper task", "Reply", Modifier.weight(1f), background = Palette.WhisperPanel)
            if (task.id.isNotEmpty()) RoundButton(Glyphs.Send, "Send reply", {
                if (draft.isNotBlank()) station.reply(task.id, draft)
                draft = ""
            }, filled = draft.isNotBlank(), fill = Palette.WhisperButton, surface = Palette.Card)
        }
    }
}

/** Incognito screens can't be captured: no screenshots, no preview in recent apps. */
@Composable
fun NoScreenshots() {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity ?: return
    androidx.compose.runtime.DisposableEffect(activity) {
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
