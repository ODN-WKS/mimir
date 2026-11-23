package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/** Where new versions of the phone app are: the same page as Mímir for the PC. */
const val RELEASES_PAGE = "https://github.com/ODN-WKS/mimir/releases/latest"

/** An inner Settings page: a back header and a scrolling column. */
@Composable
private fun Page(title: String, nav: Navigator, subtitle: String? = null, backLabel: String = "Back to Settings", content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader(title, nav::back, backLabel = backLabel, subtitle = subtitle)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun Body(text: String, color: androidx.compose.ui.graphics.Color = Palette.TextSoft) =
    Text(text, style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.45f), color = color)

/** Where the chats are kept, said as it is (QA2 #25): paired, the PC keeps them too; an online answerer is told. */
fun chatsKeptWords(paired: Boolean, onlineAnswers: Boolean): String = when {
    paired -> "Your chats are kept on this phone and your PC."
    onlineAnswers -> "Kept on this phone. Messages you send to an online model go to that company."
    else -> "Kept on this phone only."
}

/** One thing the app keeps on the phone: what it is, what it is for, how big, and how to free it. */
private data class Kept(val title: String, val why: String, val bytes: Long, val needed: Boolean = false, val remove: (@Composable () -> Unit)? = null)

/**
 * Settings › Storage: a card with what the app uses of the phone's space, then each thing it keeps,
 * biggest first, with what it is for and Delete or Clear where the app lets you.
 */
@Composable
fun StorageScreen(engine: Engine?, nav: Navigator, paired: Boolean = false) {
    val s = engine?.state?.collectAsStateWithLifecycle()?.value
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val have = Catalog.models.filter { s != null && it.id in s.have }
    val chatBytes = engine?.chatBytes() ?: 0L
    val voiceBytes = androidx.compose.runtime.remember { runCatching { io.github.odnwks.mimir.audio.Voices.get(ctx).store.bytes() }.getOrDefault(0L) }
    val total = androidx.compose.runtime.remember { runCatching { android.os.StatFs(android.os.Environment.getDataDirectory().path).totalBytes }.getOrDefault(0L) }
    val chats = (s?.earlier?.size ?: 0) + if (s?.chat?.turns?.isNotEmpty() == true) 1 else 0
    val kept = buildList {
        have.forEach { m ->
            val inUse = s?.writing == true && s.model == m.id
            add(Kept(m.plainName, "Answers on this phone with no PC or internet. ${m.details}", m.totalBytes, remove = {
                var sure by rememberSaveable(m.id) { mutableStateOf(false) }
                if (!sure && !inUse) SmallButton("Remove", { sure = true }, description = "Delete ${m.plainName}")
                if (sure) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallButton("Remove", { engine?.delete(m.id); sure = false }, description = "Yes, delete ${m.plainName}")
                    SmallButton("Keep", { sure = false })
                }
            }))
        }
        add(Kept("Ingrid's voice", "Reads answers aloud, on this phone.", voiceBytes, needed = true))
        add(Kept(if (chats == 1) "1 chat" else "$chats chats", chatsKeptWords(paired, io.github.odnwks.mimir.online.OnlineModels.answering() != null), chatBytes, remove = {
            if (engine != null && chats > 0) {
                var sure by rememberSaveable { mutableStateOf(false) }
                if (!sure) SmallButton("Delete all chats\u2026", { sure = true }, description = "Delete all chats")
                else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        (if (chats == 1) "Delete your 1 chat from this phone? This can't be undone." else "Delete all $chats chats from this phone? This can't be undone.").forDevice(),
                        style = Type.Small, color = Palette.TextSoft,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton(if (chats == 1) "Yes, delete 1 chat" else "Yes, delete $chats chats", { engine.deleteAll(); sure = false })
                        SmallButton("Keep them", { sure = false })
                    }
                }
            }
        }))
    }.sortedByDescending { it.bytes }
    val used = kept.sumOf { it.bytes }
    Page("Storage", nav) {
        Panel(spacing = 8.dp) {
            Text("${sizeWords(used)} used by $AI_NAME", style = Type.Section.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 20.sp), color = Palette.Text, modifier = Modifier.semantics { heading() })
            if (total > 0) {
                Bar(used.toFloat() / total, 8.dp)
                Text("of this phone's ${sizeWords(total)}".forDevice(), style = Type.Small, color = Palette.TextDim)
            }
        }
        CapsLabel("WHAT'S KEPT, BIGGEST FIRST")
        kept.forEach { k ->
            Panel(spacing = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(k.title, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                        Text(k.why.forDevice(), style = Type.Small, color = Palette.TextDim)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(sizeWords(k.bytes), style = Type.BodyTight, color = Palette.TextSoft)
                        if (k.needed) Text("Needed", style = Type.Small, color = Palette.TextDim)
                    }
                }
                k.remove?.invoke()
            }
        }
        if (have.isEmpty()) Body("No model is downloaded. This phone gets one when you first chat with it here.", Palette.TextDim)
        Body("$AI_NAME cleans up after itself: a model unused for ${Engine.UNUSED_DAYS} days is removed by itself, and it downloads again when you use it.", Palette.TextDim)
        Body("Your projects and tasks stay on your PC; the phone keeps only what it needs to show them.", Palette.TextDim)
    }
}

/** "2.7 GB", "340 KB", "0 KB". */
fun sizeWords(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
    else -> "%.0f KB".format(bytes / 1e3)
}

/** The word under the read-aloud speed slider. */
fun speedWord(speed: Float): String = when {
    speed < 0.75f -> "Much slower"
    speed < 0.95f -> "Slower"
    speed <= 1.05f -> "Normal"
    speed <= 1.3f -> "Faster"
    else -> "Much faster"
}

/**
 * Settings › Voice: Ingrid with a sample to hear, the read-aloud speed, and what the microphone
 * button does. The other voices (the phone's own) are one row away.
 */
@Composable
fun VoiceScreen(state: StationState, station: Station, nav: Navigator) {
    Page("Voice", nav) {
        val voices = rememberVoices()
        val vs by voices.state.collectAsStateWithLifecycle()
        val ingrid = io.github.odnwks.mimir.audio.VoiceCatalog.ingrid
        val playing = vs.sampling == ingrid.id
        CapsLabel("READING ALOUD")
        Panel(spacing = 8.dp) {
            Text(ingrid.name, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
            Text("A natural voice, built into the app", style = Type.Small, color = Palette.TextDim)
            BigButton(
                if (playing) "Stop" else "Hear her", Look.Outline, { voices.actions.sample(ingrid.id) }, height = 44.dp,
                description = if (playing) "Stop the sample" else "Play a sample of ${ingrid.name}", icon = if (playing) Glyphs.Stop else Glyphs.Play,
            )
        }
        val chosen = io.github.odnwks.mimir.audio.ReadSpeed.snap(state.player.speed)
        var pending by androidx.compose.runtime.remember(chosen) { androidx.compose.runtime.mutableFloatStateOf(chosen) }
        Panel(spacing = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.weight(1f))
                Text(speedWord(pending), style = Type.BodyTight, color = Palette.BlueText, modifier = Modifier.testTag("speed-word"))
            }
            androidx.compose.material3.Slider(
                value = pending,
                onValueChange = { pending = io.github.odnwks.mimir.audio.ReadSpeed.snap(it) },
                onValueChangeFinished = { if (pending != chosen) station.setSpeed(pending) },
                valueRange = io.github.odnwks.mimir.audio.ReadSpeed.MIN..io.github.odnwks.mimir.audio.ReadSpeed.MAX,
                steps = io.github.odnwks.mimir.audio.ReadSpeed.STEPS,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Palette.BlueText, activeTrackColor = Palette.BlueText),
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Read-aloud speed"
                    stateDescription = io.github.odnwks.mimir.audio.ReadSpeed.percent(pending)
                },
            )
            val read = rememberReadAloud()
            val reading by read.reading.collectAsStateWithLifecycle()
            val readying by read.gettingReady.collectAsStateWithLifecycle()
            val hearing = reading == SPEED_SAMPLE_ID
            val waiting = hearing && readying == SPEED_SAMPLE_ID
            SmallButton(
                when { waiting -> "Getting ${read.voiceName ?: "the voice"} ready…"; hearing -> "Stop"; else -> "Hear it" },
                { read.speed = pending; read.toggle(SPEED_SAMPLE_ID, SPEED_SAMPLE) },
                description = when { waiting -> "Getting the voice ready. Tap to stop"; hearing -> "Stop the sample"; else -> "Hear a sample at ${io.github.odnwks.mimir.audio.ReadSpeed.percent(pending)}" },
                animate = true,
            )
            Row(Modifier.fillMaxWidth()) {
                Text("Slower", style = Type.Small, color = Palette.TextDim, modifier = Modifier.weight(1f))
                Text("Faster", style = Type.Small, color = Palette.TextDim)
            }
        }
        ListRow("Other voices", "The phone's own voice, and which one reads", { nav.open(Screen.Voices) })
        val natural = vs.active
        val phoneVoice = natural?.name ?: rememberReadAloud().voiceName
        val here = if (natural != null) "$phoneVoice, on this phone" else "this phone's own voice${phoneVoice?.let { " ($it)" } ?: ""}"
        Body(
            if (state.paired && state.pc.online) "Online replies are read by your PC's voice. $AI_NAME's chats, Whispers, and anything while your PC is away are read by $here, so nothing leaves the phone."
            else "Messages are read by $here: nothing leaves the phone. Tap the speaker beside a reply, or long-press the reply and choose Read aloud; tap again to stop.",
            Palette.TextDim,
        )
        CapsLabel("TALKING INSTEAD OF TYPING")
        Panel(spacing = 6.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(Glyphs.Mic, null, tint = Palette.BlueText, modifier = Modifier.size(22.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Microphone button", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                    // PHE-16, PHU-07: the phone's own recogniser converts speech to text; the PC only receives the words.
                    Text("This phone turns your words into text itself. The recording is never sent; your PC receives only the words.", style = Type.Small, color = Palette.TextDim)
                }
            }
        }
        Body("The PC's own voice, and how it turns speech into text, are chosen on your PC: $AI_NAME › Settings › Voice.", Palette.TextDim)
    }
}

/** What "Check for a new version" says: the newest, a newer one ready, or that it couldn't check. */
sealed interface UpdateResult {
    data class Newest(val version: String) : UpdateResult
    data class Ready(val version: String) : UpdateResult
    data object Couldnt : UpdateResult
}

/** The result of a check from what the phone knows: the PC says which version is newest, and says it when it's away. */
fun updateResult(state: StationState): UpdateResult = when {
    state.appUpdate -> UpdateResult.Ready(state.pcVersion)
    state.paired && state.pc.online && state.pcVersion.isNotEmpty() -> UpdateResult.Newest(state.appVersion)
    else -> UpdateResult.Couldnt
}

/** The line when GitHub could not be reached and the PC answered instead: never "the newest" as if it were sure. */
fun updateFallbackWords(r: UpdateResult, pcVersion: String): String = when (r) {
    is UpdateResult.Newest -> "Couldn't reach GitHub to check. Your PC says $pcVersion is the newest."
    else -> updateResultWords(r)
}

fun updateResultWords(r: UpdateResult): String = when (r) {
    is UpdateResult.Newest -> "You have the newest version (${r.version})."
    is UpdateResult.Ready -> "Version ${r.version} is ready."
    UpdateResult.Couldnt -> "Couldn't check just now."
}

/** Settings › Help and about › App updates: this app's version, and Check for a new version (it always answers). */
@Composable
fun AboutScreen(state: StationState, nav: Navigator) {
    Page("App updates", nav, backLabel = "Back") {
        Panel(spacing = 4.dp) {
            Text("$AI_NAME ${state.appVersion}", style = Type.Section, color = Palette.Text)
            Text(
                if (state.paired && state.pcVersion.isNotEmpty()) "Your PC has $AI_NAME ${state.pcVersion}" else "The phone app for $AI_NAME on your PC",
                style = Type.Small, color = Palette.TextDim,
            )
        }
        AppUpdatesSection(state)
        Body("Installing a new version keeps your pairing and chats. When your PC gets a new $AI_NAME, a line at the top of Updates and here says a new app is out.", Palette.TextDim)
    }
}

/** One question on Help and about, with its short answer on its own page. */
data class HelpTopic(val id: String, val question: String)

val HELP_TOPICS = listOf(
    HelpTopic("who", "Who answers my messages?"),
    HelpTopic("whisper", "What is a Whisper?"),
    HelpTopic("updates", "What are Updates?"),
    HelpTopic("needs", "What does \u201cNeeds you\u201d mean?"),
    HelpTopic("away", "Using $AI_NAME away from home"),
    HelpTopic("pictures", "Where do my pictures go?"),
    HelpTopic("lost", "What if I lose this phone?"),
)

/** Where a picture sent from the chat goes (the same words on the PC). */
const val HELP_PICTURES = "Pictures you send go to your PC (or to Anthropic when Online answers) and are kept with that chat."

/** The short answer to a Help question. */
fun helpAnswer(id: String, paired: Boolean): String = when (id) {
    "who" -> "You choose, in Settings \u203a Who answers: this phone's own model (works with no PC or internet), your PC's model, or online through your PC. The chat's top line says which one is answering."
    "whisper" -> whisperExplained(paired)
    "updates" -> "Spoken updates that arrive when Mímir finishes something on your PC. Tap one to hear it; a new version of this app is also announced there; Settings \u203a Help and about \u203a App updates checks for one."
    "needs" -> "Mímir asks to change a file or run a command, or has a question, and waits until you answer. Needs you lists them; open one to say yes or no. Project work uses Claude Code on your PC."
    "away" -> "With \u201cLet this phone reach your PC from anywhere\u201d on (Settings \u203a Your PC), this phone reaches your PC away from home and stays private. Off, it works only on the same network. Chats on this phone's own model need neither."
    "pictures" -> HELP_PICTURES
    "lost" -> "You remove it at your PC: $AI_NAME \u203a Settings \u203a Phones."
    else -> ""
}

/** Settings › Help and about: the few questions people ask, each opening a short answer, then the version. */
@Composable
fun HelpScreen(state: StationState, nav: Navigator) {
    Page("Help and about", nav) {
        CapsLabel("HOW THINGS WORK")
        HELP_TOPICS.forEach { t -> ListRow(t.question, null, { nav.open(Screen.HelpAnswer(t.id)) }) }
        CapsLabel("THIS APP")
        ListRow(LABS_TITLE, LABS_LINE, { nav.open(Screen.Labs) })
        ListRow(
            "App updates", "$AI_NAME ${state.appVersion} \u00b7 " + if (state.appUpdate) "a new version is out" else "check for a new version",
            { nav.open(Screen.About) },
        )
        Text(FONT_CREDIT, style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    }
}

const val FONT_CREDIT = "Font: Cinzel (SIL Open Font License 1.1)"

/** One Help question's answer page. */
@Composable
fun HelpAnswerScreen(id: String, state: StationState, nav: Navigator) {
    val topic = HELP_TOPICS.firstOrNull { it.id == id }
    Page(topic?.question ?: "Help", nav, backLabel = "Back") {
        Panel { Text(helpAnswer(id, state.paired), style = Type.Body, color = Palette.TextSoft) }
    }
}

/**
 * The read-aloud speed: a slider from 50% to 150% in 5% steps (the same speed everywhere: Settings ›
 * Voice, every chat's and task's sheet, the Updates player), on top of each voice's own pace. It
 * takes the new speed when the finger lifts; "Hear it" reads a short line at that speed, and only
 * when tapped.
 */
@Composable
fun SpeedRow(state: StationState, station: Station, icon: Boolean = false) {
    val chosen = io.github.odnwks.mimir.audio.ReadSpeed.snap(state.player.speed)
    var pending by androidx.compose.runtime.remember(chosen) { androidx.compose.runtime.mutableFloatStateOf(chosen) }
    val voice = rememberReadAloud()
    val reading by voice.reading.collectAsStateWithLifecycle()
    val hearing = reading == SPEED_SAMPLE_ID
    val readying by voice.gettingReady.collectAsStateWithLifecycle()
    val waiting = hearing && readying == SPEED_SAMPLE_ID
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // In a sheet it lines up with the rows' icons.
            if (icon) Icon(Glyphs.Speaker, null, tint = Palette.Text, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Read-aloud speed", style = Type.BodyTight, color = Palette.Text)
                Text("For every message you have read aloud, and the Updates player", style = Type.Small, color = Palette.TextDim)
            }
            Text(io.github.odnwks.mimir.audio.ReadSpeed.percent(pending), style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.BlueText)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Slider(
                value = pending,
                onValueChange = { pending = io.github.odnwks.mimir.audio.ReadSpeed.snap(it) },
                onValueChangeFinished = { if (pending != chosen) station.setSpeed(pending) },
                valueRange = io.github.odnwks.mimir.audio.ReadSpeed.MIN..io.github.odnwks.mimir.audio.ReadSpeed.MAX,
                steps = io.github.odnwks.mimir.audio.ReadSpeed.STEPS,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Palette.BlueText, activeTrackColor = Palette.BlueText),
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "Read-aloud speed"
                    stateDescription = io.github.odnwks.mimir.audio.ReadSpeed.percent(pending)
                },
            )
            SmallButton(
                when { waiting -> "Getting ${voice.voiceName ?: "the voice"} ready…"; hearing -> "Stop"; else -> "Hear it" },
                { voice.speed = pending; voice.toggle(SPEED_SAMPLE_ID, SPEED_SAMPLE) },
                description = when { waiting -> "Getting the voice ready. Tap to stop"; hearing -> "Stop the sample"; else -> "Hear a sample at ${io.github.odnwks.mimir.audio.ReadSpeed.percent(pending)}" },
                animate = true,
            )
        }
    }
}

/** What "Hear it" reads, and its id while it's being read. */
const val SPEED_SAMPLE = "This is how fast messages are read aloud."
const val SPEED_SAMPLE_ID = "speed-sample"
