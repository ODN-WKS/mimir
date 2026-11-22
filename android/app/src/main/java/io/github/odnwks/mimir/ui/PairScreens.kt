package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.odnwks.mimir.data.PairProgress
import io.github.odnwks.mimir.data.Station
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/** The one-time warning when pairing a phone with no screen lock (owner, 2026-11-19: warn, never block). */
const val NO_LOCK_WARNING = "Without a screen lock, anyone holding this phone can use $AI_NAME and your PC. Add one in Android Settings › Security."

/** Connect your PC: the intro, and the three plain steps (the words the PC really uses). */
const val CONNECT_TITLE = "Connect your PC"
val CONNECT_INTRO = "Once connected, this phone reaches your PC from anywhere. It stays private, with no account to make."
val CONNECT_STEPS = listOf(
    "On your PC, open $AI_NAME and go to Settings › Phones.",
    "Click Pair a phone. A square code appears.",
    "Point this phone's camera at it.",
)
const val CONNECT_SCAN = "Scan the code"
const val CONNECT_TYPE_INSTEAD = "Can't scan? Type the code instead"
const val CONNECT_SCAN_INSTEAD = "Scan instead"
const val CONNECT_TYPE_INSTEAD_SHORT = "Type the code instead"

/** The words in a step that are bold: what to look for on the PC. */
private val STEP_BOLD = listOf("Settings › Phones", "Pair a phone")

/** A step with the PC's own words in bold. */
fun connectStep(text: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    append(text)
    STEP_BOLD.forEach { w ->
        val i = text.indexOf(w)
        if (i >= 0) addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold), i, i + w.length)
    }
}

/** The badge colour behind a step's number. */
private val StepBadge = androidx.compose.ui.graphics.Color(0xFF22355C)

/** Numbered round badges and the step beside each. */
@Composable
fun NumberedSteps(steps: List<String>, color: androidx.compose.ui.graphics.Color = Palette.Text) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val d = with(LocalDensity.current) { (15 * 1.8f * fontScale).dp }
                Box(Modifier.sizeIn(minWidth = d, minHeight = d).clip(CircleShape).background(StepBadge), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = Type.Label.copy(fontWeight = FontWeight.Bold), color = Palette.Text)
                }
                Text(connectStep(step.forDevice()), style = Type.BodyTight, color = color, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A serif title centred between a back arrow and an empty slot. */
@Composable
private fun CenteredBackTitle(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTarget(Glyphs.Back, "Back", onBack)
        Text(title, style = ScreenTitleStyle, color = Palette.Text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { heading() })
        androidx.compose.foundation.layout.Spacer(Modifier.size(48.dp))
    }
}

/** Connect your PC: scan the PC's code, or type it. */
@Composable
fun PairScreen(station: Station, nav: Navigator, screenLock: Boolean = true) {
    // "Paste the code instead" on Couldn't pair opens straight to the paste box.
    var typing by remember { mutableStateOf(nav.pastePairCode) }
    androidx.compose.runtime.LaunchedEffect(Unit) { nav.pastePairCode = false }
    var typed by remember { mutableStateOf("") }
    // Replaced, not stacked: Scan, Paste, Couldn't pair and back never pile up behind each other.
    val pairNow = { code: String -> station.pair(code); nav.replace(Screen.PairDone) }
    // No screen lock: said once, plainly, with Pair anyway.
    var warnFor by remember { mutableStateOf<String?>(null) }
    val go = { code: String -> if (screenLock) pairNow(code) else warnFor = code }
    warnFor?.let { code ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { warnFor = null },
            title = { Text("This phone has no screen lock", style = Type.Section, color = Palette.Text) },
            text = { Text(NO_LOCK_WARNING.forDevice(), style = Type.Body, color = Palette.TextSoft) },
            confirmButton = { androidx.compose.material3.TextButton({ warnFor = null; pairNow(code) }) { Text("Pair anyway", color = Palette.BlueText) } },
            dismissButton = { androidx.compose.material3.TextButton({ warnFor = null }) { Text("Cancel", color = Palette.TextSoft) } },
            containerColor = Palette.Card,
        )
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { go(it) } }
    val startScan = {
        scan.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                .setOrientationLocked(false).setPrompt("Point at the code in $AI_NAME › Settings › Phones"),
        )
    }
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        CenteredBackTitle(CONNECT_TITLE, nav::back)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(CONNECT_INTRO.forDevice(), style = Type.Body, color = Palette.TextSoft)
            NumberedSteps(CONNECT_STEPS)
            if (typing) {
                MessageBox(typed, { typed = it }, "Pairing code", "Type the code from the PC", Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(16.dp))
            } else {
                Box(
                    Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Bar)
                        .dashedBorder(Palette.Outline, 20.dp)
                        .clickable(role = Role.Button) { startScan() }
                        .clearAndSetSemantics { contentDescription = "Scan the code with the camera"; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(150.dp).border(3.dp, Palette.BlueLight, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text(CONNECT_SCAN, style = Type.Label, color = Palette.TextDim)
                    }
                }
            }
        }
        PinnedBar(top = 10.dp) {
            if (typing) {
                BigButton("Pair", Look.Primary, { if (typed.isNotBlank()) go(typed) }, radius = 16.dp, bold = true)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LinkText(CONNECT_SCAN_INSTEAD, { typing = false }) }
            } else {
                BigButton(CONNECT_SCAN, Look.Primary, { startScan() }, radius = 16.dp, bold = true)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LinkText(CONNECT_TYPE_INSTEAD, { typing = true }) }
            }
        }
    }
}

const val ALMOST_THERE = "Almost there"
const val WORDS_ASK = "Your PC is showing four words. Are they the same as these?"
const val WORDS_MATCH = "If they match, click \"Yes, they match\" on your PC. Matching words mean nobody is in between."

const val ALERTS_TITLE = "Know when your PC needs you"
const val ALERTS_WHY = "Mímir can tell you when something on your PC needs you or has an answer. Nothing is sent anywhere else."

/** Android 13 and later: notifications aren't allowed yet and were never asked about (asked once, right after pairing). */
fun needsAlertsAsk(ctx: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 33) return false
    val prefs = ctx.getSharedPreferences("alerts_ask", 0)
    if (prefs.getBoolean("asked", false)) return false
    prefs.edit().putBoolean("asked", true).apply()
    return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** Pairing (board 16): the four words, confirmed on the PC; then on to the chat. */
@Composable
fun PairDoneScreen(state: StationState, nav: Navigator) {
    val p = state.pairing
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Once, right after pairing: one plain line why, then Android's own question. Never later in a session.
    var askAlerts by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val alertsAnswer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        askAlerts = false; nav.resetTo(Screen.Chat)
    }
    if (askAlerts) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askAlerts = false; nav.resetTo(Screen.Chat) },
        title = { Text(ALERTS_TITLE, style = Type.Section, color = Palette.Text) },
        text = { Text(ALERTS_WHY, style = Type.BodyTight, color = Palette.TextSoft) },
        confirmButton = { LinkText("Allow notifications", { alertsAnswer.launch(android.Manifest.permission.POST_NOTIFICATIONS) }, bold = true) },
        dismissButton = { LinkText("Not now", { askAlerts = false; nav.resetTo(Screen.Chat) }) },
        containerColor = Palette.Card,
    )
    LaunchedEffect(p) {
        when (p) {
            PairProgress.Paired -> if (needsAlertsAsk(ctx)) askAlerts = true else nav.resetTo(Screen.Chat)
            is PairProgress.Failed -> nav.replace(Screen.PairError)
            else -> {}
        }
    }
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (p is PairProgress.ConfirmOnPc) {
                MimirEye(EyeMode.Normal, Modifier.size(72.dp))
                Text(ALMOST_THERE, style = ScreenTitleStyle.copy(textAlign = TextAlign.Center), color = Palette.Text, modifier = Modifier.semantics { heading() })
                Text(WORDS_ASK, style = Type.Body.copy(textAlign = TextAlign.Center), color = Palette.TextSoft)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    p.words.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { w ->
                                Box(
                                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Palette.Card).border16(Palette.LineStrong, 14).padding(vertical = 18.dp, horizontal = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(w, style = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 20.sp, lineHeight = 26.sp, textAlign = TextAlign.Center), color = Palette.Text) }
                            }
                        }
                    }
                }
                Text(WORDS_MATCH, style = Type.Small.copy(lineHeight = Type.Small.fontSize * 1.45f, textAlign = TextAlign.Center), color = Palette.TextDim)
            } else {
                // No tick until it has really paired: a spinner while reaching the PC.
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(44.dp), color = Palette.BlueLight, strokeWidth = 4.dp)
                SectionTitle("Reaching your PC…", style = Type.Big.copy(textAlign = TextAlign.Center))
                Text("This takes a few seconds.", style = Type.Body.copy(textAlign = TextAlign.Center), color = Palette.TextSoft)
            }
        }
        PinnedBar(top = 10.dp) {
            BigButton("Cancel", Look.Outline, { nav.replace(Screen.Pair) }, radius = 16.dp)
        }
    }
}

private val problems = listOf(
    "The PC is out of reach" to "Check the PC is awake and $AI_NAME is open. The phone keeps trying for a minute.",
    "The words didn't match" to "Pairing was stopped to keep you safe. Start again on the PC. If it happens twice, check both screens carefully.",
    "Camera not allowed" to "Allow the camera in the phone’s settings, or type the code instead.",
)

/** Couldn't pair (board 17): what went wrong and how to fix it. */
@Composable
fun PairErrorScreen(state: StationState, nav: Navigator) {
    val why = (state.pairing as? PairProgress.Failed)?.why
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader("Couldn't pair", { nav.replace(Screen.Pair) })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Panel(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
                background = Palette.RedCard, border = Palette.RedBorder, padding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Glyphs.Clock, null, tint = Palette.RedTitle, modifier = Modifier.size(22.dp))
                    SectionTitle("Here's what to try", color = Palette.RedTitle, style = Type.Section.copy(fontSize = 17.sp))
                }
                Text(why ?: "Codes last 10 minutes. On the PC, click \"Pair a phone\" again for a new one, then scan it.", style = Type.Body, color = Palette.RedBody)
            }
            SectionTitle("Other things that can go wrong", Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp))
            problems.forEach { (title, fix) ->
                Panel(radius = 16.dp, spacing = 4.dp) {
                    Text(title, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                    Text(fix, style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.45f), color = Palette.TextSoft)
                }
            }
        }
        PinnedBar(top = 10.dp) {
            BigButton("Scan the new code", Look.Primary, { nav.replace(Screen.Pair) }, radius = 16.dp)
            BigButton(CONNECT_TYPE_INSTEAD_SHORT, Look.Outline, { nav.pastePairCode = true; nav.replace(Screen.Pair) }, radius = 16.dp)
        }
    }
}
