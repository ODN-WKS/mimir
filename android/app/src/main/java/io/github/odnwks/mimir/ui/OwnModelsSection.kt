package io.github.odnwks.mimir.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.standalone.LocalState
import io.github.odnwks.mimir.standalone.OwnEntry
import io.github.odnwks.mimir.standalone.OwnModels
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/** Where Android's folder picker starts: the Downloads folder. */
private val DOWNLOADS: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

/** The words under a file that can't be used, or null when it can. */
internal fun ownProblem(e: OwnEntry, phoneBytes: Long): String? = when {
    e.format == null -> "Can't run this kind yet"
    OwnModels.tooBig(e.totalBytes, phoneBytes) -> "Too big for this phone"
    else -> null
}

/**
 * "Your own models" (Settings › Who answers): the owner picks a folder once, and every .gguf file in it
 * is listed with the kind Mímir found, ready to use straight from the folder (nothing is copied).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OwnModelsSection(s: LocalState, phone: PhoneModels) {
    val act = phone.actions ?: return
    if (!s.supported) return
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            act.setOwnFolder(uri.toString())
        }
    }
    // The folder is read whenever the store opens.
    LaunchedEffect(s.ownFolder != null) { if (s.ownFolder != null) act.lookAgain() }
    val reduce = LocalReduceMotion.current
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).then(if (reduce) Modifier else Modifier.animateContentSize(tween(220))),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Your own models", style = Type.Label.copy(fontWeight = FontWeight.Bold), color = Palette.Text, modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
        Text(
            "Put model files (ending in .gguf) in a folder, for example a new one in Downloads called Mimir models, and choose that folder here. " +
                "Android only lets an app read Downloads after you choose the folder once. They run straight from the folder, with no copy.",
            style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (s.ownFolder == null) {
            BigButton("Choose a models folder", Look.Outline, { pick.launch(DOWNLOADS) }, height = 48.dp)
            if (s.ownLost) Text("That folder couldn't be read. Try another one.", style = Type.Small, color = Palette.AmberText, modifier = Modifier.padding(horizontal = 4.dp))
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Folder: ${s.ownFolder}", style = Type.Label, color = Palette.Text, modifier = Modifier.weight(1f))
            LinkText("Change", { pick.launch(DOWNLOADS) })
        }
        if (s.ownLost) Text(
            "This folder can't be read any more. Choose it again with Change.", style = Type.Small, color = Palette.AmberText, modifier = Modifier.padding(horizontal = 4.dp),
        )
        val busy = s.writing || s.testing != null
        s.own.forEach { e -> OwnCard(e, s, phone.phoneBytes, busy) { act.useOwn(e.docId) } }
        if (s.own.isEmpty() && !s.ownLost && !s.ownLooking) Text(
            "No .gguf files in this folder yet.", style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp),
        )
        BigButton(if (s.ownLooking) "Looking…" else "Look again", Look.Quiet, { if (!s.ownLooking) act.lookAgain() }, height = 48.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnCard(e: OwnEntry, s: LocalState, phoneBytes: Long, busy: Boolean, use: () -> Unit) {
    val inUse = s.model == e.id
    val problem = ownProblem(e, phoneBytes)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (inUse) Palette.BlueCard else Palette.Card).border16(if (inUse) Palette.BlueBorder else Palette.Line)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(e.file, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
        Text(if (e.format == null) e.sizeText else "${e.kind} · ${e.sizeText}", style = Type.Small, color = Palette.TextSoft)
        if (e.vision != null) Text("Can see photos (${e.vision.name})", style = Type.Small, color = Palette.TextDim)
        if (problem != null) Text(problem, style = Type.Small, color = Palette.AmberText)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (inUse) Tag("In use", Palette.BlueText, Palette.BlueIcon)
            if (e.uncensored) Tag("Uncensored", Palette.AmberText, Palette.Track)
        }
        if (!inUse && problem == null && !busy) {
            BigButton("Use this one", Look.Primary, use, height = 48.dp, description = "Use ${e.name}")
            if (s.have.any { !OwnModels.isOwn(it) }) Text(
                "Only one model is kept: once this one is used, the downloaded one is removed to free its space.",
                style = Type.Small, color = Palette.TextDim,
            )
        }
    }
}
