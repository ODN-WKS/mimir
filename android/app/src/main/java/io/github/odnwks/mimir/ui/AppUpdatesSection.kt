package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.BuildConfig
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.update.AppUpdater
import io.github.odnwks.mimir.update.UpdateStatus

const val UPDATES_PRIVACY = "Checks GitHub once a day. Nothing about you is sent."
const val UPDATES_TEST_BUILD = "This test version only shows what's available."
const val UPDATES_ASKS = "Your Android asks you to tap once to finish each update."
const val UPDATES_NOT_ALLOWED = "Installing isn't allowed yet. Tap Install now and Android asks once."

/** The install button shows whenever a release is saved and the update is ready, waiting on Android's installer (the owner may have cancelled it) or failed to install (a failure must never hide it). */
fun showsInstallButton(s: UpdateStatus, releaseSaved: Boolean): Boolean = releaseSaved && (s is UpdateStatus.Ready || s is UpdateStatus.Waiting || (s is UpdateStatus.Failed && s.install))

/** "Install now" at first and while the owner still has to allow installs; "Try again" after any other failure. */
fun installButtonWords(s: UpdateStatus): String =
    if (s is UpdateStatus.Failed && s.words != io.github.odnwks.mimir.update.AppUpdater.NEEDS_ALLOW) "Try again" else "Install now"

/** The one line under a failed GitHub check that says what happened and what to do next (null: nothing more to say). */
fun updateDetail(fallback: Boolean, pcResult: UpdateResult, paired: Boolean): String? = when {
    pcResult is UpdateResult.Newest -> null
    pcResult is UpdateResult.Couldnt && !paired -> "GitHub couldn't be reached. Try again later, or use the download page."
    pcResult is UpdateResult.Couldnt -> "GitHub couldn't be reached and your PC is away. Try again later, or use the download page."
    fallback -> "GitHub couldn't be reached, so your PC answered. Check again later."
    else -> null
}

/** The status line under "Check now". */
fun updateStatusWords(s: UpdateStatus, current: String): String = when (s) {
    UpdateStatus.None -> "You have version $current."
    UpdateStatus.Checking -> "Checking…"
    is UpdateStatus.Newest -> "You have the newest version, ${s.version}."
    is UpdateStatus.Ready -> "Version ${s.version} is ready. Install now?"
    UpdateStatus.Downloading -> "Getting the update…"
    is UpdateStatus.Waiting -> "Version ${s.version} is on its way. Android may ask you to confirm."
    is UpdateStatus.Installed -> "Installed ${s.version}."
    is UpdateStatus.Failed -> s.words
}

/** Settings › Help and about › App updates: the Updates section (on/off, Check now, mobile data, status, what's new). */
/** The switch that keeps the app up to date: not "Updates", which is the menu's news feed (R19). */
const val APP_UPDATES_SWITCH = "Keep the app up to date"

@Composable
fun AppUpdatesSection(pc: io.github.odnwks.mimir.data.StationState, modifier: Modifier = Modifier) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val ctx = LocalContext.current
    val status by AppUpdater.status.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(AppUpdater.enabled(ctx)) }
    var mobile by remember { mutableStateOf(AppUpdater.mobileData(ctx)) }
    // "Check now" asks GitHub; only when GitHub can't be reached is the PC asked, and the line says which answered.
    var fallback by remember { mutableStateOf(false) }
    var answered by remember { mutableStateOf(false) }
    val pcResult = updateResult(pc)
    val pcSpeaks = fallback || (status is UpdateStatus.None && pc.appUpdate)
    // Read again when the screen comes back (after Android's allow page) and when the status changes.
    var resumes by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { resumes++ }
    val allowed = remember(status, resumes) { AppUpdater.installAllowed(ctx) }
    val saved = remember(status, resumes) { AppUpdater.foundRelease(ctx) != null }
    CapsLabel("APP UPDATES", modifier)
    Panel(spacing = 6.dp) {
        SwitchRow(on, { on = it; AppUpdater.setEnabled(ctx, it) }, Modifier.heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f)) {
                Text(APP_UPDATES_SWITCH, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                Text(if (on) "On" else "Off", style = Type.Small, color = Palette.TextDim)
            }
        }
        SwitchRow(mobile, { mobile = it; AppUpdater.setMobileData(ctx, it) }, Modifier.heightIn(min = 48.dp)) {
            Text("Also on mobile data", style = Type.BodyTight, color = Palette.Text, modifier = Modifier.weight(1f))
        }
        BigButton("Check now", Look.Outline, {
            Thread {
                AppUpdater.check(ctx, force = true)
                val s = AppUpdater.status.value
                fallback = s is UpdateStatus.Failed && s.words == io.github.odnwks.mimir.update.UpdateWords.COULDNT_CHECK
                answered = true
            }.start()
        }, height = 48.dp)
        Text(if (pcSpeaks) (if (fallback) updateFallbackWords(pcResult, pc.pcVersion) else updateResultWords(pcResult)) else updateStatusWords(status, BuildConfig.VERSION_NAME), style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.testTag("updates-status"))
        if (pcSpeaks) {
            updateDetail(fallback, pcResult, pc.paired)?.let { Text(it, style = Type.Small, color = Palette.TextDim) }
            if (pcResult !is UpdateResult.Newest) BigButton(if (pcResult is UpdateResult.Ready) "Get it" else "Open the download page", Look.Primary, { uri.openUri(RELEASES_PAGE) }, height = 48.dp)
        } else if (answered && (status is UpdateStatus.Newest || status is UpdateStatus.Ready)) {
            Text("GitHub answered.", style = Type.Small, color = Palette.TextDim)
        }
        if (!pcSpeaks) {
            (status as? UpdateStatus.Ready)?.takeIf { it.notes.isNotEmpty() }?.let { r ->
                Text("What's new", style = Type.Small, color = Palette.TextDim)
                Text(r.notes, style = Type.Label, color = Palette.TextSoft, modifier = Modifier.testTag("updates-notes"))
            }
            if (showsInstallButton(status, saved)) {
                BigButton(installButtonWords(status), Look.Primary, { Thread { AppUpdater.downloadAndInstall(ctx) }.start() }, height = 48.dp, modifier = Modifier.testTag("updates-install"))
                if (!allowed) Text(UPDATES_NOT_ALLOWED, style = Type.Small, color = Palette.TextDim)
            }
        }
        if (AppUpdater.isDevBuild) Text(UPDATES_TEST_BUILD, style = Type.Small, color = Palette.TextDim)
        else if (!AppUpdater.silentLater) Text(UPDATES_ASKS, style = Type.Small, color = Palette.TextDim)
        Text(UPDATES_PRIVACY, style = Type.Small, color = Palette.TextDim, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    }
}
