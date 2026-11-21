package io.github.odnwks.mimir.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import io.github.odnwks.mimir.data.count
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.ui.theme.motionSpec

/** "Just chat", picked in the picker a chat's project card opened (not a project's name). */
const val JUST_CHAT = "\u0000just chat"

/** More than this many projects, and the picker gets a search box. */
private const val SEARCH_FROM = 7

/**
 * The project picker (a sheet from the bottom): the PC's projects, searchable, plus "New
 * project…", which makes the folder on the PC. Not paired, it says so, with a Pair button.
 */
@Composable
fun ProjectPicker(current: String, state: StationState, station: Station, nav: Navigator, newProject: Boolean = false, back: io.github.odnwks.mimir.nav.Sheet? = null, open: Boolean = false, justChat: Boolean = false) {
    val standIn = LocalStandIn.current
    var query by rememberSaveable { mutableStateOf("") }
    var naming by rememberSaveable { mutableStateOf(newProject) }
    var name by rememberSaveable { mutableStateOf("") }
    var working by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val pick = { p: String ->
        when {
            back == io.github.odnwks.mimir.nav.Sheet.WhereTo -> { nav.composeTo = nav.composeTo.copy(project = p); nav.openSheet(back) }
            open -> { nav.closeSheet(); nav.open(Screen.Project(p)) }
            else -> { nav.pickedProject = p; nav.closeSheet() }
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SheetHandle() }
        SectionTitle("Project", style = Type.Section.copy(fontWeight = FontWeight.Bold))

        if (!state.paired) {
            Text(
                "Pair with your PC to use its projects. New projects are made as folders on the PC.",
                style = Type.Label, color = Palette.TextSoft,
            )
            BigButton("Pair with your PC", Look.Primary, { nav.closeSheet(); nav.open(Screen.Pair) }, height = 48.dp)
            BigButton("Cancel", Look.Outline, { nav.closeSheet() }, height = 48.dp)
            return@Column
        }

        val online = state.pc.online
        if (!online) {
            Text(
                "Your PC is away: these are its projects from earlier.",
                style = Type.Small, color = Palette.TextDim,
            )
        }
        // A chat filed under a project by mistake: "Just chat" first (Stage 1, 2.3).
        if (justChat) Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).choice(false) { nav.pickedProject = JUST_CHAT; nav.closeSheet() }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioMark(false)
            Text("Just chat", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.weight(1f))
        }
        val all = state.pickable
        if (all.size >= SEARCH_FROM) {
            MessageBox(query, { query = it }, "Search projects", "Search projects", Modifier.fillMaxWidth())
        }
        val shown = all.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
        if (all.isEmpty()) {
            Text("No projects yet. Make one below.", style = Type.Label, color = Palette.TextDim)
        } else if (shown.isEmpty()) {
            Text("No project matches \"${query.trim()}\".", style = Type.Label, color = Palette.TextDim)
        }
        shown.forEach { p ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).choice(p == current) { pick(p) }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioMark(p == current)
                Text(p, style = Type.BodyTight, color = Palette.Text, modifier = Modifier.weight(1f))
            }
        }

        // "New project…": a name, then the PC makes the folder beside the others.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, enabled = !naming) { naming = true; problem = null }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Glyphs.Plus, null, tint = Palette.BlueText, modifier = Modifier.size(22.dp))
            Text("New project…", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.BlueText)
        }
        AnimatedVisibility(
            naming,
            enter = expandVertically(motionSpec(Motion.NEW_ITEM_MS), clip = false) + fadeIn(motionSpec(Motion.NEW_ITEM_MS)),
            exit = shrinkVertically(motionSpec(Motion.NEW_ITEM_MS), clip = false) + fadeOut(motionSpec(Motion.NEW_ITEM_MS)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val make = {
                    if (name.isNotBlank() && !working) {
                        working = true
                        problem = null
                        station.newProject(name) { made, message ->
                            working = false
                            if (made != null) {
                                naming = false
                                name = ""
                                pick(made)
                                standIn(message)
                            } else problem = message
                        }
                    }
                }
                MessageBox(
                    name, { name = it; problem = null }, "New project name", "Project name", Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { make() }),
                )
                Text(
                    if (online) "Made as a folder beside your other projects on the PC."
                    else "Your PC is away: new projects can be made when it's back.",
                    style = Type.Small, color = Palette.TextDim,
                )
                problem?.let { Text(it, style = Type.Small, color = Palette.Red) }
                BigButton(
                    if (working) "Making it…" else "Make the project", Look.Primary, make, height = 48.dp,
                    enabled = online && name.isNotBlank() && !working,
                )
            }
        }
        BigButton("Cancel", Look.Outline, { nav.closeSheet() }, height = 48.dp)
    }
}
