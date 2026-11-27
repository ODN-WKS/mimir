package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.standalone.Skill
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

const val SKILL_CHIP_TAG = "skill-chip"
const val SKILL_ROW_TAG = "skill-row"
const val SKILL_SHEET_TITLE = "Skill for this message"
const val SKILL_FOOT = "No skill needed for weather, quick facts or general knowledge: $AI_NAME handles those right away."

/** The room always left for the skill bubble, so it is never pushed out of the row. */
private val SKILL_ROOM = 56.dp

/** The Model · Effort bubble and the skill bubble side by side: the first gets its room first, so it stays whole. */
@Composable
fun ChipPair(model: @Composable () -> Unit, skill: @Composable () -> Unit) {
    BoxWithConstraints {
        val room = maxWidth
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.widthIn(max = (room - SKILL_ROOM - 6.dp).coerceAtLeast(0.dp))) { model() }
            Box(Modifier.weight(1f, fill = false)) { skill() }
        }
    }
}

/** "Skill", or the picked skill's name, highlighted; the longest form that fits. */
@Composable
fun SkillChip(skill: Skill?, onClick: () -> Unit) {
    BoxWithConstraints {
        val measurer = rememberTextMeasurer()
        val style = Type.Small.copy(fontWeight = FontWeight.Bold)
        val padding = with(LocalDensity.current) { 18.dp.roundToPx() }
        val forms = if (skill == null) listOf("Skill") else listOf("Skill: ${skill.title}", skill.title, skill.short)
        val words = forms.firstOrNull { measurer.measure(it, style, maxLines = 1).size.width <= constraints.maxWidth - padding } ?: forms.last()
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier.testTag(SKILL_CHIP_TAG).heightIn(min = 40.dp).clip(shape).clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = if (skill == null) "Skill: none. Choose" else "Skill: ${skill.title}. Change" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                words, style = style, color = if (skill == null) Palette.Text else Palette.BlueText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(shape).background(if (skill == null) Color.Transparent else Palette.BlueCard)
                    .border(1.dp, if (skill == null) Palette.LineStrong else Palette.BlueBorder, shape).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

fun skillIcon(skill: Skill?): ImageVector = when (skill) {
    null -> Glyphs.SkillNone
    Skill.Deep -> Glyphs.SkillResearch
    Skill.Shopping -> Glyphs.SkillShopping
    Skill.Writing -> Glyphs.SkillWriting
    Skill.Teach -> Glyphs.SkillTeach
    Skill.People -> Glyphs.SkillPeople
    Skill.Summarize -> Glyphs.SkillSummarize
    Skill.Trace -> Glyphs.SkillTrace
}

/** None, then every skill that works today. */
@Composable
fun SkillRows(chosen: Skill?, onPick: (Skill?) -> Unit) {
    (listOf<Skill?>(null) + Skill.entries).forEach { s ->
        val on = s == chosen
        Row(
            Modifier.fillMaxWidth().testTag(SKILL_ROW_TAG).heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                .background(if (on) Palette.BlueCard else Color.Transparent)
                .selectable(on, role = Role.RadioButton) { onPick(s) }.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(30.dp).background(Palette.Ground, CircleShape), contentAlignment = Alignment.Center) {
                Icon(skillIcon(s), null, tint = Palette.BlueText, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(s?.title ?: "None", style = Type.BodyTight, color = Palette.Text)
                Text(s?.about ?: "Just answer", style = Type.Small, color = Palette.TextDim)
            }
            if (on) Icon(Glyphs.Check, "Picked", tint = Palette.BlueText, modifier = Modifier.size(20.dp))
        }
    }
    Column { Text(SKILL_FOOT, style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) }
}
