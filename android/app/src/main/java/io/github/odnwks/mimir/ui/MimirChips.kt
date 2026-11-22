package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.standalone.Effort
import io.github.odnwks.mimir.standalone.LocalModel
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/** The chip's word when room is short: whole words that fit in [max] characters ("Qwen 3.6" from "Qwen 3.6 35B"); one long word is cut with an ellipsis. Never empty. */
fun chipShort(word: String, max: Int = 10): String {
    val w = word.trim()
    if (w.length <= max) return w
    val parts = w.split(' ').filter { it.isNotEmpty() }
    var out = ""
    for (p in parts) {
        val next = if (out.isEmpty()) p else "$out $p"
        if (next.length > max) break
        out = next
    }
    return out.ifEmpty { w.take(max - 1) + "…" }
}

/** What a chip says at this fit: the whole word with room, otherwise a short one (a chip never shows only an icon). */
private fun ChipFit.word(word: String): String = if (this < ChipFit.Tight) word else chipShort(word)

/**
 * Claude's model in plain words from what the PC reports ("claude-sonnet-4-6-20250929" gives "Sonnet 4.6",
 * "Opus 5.5", "Haiku", "Fable"): the family and its version, no dates or raw ids. Null when no family is named
 * ("Claude · paid" says nothing about which model answered).
 */
fun claudeModelWord(raw: String?): String? {
    val t = raw?.lowercase() ?: return null
    val family = listOf("sonnet", "opus", "haiku", "fable").firstOrNull { it in t } ?: return null
    val after = t.substringAfter(family)
    // Digits right after the family ("sonnet-4-6", "opus 5.5"), never an 8-digit date.
    val nums = Regex("(?<![0-9])([0-9]{1,2})(?![0-9])").findAll(after.substringBefore("20").ifEmpty { after }).map { it.value }.take(2).toList()
    val version = nums.joinToString(".")
    return family.replaceFirstChar { it.uppercase() } + if (version.isNotEmpty()) " $version" else ""
}

/** The model chip's word when Claude answers: "Auto-pick" until the PC says which model it uses. */
fun claudeChipWord(): String = "Auto-pick"

private const val pcPicks = "Your messages go to Anthropic."

/** One line each: the PC's words ("Quick", "Balanced", "Most capable"), and for Auto-pick what it does. */
fun claudeAbout(id: String, pcWords: String): String = if (id == "auto") "Mímir picks the model itself" else pcWords

/** The chip's word with Claude answering: the model picked for chats, else the one that last answered, else "Auto-pick". */
fun claudeChipWordFor(chosen: String?, @Suppress("UNUSED_PARAMETER") lastAnswer: String?): String = chosen ?: claudeChipWord()

/** The top bar's word for who answers: Claude's own name when the PC routes chats to Claude, else the PC. */
fun answererWord(whisper: Boolean, online: Boolean, answeringPc: Boolean, claudeAnswers: Boolean, serviceName: String? = null): String =
    when { whisper -> "Whisper"; online -> serviceName ?: "Online"; answeringPc && claudeAnswers -> "Online"; answeringPc -> "Your PC"; else -> "This phone" }

/** A model's short name for its chip ("Qwen 3.5 4B SOMPOA"). */
val LocalModel.chipName: String get() = name.substringBefore(',').trim()

/** The beginner word on the box's model chip: "Balanced", "Smartest" (a model of your own: its name). */
val LocalModel.chipWord: String get() = plainName.substringBefore(" ·").trim()

/**
 * The model chip on Mímir's message box: what answers, and a tap lists the models on this phone
 * (only those whose file is here) with More in Settings › Who answers.
 */
@Composable
fun ModelChip(current: LocalModel, installed: List<LocalModel>, onChoose: (String) -> Unit, fit: ChipFit = ChipFit.Full, onMore: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    // With little room (large text on a narrow phone) the word gets shorter: never only an icon.
    Box {
        Chip(fit.word(current.chipWord), "Model: ${current.chipName}. Change model", arrow = fit == ChipFit.Full) { open = true }
        DropdownMenu(open, { open = false }) { ModelRows(current, installed, { open = false; if (it != current.id) onChoose(it) }, { open = false; onMore() }) }
    }
}

/** The rows of the model list: each model on this phone (the current one checked), then More in Settings › Who answers. */
@Composable
fun ModelRows(current: LocalModel, installed: List<LocalModel>, onChoose: (String) -> Unit, onMore: () -> Unit) {
    installed.forEach { m ->
        DropdownMenuItem(
            { MenuLine(m.chipName, m.chipWord + " · " + m.sizeText + if (m.refusalsRemoved) " · no safety filter" else "", m.id == current.id) },
            { onChoose(m.id) },
            modifier = Modifier.semantics { selected = m.id == current.id },
        )
    }
    DropdownMenuItem({ Text("More in Settings › Who answers", style = Type.BodyTight, color = Palette.BlueText) }, onMore)
}

/**
 * The model chip when the PC or Claude answers: its short plain name ("Claude" when Claude does),
 * and a tap lists it with More in Settings › Who answers.
 */
@Composable
fun PcModelChip(
    word: String, about: String, fit: ChipFit = ChipFit.Full,
    /** "Last answer: Sonnet 4.6", when the PC reported which model answered. */
    lastAnswer: String? = null,
    /** Claude answering: a way back to the PC's own free model, right here. */
    onUseFree: (() -> Unit)? = null,
    /** Claude's choices as the PC offers them (empty: an older PC, so today's menu), the chosen one, and how to pick. */
    choices: List<io.github.odnwks.mimir.link.LChoice> = emptyList(), chosenId: String = "auto", onChoose: (String) -> Unit = {},
    onMore: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(fit.word(word), "Model: $word. Change model", arrow = fit == ChipFit.Full) { open = true }
        DropdownMenu(open, { open = false }) {
            if (choices.isEmpty()) DropdownMenuItem({ MenuLine(word, about, true) }, { open = false }, modifier = Modifier.semantics { selected = true })
            else {
                choices.forEach { c ->
                    DropdownMenuItem(
                        { MenuLine(c.name, c.about.ifBlank { null }?.let { claudeAbout(c.id, it) } ?: "", c.id == chosenId) },
                        { open = false; if (c.id != chosenId) onChoose(c.id) },
                        modifier = Modifier.semantics { selected = c.id == chosenId },
                    )
                }
                DropdownMenuItem({ Text("Applies to all chats on this PC. $pcPicks", style = Type.Small, color = Palette.TextDim) }, { open = false }, enabled = false)
            }
            if (lastAnswer != null) DropdownMenuItem({ Text("Last answer: $lastAnswer", style = Type.Small, color = Palette.TextDim) }, { open = false }, enabled = false)
            if (onUseFree != null) DropdownMenuItem({ MenuLine("Your PC's free model", "Quick answers that stay on your PC", false) }, { open = false; onUseFree() })
            DropdownMenuItem({ Text("More in Settings › Who answers", style = Type.BodyTight, color = Palette.BlueText) }, { open = false; onMore() })
        }
    }
}

/**
 * Paired, the model chip says where the next message goes (Stage 1, 2.2): the PC when it's
 * connected (the default), or this phone. Tapping it offers "Your PC when connected" or "This phone
 * only". A change of place fades (150 ms; at once with animations removed).
 */
@Composable
fun PlaceChip(usePc: Boolean, pcOnline: Boolean, pcName: String, onChoose: (Boolean) -> Unit, phoneCan: Boolean = true, fit: ChipFit = ChipFit.Full, onMore: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val here = if ((usePc || !phoneCan) && pcOnline) pcName else "This phone"
    Box {
        androidx.compose.animation.Crossfade(here, animationSpec = io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.RECEIPT_MS), label = "place") { words ->
            val pc = words != "This phone"
            val shown = when (fit) { ChipFit.Full -> words; ChipFit.Short, ChipFit.Tight -> if (pc) "PC" else "Phone"; ChipFit.Icons, ChipFit.Menu -> if (pc) "PC" else "Phone" }
            Chip(shown, "Answers come from: $words. Change where", icon = if (pc) Glyphs.Monitor else Glyphs.Phone, arrow = fit == ChipFit.Full) { open = true }
        }
        DropdownMenu(open, { open = false }) { PlaceRows(usePc, pcName, onChoose = { open = false; if (it != usePc && (it || phoneCan)) onChoose(it) }, phoneCan, onMore = { open = false; onMore() }) }
    }
}

/** The rows of the place list: the PC when connected, or this phone only (greyed when it can't run a model), then More. */
@Composable
fun PlaceRows(usePc: Boolean, pcName: String, onChoose: (Boolean) -> Unit, phoneCan: Boolean = true, onMore: () -> Unit) {
    DropdownMenuItem(
        { MenuLine("$pcName when connected", "Its stronger model; this phone answers when it can't be reached", usePc) },
        { onChoose(true) },
        modifier = Modifier.semantics { selected = usePc },
    )
    // A phone that can't run a model can't answer by itself: said, and not offered.
    DropdownMenuItem(
        { MenuLine("This phone only", if (phoneCan) "Nothing goes to your PC" else "This phone can't run a model", phoneCan && !usePc) },
        { onChoose(false) },
        enabled = phoneCan,
        modifier = Modifier.semantics { selected = phoneCan && !usePc },
    )
    DropdownMenuItem({ Text("More in Settings › Who answers", style = Type.BodyTight, color = Palette.BlueText) }, onMore)
}

/** The small grey mark under an answer: where it ran (Stage 1, 2.2). Null when nothing is known. */
fun answerMark(ran: String?, pcName: String, whisper: Boolean): Pair<androidx.compose.ui.graphics.vector.ImageVector, String>? = when {
    whisper -> Glyphs.Lock to "Whisper · this phone"
    ran == "pc" -> Glyphs.Monitor to pcName
    ran == "phone" -> Glyphs.Phone to "This phone"
    ran == "paid" -> Glyphs.Monitor to "Online"
    else -> null
}

/** The effort chip: Quick, Balanced or Thorough. */
@Composable
fun EffortChip(current: Effort, model: LocalModel, onChoose: (Effort) -> Unit, fit: ChipFit = ChipFit.Full) {
    // It always says "Effort:" (the owner asked for it): it never moves into the + menu.
    var open by remember { mutableStateOf(false) }
    Box {
        // "Effort:" stays while it fits; with the least room the word alone ("Thorough") is shown.
        Chip(if (fit >= ChipFit.Icons) current.words else "Effort: ${current.words}", "Effort: ${current.words}. Change effort", icon = null, arrow = fit <= ChipFit.Short) { open = true }
        DropdownMenu(open, { open = false }) {
            Effort.entries.forEach { e ->
                DropdownMenuItem(
                    { MenuLine(e.words, effortAbout(e, model), e == current) },
                    { open = false; if (e != current) onChoose(e) },
                    modifier = Modifier.semantics { selected = e == current },
                )
            }
        }
    }
}

/** What an effort does with this model (Thorough thinks first only on models that can). */
fun effortAbout(e: Effort, model: LocalModel): String =
    if (e == Effort.Thorough && !model.thinking) "Longer, fuller answers" else e.about

@Composable
internal fun MenuLine(title: String, about: String, chosen: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (chosen) Icon(Glyphs.Check, null, tint = Palette.BlueText, modifier = Modifier.size(20.dp))
        }
        Column {
            Text(title, style = Type.BodyTight.copy(fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal), color = Palette.Text)
            Text(about, style = Type.Small, color = Palette.TextDim)
        }
    }
}

/** A small rounded chip with a down arrow; its touch target is 48 dp tall. */
@Composable
private fun Chip(text: String?, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, arrow: Boolean = true, onClick: () -> Unit) {
    // A long model name gives way first (it ends in "…"); the effort's word stays whole.
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.heightIn(min = 48.dp).clip(shape).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(shape).background(Palette.Card).border(1.dp, Palette.Outline, shape)
                .padding(start = 5.dp, end = if (arrow || text == null) 2.dp else 5.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (icon != null) Icon(icon, null, tint = Palette.TextSoft, modifier = Modifier.size(if (text == null) 20.dp else 16.dp))
            if (text != null) Text(text, style = Type.Small.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp), color = Palette.TextSoft, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            if (arrow || text == null) Icon(Glyphs.Down, null, tint = Palette.TextSoft, modifier = Modifier.size(14.dp))
        }
    }
}

/** What the attach menu says when the PC answers and its model can't look at pictures. */
const val PC_CANNOT_SEE = "None of your PC's models can look at pictures right now. Send words only."

/** Whether the model answering can see pictures. */
sealed interface Sight {
    data object Sees : Sight
    /** It can once its vision part is downloaded (Update in Settings › Who answers). */
    data class NeedsUpdate(val model: String) : Sight
    data object Cannot : Sight
    /** The PC answers and its model can't look at pictures (the phone's own model isn't offered then). */
    data object PcCannot : Sight
}

/**
 * The + on Mímir's message box: a text file is read in; photos, videos and the camera go to a
 * model that sees pictures. With one that can't, the row says so and leads to Settings › Who answers.
 */
@Composable
fun AttachButton(
    sight: Sight, filesFull: Boolean, picturesFull: Boolean, onFile: () -> Unit, onPhotos: () -> Unit, onCamera: () -> Unit, onModels: () -> Unit,
    /** With the least room on the box, the effort is chosen here (null: it has its own chip). */
    effort: Effort? = null, model: LocalModel? = null, onEffort: (Effort) -> Unit = {},
    /** Your PC reads the files (PDFs, documents, sheets, recordings), not this phone. */
    pcFiles: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundButton(Glyphs.Plus, "Attach", { open = true }, size = TINY_BUTTON, iconSize = 22.dp, outline = false)
        DropdownMenu(open, { open = false }) {
            when (sight) {
                Sight.Sees -> {
                    val full = "Up to ${io.github.odnwks.mimir.standalone.Pictures.MAX} pictures on one message"
                    DropdownMenuItem(
                        { MenuIconLine(Glyphs.Look, "Photo or video", if (picturesFull) full else "A video goes as a few still pictures") },
                        { open = false; onPhotos() }, enabled = !picturesFull,
                    )
                    DropdownMenuItem(
                        { MenuIconLine(Glyphs.Look, "Take a photo", if (picturesFull) full else "With the camera") },
                        { open = false; onCamera() }, enabled = !picturesFull,
                    )
                }
                is Sight.NeedsUpdate -> DropdownMenuItem(
                    { MenuIconLine(Glyphs.Models, "Photo, video or camera", "Update ${sight.model} in Settings › Who answers to let it see pictures") },
                    { open = false; onModels() },
                )
                Sight.PcCannot -> DropdownMenuItem(
                    { MenuIconLine(Glyphs.Models, "Photo, video or camera", PC_CANNOT_SEE) },
                    { open = false },
                    enabled = false,
                )
                Sight.Cannot -> DropdownMenuItem(
                    { MenuIconLine(Glyphs.Models, "Photo, video or camera", "This model can't see pictures: switch to a model that sees photos in Settings › Who answers") },
                    { open = false; onModels() },
                )
            }
            DropdownMenuItem(
                { MenuIconLine(Glyphs.Document, "File", if (filesFull) "Up to ${io.github.odnwks.mimir.standalone.Attach.MAX_FILES} files on one message" else if (pcFiles) "Your PC reads PDFs, documents and more" else "Text files: notes, lists, code") },
                { open = false; onFile() },
                enabled = !filesFull,
            )
            if (effort != null && model != null) {
                androidx.compose.material3.HorizontalDivider(color = Palette.Line)
                Effort.entries.forEach { e ->
                    DropdownMenuItem(
                        { MenuLine("Effort: ${e.words}", effortAbout(e, model), e == effort) },
                        { open = false; if (e != effort) onEffort(e) },
                        modifier = Modifier.semantics { selected = e == effort },
                    )
                }
            }
        }
    }
}

/** A picture on a message: a small thumbnail, with × while unsent. */
@Composable
fun PictureChip(picture: io.github.odnwks.mimir.standalone.TurnPicture, onRemove: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(12.dp)
    val image = remember(picture.id) {
        runCatching { android.graphics.BitmapFactory.decodeByteArray(picture.jpeg, 0, picture.jpeg.size)?.asImageBitmap() }.getOrNull()
    }
    Box(Modifier.size(56.dp).clip(shape).background(Palette.Card).border(1.dp, Palette.Outline, shape).semantics { contentDescription = "Picture: ${picture.name}" }) {
        if (image != null) androidx.compose.foundation.Image(image, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Icon(Glyphs.Look, null, tint = Palette.TextSoft, modifier = Modifier.align(Alignment.Center).size(20.dp))
        if (onRemove != null) Box(
            Modifier.align(Alignment.TopEnd).size(28.dp).clickable(role = Role.Button, onClick = onRemove).semantics { contentDescription = "Remove ${picture.name}" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Bar), contentAlignment = Alignment.Center) {
                Icon(Glyphs.Close, null, tint = Palette.Text, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun MenuIconLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, about: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = Palette.TextSoft, modifier = Modifier.size(20.dp))
        Column {
            Text(title, style = Type.BodyTight, color = Palette.Text)
            Text(about, style = Type.Small, color = Palette.TextDim)
        }
    }
}

/** A file on a message: its name (and "start only" when it was too long), with × while unsent. */
@Composable
fun FileChip(file: io.github.odnwks.mimir.standalone.TurnFile, onRemove: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FileChipBody(file, onRemove)
        FileNoteLine(file)
    }
}

/** What the PC read of a file, dim under it ("Read all of it", "Read pages 1 to 40 of 120"); nothing until it has said. */
@Composable
fun FileNoteLine(file: io.github.odnwks.mimir.standalone.TurnFile) {
    if (file.note.isNotEmpty()) Text(
        file.note, style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp).semantics { contentDescription = "${file.name}: ${file.note}" },
    )
}

@Composable
private fun FileChipBody(file: io.github.odnwks.mimir.standalone.TurnFile, onRemove: (() -> Unit)?) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.clip(shape).background(Palette.Card).border(1.dp, Palette.Outline, shape)
            .padding(start = 10.dp, end = if (onRemove != null) 0.dp else 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Glyphs.Document, null, tint = Palette.TextSoft, modifier = Modifier.size(16.dp))
        Text(
            file.name + if (file.cut) " · start only" else "", style = Type.Small, color = Palette.Text, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp).padding(vertical = if (onRemove != null) 0.dp else 6.dp),
        )
        if (onRemove != null) Box(
            Modifier.size(40.dp).clickable(role = Role.Button, onClick = onRemove).semantics { contentDescription = "Remove ${file.name}" },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyphs.Close, null, tint = Palette.TextSoft, modifier = Modifier.size(16.dp)) }
    }
}

/** Why a picked file wasn't added, in plain words. */
fun attachProblem(p: io.github.odnwks.mimir.standalone.Attach.Problem): String = when (p) {
    io.github.odnwks.mimir.standalone.Attach.Problem.Picture -> "Pictures go through Photo or video in the + menu, not File."
    io.github.odnwks.mimir.standalone.Attach.Problem.NotText -> "This phone reads only text files (notes, lists, code). Connect your PC to read PDFs, documents and recordings, or pick a text file."
    io.github.odnwks.mimir.standalone.Attach.Problem.TooBig -> "That file is over 10 MB. Pick a smaller one, or send part of it."
    io.github.odnwks.mimir.standalone.Attach.Problem.Unreadable -> "That file couldn't be opened. Pick it again, or try another file."
}
