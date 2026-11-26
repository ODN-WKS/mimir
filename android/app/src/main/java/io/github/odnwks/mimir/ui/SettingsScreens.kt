package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.theme.TitleFont
import io.github.odnwks.mimir.ui.adaptive.forDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import io.github.odnwks.mimir.ui.theme.AppSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.data.Mapping
import io.github.odnwks.mimir.data.Model
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.nav.Tab
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.data.count

private data class MoreRow(val title: String, val value: String, val icon: ImageVector, val bg: Color, val fg: Color, val go: (() -> Unit)?)

/**
 * Settings, behind the cog: a calm list, each row a title and one dim line saying what is inside:
 * Voice, Who answers, Your PC, Privacy, Look and size, Storage, Help and about; the eye and the app's
 * name and version sit at the bottom, their one permanent home. Unpaired, Your PC is the way to connect.
 */
@Composable
fun SettingsScreen(state: StationState, nav: Navigator) {
    val tap = LocalTapTracker.current
    val net by io.github.odnwks.mimir.data.NetUse.use.collectAsStateWithLifecycle()
    // Online services with a key set up are listed on the Who answers page (Remove key is there); said here too.
    val onlineNow by io.github.odnwks.mimir.online.OnlineModels.state.collectAsStateWithLifecycle()
    val onlineLine = if (onlineNow.setups.isEmpty()) "" else " \u00b7 online: " + onlineNow.setups.joinToString(", ") { it.name }
    val worth = state.paired && state.models.weeklyCheck && state.worthSwitching?.heard == io.github.odnwks.mimir.data.Heard.New
    val rows = buildList {
        add(MoreRow("Voice", SETTINGS_VOICE_LINE, Glyphs.Voice, Palette.BlueIcon, Palette.BlueText) { nav.open(Screen.Voice, tap.last) })
        add(MoreRow("Who answers", if (worth) "A better model for this phone may be worth a look" else SETTINGS_WHO_LINE + onlineLine, Glyphs.Models, Palette.BlueIcon, Palette.BlueText) { nav.open(Screen.Models, tap.last) })
        add(MoreRow("Privacy", privacyLine(net), Glyphs.Shield, Palette.BlueIcon, Palette.BlueText) { nav.open(Screen.PrivacyCheck, tap.last) })
        if (state.paired) add(MoreRow("Notifications", SETTINGS_NOTIFICATIONS_LINE, Glyphs.Bell, Palette.BlueIcon, Palette.BlueText) { nav.open(Screen.Notifications, tap.last) })
        add(MoreRow("Look and size", SETTINGS_LOOK_LINE, Glyphs.Look, Palette.GreyIcon, Palette.Text) { nav.open(Screen.General, tap.last) })
        add(
            if (state.paired) MoreRow("Your PC", SETTINGS_PC_LINE, Glyphs.PhonePc, Palette.BlueIcon, Palette.BlueText) { nav.open(Screen.PhonePc, tap.last) }
            else MoreRow("Your PC", "Not connected \u00b7 connect it", Glyphs.PhonePc, Palette.BlueIcon, Palette.BlueText) { nav.open(Screen.Pair, tap.last) },
        )
        add(MoreRow("Storage", SETTINGS_STORAGE_LINE, Glyphs.Storage, Palette.BlueIcon, Palette.BlueText) { nav.open(Screen.Storage, tap.last) })
        add(MoreRow("Help and about", if (state.appUpdate) "A new version is out \u00b7 ${state.pcVersion}" else SETTINGS_HELP_LINE, Glyphs.About, Palette.GreyIcon, Palette.Text) { nav.open(Screen.Help, tap.last) })
    }
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        CenteredTitleBar("Settings", nav)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Search: the words people use ("system prompt", "privacy") find where a thing lives.
            var query by rememberSaveable { mutableStateOf("") }
            // The text box is the heaviest part to build: it comes one frame after the page slides in
            // (a box of the same height holds its place), so the opening frames stay short.
            // Built in steps over the first frames (the page is still sliding in): the first rows, the rest, then the box.
            var step by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { repeat(3) { androidx.compose.runtime.withFrameNanos { }; step++ } }
            val boxReady = step >= 3
            if (boxReady) MessageBox(query, { query = it }, "Search settings", "Search settings", Modifier.fillMaxWidth())
            else Spacer(Modifier.fillMaxWidth().height(48.dp))
            if (query.isNotBlank()) {
                val found = findSettings(query, state.paired)
                if (found.isEmpty()) Text("Nothing in Settings matches \u201c${query.trim()}\u201d.", style = Type.Label, color = Palette.TextDim, modifier = Modifier.padding(4.dp))
                found.forEach { f ->
                    if (f.startsWhisper) ListRow(f.title, whisperExplained(state.paired).forDevice(), {
                        // The chat starts it as its eye does: unpaired, M\u00edmir; paired, on this phone or the PC.
                        nav.askChat(io.github.odnwks.mimir.nav.ChatAsk.StartWhisper)
                        nav.selectTab(Tab.Chat)
                    }, leading = { MimirEye(EyeMode.Whisper, Modifier.size(28.dp)) })
                    else ListRow(f.title, if (f.where == "Settings") "Settings" else "Settings \u203a ${f.where}", { nav.open(f.screen, tap.last) })
                }
            } else {
                rows.forEachIndexed { i, r -> if (step >= if (i < 4) 1 else 2) ListRow(r.title, r.value.forDevice(), r.go) }
                if (boxReady) SettingsFooter(state.appVersion) else Spacer(Modifier.fillMaxWidth().height(100.dp))
            }
        }
        NeedsYouBar(state.needsYou.size, { nav.open(Screen.NeedsYou) }, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp))
    }
}

const val SETTINGS_VOICE_LINE = "Ingrid's voice, read-aloud speed, talk to type"
const val SETTINGS_WHO_LINE = "This phone, your PC, online"
const val SETTINGS_NOTIFICATIONS_LINE = "What to tell me, staying in touch"
const val SETTINGS_PC_LINE = "Connection, phones that can reach it"
const val SETTINGS_LOOK_LINE = "Text size, startup animation, keyboard, stop buttons"
const val SETTINGS_STORAGE_LINE = "What's kept on this phone, delete chats"
const val SETTINGS_HELP_LINE = "Questions, app updates, Labs"

/** The one permanent home of the eye and the app's name: centred at the end of Settings, with the version. */
@Composable
fun SettingsFooter(version: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MimirEye(EyeMode.Normal, Modifier.size(width = 40.dp, height = 25.dp))
        Text(AI_NAME, style = Type.Body.copy(fontFamily = TitleFont, fontSize = 16.sp), color = Palette.Text)
        Text("version $version", style = Type.Small, color = Palette.TextDim)
    }
}

/** Settings › Your PC: the connection, the phones that can reach it, its name, and Disconnect this phone. */
@Composable
fun PhonePcScreen(state: StationState, station: Station, nav: Navigator, settings: Boolean = false) {
    // A PC that lists its Claude Code sessions: the list first, these settings one row away.
    if (state.pcSessions != null && !settings) return PcSessionsScreen(state, station, nav)
    val standIn = LocalStandIn.current
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader(if (settings) "PC settings" else "Your PC", nav::back)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Panel(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp), spacing = 4.dp) {
                SectionTitle(state.pc.name, style = Type.Section.copy(fontSize = Type.Section.fontSize * 17 / 16))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.padding(top = firstLineCenter(Type.Small, 8.dp))) { Dot(if (state.pc.online) Palette.Green else Palette.TextDim, hollow = !state.pc.online) }
                    Text(if (state.pc.online) "Online · ${state.pc.route}" else state.connectionLine, style = Type.Small, color = Palette.TextDim)
                }
                Spacer(Modifier.size(2.dp))
                Spacer(Modifier.size(2.dp))
                LineButton("What it's doing now") { nav.selectTab(Tab.Status) }
            }
            // Pairing starts at the PC only: this phone offers no way to pair another.
            Text("New phones are paired from $AI_NAME on the PC.", style = Type.Small, color = Palette.TextSoft, modifier = Modifier.padding(horizontal = 4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Phones that can reach it", Modifier.padding(horizontal = 4.dp))
                state.phones.forEach { ph ->
                    ListRow(ph.name, ph.note, onClick = null, trailing = null)
                }
            }
            // The PC's name and how phones reach it: the PC's settings, changed from here too.
            Panel(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp), spacing = 4.dp) {
                SectionTitle("Your PC")
                var naming by rememberSaveable { mutableStateOf(false) }
                var name by rememberSaveable { mutableStateOf(state.pc.name) }
                if (!naming) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Name", style = Type.Small, color = Palette.TextDim)
                        Text(state.pc.name, style = Type.BodyTight, color = Palette.Text)
                    }
                    if (state.owner) SmallButton("Rename", { name = state.pc.name; naming = true }, description = "Rename your PC")
                } else Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    MessageBox(name, { name = it.take(40) }, "Your PC's name", "The name your phones see", Modifier.fillMaxWidth(), autoFocus = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("Save", { station.setPcName(name); naming = false }, description = "Save the name")
                        SmallButton("Cancel", { naming = false })
                    }
                }
                // The PC's own settings are the owner's: another person's phone only sees the name.
                if (state.owner) {
                    Spacer(Modifier.fillMaxWidth().size(1.dp).background(Palette.Line))
                    SwitchRow(state.relays, station::setRelays, Modifier.heightIn(min = 56.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Let this phone reach your PC from anywhere", style = Type.BodyTight, color = Palette.Text)
                            Text(
                                "When this phone can't connect straight to your PC (away from home), it goes another way and stays private. Off: only at home or on the same network. Takes effect when your PC next starts.",
                                style = Type.Small, color = Palette.TextDim,
                            )
                        }
                    }
                }
            }
            // Removing THIS phone is fine from here; other phones are removed at the PC.
            BigButton("Disconnect this phone", Look.Danger, { nav.openSheet(io.github.odnwks.mimir.nav.Sheet.Unpair) }, height = 48.dp)
            Text("Other phones are removed on your PC: $AI_NAME › Settings › Phones.", style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp))
        }
        NeedsYouBar(state.needsYou.size, { nav.open(Screen.NeedsYou) }, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp))
    }
}

/** A full-width row with a line above and a chevron ("Rename this PC"). */
@Composable
private fun LineButton(text: String, onClick: () -> Unit) {
    Column {
        Spacer(Modifier.fillMaxWidth().size(1.dp).background(Palette.Line))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = Type.BodyTight, color = Palette.Text, modifier = Modifier.weight(1f))
            Icon(Glyphs.Next, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
        }
    }
}

/** Settings › Look and size: the text size (with a preview), the colours and the startup animation. */
@Composable
fun GeneralScreen(state: StationState, nav: Navigator) {
    val tap = LocalTapTracker.current
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader("Look and size", nav::back)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CapsLabel("TEXT SIZE")
            SizeSetting()
            CapsLabel("COLORS")
            // Only the dark look is built; a light one and "match phone" wait.
            Panel(spacing = 4.dp) {
                Text("Dark", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                Text("The only look for now.", style = Type.Small, color = Palette.TextDim)
            }
            CapsLabel("STARTUP")
            StartupAnimationSetting()
            CapsLabel("KEYBOARD")
            KeyboardAutoSetting()
            CapsLabel(STOP_SETTING_TITLE)
            StopSetting()
        }
    }
}

/** The switch "Open the keyboard by itself" (on unless chosen off): a new chat and any box that needs typing bring the keyboard up. */
@Composable
fun KeyboardAutoSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PHONE_CHAT_PREFS, 0) }
    var on by remember { androidx.compose.runtime.mutableStateOf(keyboardAutoOn(ctx)) }
    Panel(padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)) {
        SwitchRow(on, { on = it; prefs.edit().putBoolean(KEYBOARD_AUTO, it).apply() }, Modifier.heightIn(min = 56.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Open the keyboard by itself", style = Type.BodyTight, color = Palette.Text)
                Text("A new chat and boxes that need typing put the cursor in and show the keyboard", style = Type.Small, color = Palette.TextDim)
            }
        }
    }
}

/** The switch "Startup animation" (on unless chosen off); it shows off and says why when the phone removes animations. */
@Composable
fun StartupAnimationSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PHONE_CHAT_PREFS, 0) }
    var on by remember { androidx.compose.runtime.mutableStateOf(startupAnimationOn(ctx)) }
    val removed = LocalReduceMotion.current || io.github.odnwks.mimir.ui.theme.Motion.systemRemovesAnimations(ctx)
    Panel(padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)) {
        SwitchRow(
            on && !removed, { if (!removed) { on = it; prefs.edit().putBoolean(STARTUP_ANIMATION, it).apply() } },
            Modifier.heightIn(min = 56.dp).then(if (removed) Modifier.alpha(0.5f).semantics { disabled() } else Modifier),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Startup animation", style = Type.BodyTight, color = Palette.Text)
                Text(if (removed) "Animations are off in this phone's settings" else "The eye opens when $AI_NAME starts", style = Type.Small, color = Palette.TextDim)
            }
        }
    }
}

/**
 * Settings › General › Size: one slider for the whole app, 50% to 150%, like the PC's zoom, on top
 * of the phone's own font and display size. The preview follows the finger; the app takes the new
 * size when the finger lifts (so the slider doesn't move under it). Chat text is pinched in chats.
 */
@Composable
fun SizeSetting() {
    val size = io.github.odnwks.mimir.ui.theme.LocalAppSize.current
    var pending by androidx.compose.runtime.remember(size.scale) { androidx.compose.runtime.mutableFloatStateOf(size.scale) }
    Panel(spacing = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.weight(1f).semantics { heading() })
            Text(AppSize.percent(pending), style = Type.BodyTight, color = Palette.Text)
        }
        Text("The whole app, on top of your phone's own font and display size.", style = Type.Small, color = Palette.TextDim)
        // Said only while it matters: small sizes make buttons smaller than a fingertip.
        if (pending < 0.8f) Text("Below 80%, buttons get small and harder to tap.", style = Type.Small, color = Palette.AmberText)
        val fits = size.shownAt(pending)
        if (fits < pending) Text(
            "With this phone's font size, ${AppSize.percent(fits)} already fills the screen, so the app stops there.",
            style = Type.Small, color = Palette.TextDim,
        )
        androidx.compose.material3.Slider(
            value = pending,
            onValueChange = { pending = AppSize.clampScale(it) },
            onValueChangeFinished = { size.chooseScale(pending) },
            valueRange = AppSize.MIN..AppSize.MAX,
            steps = AppSize.STEPS,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Palette.BlueText, activeTrackColor = Palette.BlueText),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "App size"
                stateDescription = AppSize.percent(pending)
            },
        )
        // The preview: a small chat at the size being chosen.
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density / size.shown * size.shownAt(pending), d.fontScale),
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Ground).padding(12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Preview at ${AppSize.percent(pending)}" },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MineBubble("Give me three easy dinner ideas")
                Text("1. Pasta with tomatoes and garlic.", style = Type.Body, color = Palette.Text)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Text in chats: ${AppSize.percent(size.chatText)}. Pinch a chat with two fingers to change it.",
                style = Type.Small, color = Palette.TextDim, modifier = Modifier.weight(1f),
            )
            if (size.chatText != 1f) LinkText("Reset", { size.chooseChatText(1f) })
        }
    }
}

