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

const val PC_PICKS = "Your messages go to Anthropic."

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

/** The small grey mark under an answer: where it ran (Stage 1, 2.2). Null when nothing is known. */
fun answerMark(ran: String?, pcName: String, whisper: Boolean): Pair<androidx.compose.ui.graphics.vector.ImageVector, String>? = when {
    whisper -> Glyphs.Lock to "Whisper · this phone"
    ran == "pc" -> Glyphs.Monitor to pcName
    ran == "phone" -> Glyphs.Phone to "This phone"
    ran == "paid" -> Glyphs.Monitor to "Online"
    else -> null
}

/** What an effort does with this model (Thorough thinks first only on models that can). */
fun effortAbout(e: Effort, model: LocalModel): String =
    if (e == Effort.Thorough && !model.thinking) "Longer, fuller answers" else e.about

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
    /** The PC answers but has not said yet what it sees (still loading, or an older PC): neither yes nor no. */
    data object PcUnknown : Sight
}

/** What the attach menu says while the PC has not said what it sees. */
const val PC_SIGHT_UNKNOWN = "Your PC hasn't said yet whether it can look at pictures. Try again in a moment."

/** Which [Sight] applies: the PC's word first when it answers, then this phone's own model. */
fun sightFor(pcSight: Boolean?, pcReady: Boolean, phoneSees: Boolean, phoneVision: String?): Sight = when {
    pcSight == true -> Sight.Sees
    pcSight == false -> Sight.PcCannot
    phoneSees -> Sight.Sees
    phoneVision != null -> Sight.NeedsUpdate(phoneVision)
    // A paired PC answering: its word isn't known yet. Never "can't see", and Settings has nothing to offer.
    pcReady -> Sight.PcUnknown
    else -> Sight.Cannot
}

/**
 * The + on Mímir's message box: a text file is read in; photos, videos and the camera go to a
 * model that sees pictures. With one that can't, the row says so and leads to Settings › Who answers.
 */
@Composable
fun AttachButton(
    sight: Sight, filesFull: Boolean, picturesFull: Boolean, onFile: () -> Unit, onPhotos: () -> Unit, onCamera: () -> Unit, onModels: () -> Unit,
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
                Sight.PcUnknown -> DropdownMenuItem(
                    { MenuIconLine(Glyphs.Models, "Photo, video or camera", PC_SIGHT_UNKNOWN) },
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
