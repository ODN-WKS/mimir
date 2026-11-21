package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.Answer
import io.github.odnwks.mimir.data.DiffKind
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/** The change Claude asks to make (board 4), with the decision pinned at the bottom. */
@Composable
fun ChangeScreen(taskId: String, state: StationState, station: Station, nav: Navigator) {
    val task = state.task(taskId)
    val change = task?.change ?: return MissingScreen("The change", nav)
    val permission = task.permission
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader(
            "The change", nav::back, backLabel = "Back to the task",
            subtitle = "${change.file} · ${change.lines.count { it.kind != DiffKind.Context }} lines · ${task.project}",
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Panel(spacing = 6.dp) {
                SectionTitle("What it does", color = Palette.TextDim, style = Type.Label.copy(fontWeight = FontWeight.Bold))
                Text(change.what, style = Type.Body, color = Palette.Text)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("The lines", color = Palette.TextDim, style = Type.Label.copy(fontWeight = FontWeight.Bold))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border16(Palette.LineStrong)) {
                    change.lines.forEach { line ->
                        val (bg, fg, mark) = when (line.kind) {
                            DiffKind.Context -> Triple(Palette.DiffContext, Palette.TextDim, null)
                            DiffKind.Removed -> Triple(Palette.DiffRemoved, Palette.DiffRemovedText, "−" to "Removed")
                            DiffKind.Added -> Triple(Palette.DiffAdded, Palette.DiffAddedText, "+" to "Added")
                        }
                        Row(
                            Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 8.dp)
                                .semantics(mergeDescendants = true) {},
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (mark != null) Text(mark.first, style = Type.Mono.copy(fontWeight = FontWeight.Bold), color = fg,
                                modifier = Modifier.semantics { contentDescription = mark.second })
                            Text(line.text, style = Type.Mono, color = fg)
                        }
                    }
                }
            }
            Panel {
                SectionTitle("Checks that cover this", color = Palette.TextDim, style = Type.Label.copy(fontWeight = FontWeight.Bold))
                Text(change.checks, style = Type.Label, color = Palette.Text)
                Text(change.checksNote, style = Type.Small, color = Palette.TextDim)
            }
        }
        if (permission != null) PinnedBar {
            BigButton("Allow this edit", Look.Primary, { station.answer(permission.itemId, Answer.Allow); nav.back() })
            BigButton("Don't allow", Look.Outline, { station.answer(permission.itemId, Answer.DontAllow); nav.back() })
        }
    }
}
