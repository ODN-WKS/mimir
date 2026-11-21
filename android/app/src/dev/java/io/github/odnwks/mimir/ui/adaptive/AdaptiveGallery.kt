package io.github.odnwks.mimir.ui.adaptive

import android.os.Bundle
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.ui.Glyphs
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/**
 * Check build only: the tablet layout's building blocks on one screen, with stand-in content, so they
 * can be tried on a real tablet (rail, panes, limits, menu, dialog, keys, pointer) before the app's own
 * screens are fitted into them. Opened over adb:
 * `am start -n io.github.odnwks.mimir.dev/io.github.odnwks.mimir.ui.adaptive.AdaptiveGalleryActivity`
 */
class AdaptiveGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val device = Device.of(this)
        val reduce = Motion.systemRemovesAnimations(this)
        setContent {
            MimirTheme(reduceMotion = reduce) {
                CompositionLocalProvider(LocalDevice provides device) {
                    AdaptiveGallery(rememberWindowShape(), showKeys = keyboardAttached())
                }
            }
        }
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        data.add(shortcutGroup("Recipe box"))
    }
}

private val sections = listOf("Chat", "Status", "Updates", "Settings")
private val waiting = listOf("Allow Claude to run a command?", "Should I change everything to metric?", "The plan is ready", "Did pairing work on the hotel Wi-Fi?")

/** Stand-in content in the real frame. [start] picks the section shown first (for pictures). */
@Composable
fun AdaptiveGallery(shape: WindowShape, showKeys: Boolean = false, start: Int = 0, picked: Int? = null, dialog: Boolean = false, menu: Boolean = false) {
    val device = LocalDevice.current
    var section by rememberSaveable { mutableIntStateOf(start) }
    var open by rememberSaveable { mutableStateOf(picked) }
    var asking by rememberSaveable { mutableStateOf(dialog) }
    var last by rememberSaveable { mutableStateOf("") }
    val box = remember { FocusRequester() }
    val sent = remember { mutableStateListOf<String>() }

    val go: (Int) -> Unit = { section = it; open = null }
    val icons = listOf(Glyphs.Chat, Glyphs.Gauge, Glyphs.Speaker)
    val items = (0..2).map { i ->
        RailItem(sections[i], icons[i], section == i, { go(i) }, count = if (i == 1) waiting.size else 0, dot = i == 2, key = "Ctrl+${i + 1}")
    }
    val settings = RailItem("Settings", Glyphs.Settings, section == 3, { go(3) }, key = "Ctrl+,")

    Box(
        Modifier.fillMaxSize().background(Palette.Ground)
            .shortcuts(cardAsking = { open != null }) { s ->
                last = "${s.keys}: ${s.does}"
                when (s) {
                    Shortcut.Chat -> go(0)
                    Shortcut.Status -> go(1)
                    Shortcut.Updates -> go(2)
                    Shortcut.Settings -> go(3)
                    Shortcut.AskClaude, Shortcut.MessageBox -> { section = 0; runCatching { box.requestFocus() } }
                    Shortcut.NextNeedsYou -> open = ((open ?: -1) + 1) % waiting.size
                    else -> {}
                }
                true
            }
            .escBack(enabled = open != null) { open = null },
    ) {
        AdaptiveFrame(
            shape = shape,
            rail = { Rail(items, settings, onAsk = { section = 0; runCatching { box.requestFocus() } }, askIcon = Glyphs.Plus, showKeys = showKeys) },
            bottomBar = {
                Row(Modifier.fillMaxWidth().background(Palette.Bar).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    sections.take(3).forEachIndexed { i, s ->
                        Text(s, color = if (section == i) Palette.White else Palette.TextDim, style = Type.Label,
                            modifier = Modifier.heightIn(min = Limits.Target).clickable(role = Role.Tab) { go(i) }.padding(12.dp))
                    }
                }
            },
            leftTitle = sections[section],
            left = {
                PaneContent(section) { s ->
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Centered {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(sections[s], color = Palette.Text, style = Type.Title)
                                    Text("${device.offline} · ${shape.widthClass} · ${if (shape.twoPanes) "two panes" else "one pane"}", color = Palette.TextDim, style = Type.Small)
                                    Text(device.reword("Scan it with this phone. Phones that can reach it are listed under Phones. On the PC, click \"Pair a phone\"."), color = Palette.TextSoft, style = Type.Body)
                                    waiting.forEachIndexed { i, w ->
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRing().clip(RoundedCornerShape(12.dp))
                                                .background(if (open == i) Palette.BlueCard else Palette.Card).hoverLight()
                                                .menuAt { asking = true }
                                                .clickable(role = Role.Button) { open = i }.padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) { Text(w, color = Palette.Text, style = Type.Body) }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        GalleryButton(device.unpair) { asking = true }
                                        Box {
                                            var shown by remember { mutableStateOf(menu) }
                                            HoverHint("Task options") { GalleryButton("Options") { shown = true } }
                                            AnchoredMenu(shown, { shown = false }, listOf(
                                                MenuAction("Rename", {}, Glyphs.Document, key = "F2"),
                                                MenuAction("Move to another project", {}, Glyphs.Move),
                                                MenuAction("Stop this task…", { asking = true }, Glyphs.Stop, danger = true),
                                            ), offset = DpOffset(0.dp, 4.dp), showKeys = showKeys)
                                        }
                                    }
                                    sent.forEach { Text("Sent: $it", color = Palette.GreenText, style = Type.Small) }
                                    if (last.isNotEmpty()) Text("Key: $last", color = Palette.BlueText, style = Type.Small)
                                }
                            }
                        }
                        if (s == 0) {
                            var text by rememberSaveable { mutableStateOf("") }
                            Centered(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp)) {
                                BasicTextField(
                                    value = text, onValueChange = { text = it },
                                    textStyle = Type.Body.copy(color = Palette.Text), cursorBrush = SolidColor(Palette.BlueLight),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = Limits.Target).focusRequester(box)
                                        .messageBoxKeys { if (text.isNotBlank()) { sent.add(text.trim()); text = "" } }
                                        .semantics { contentDescription = "Message" }
                                        .clip(RoundedCornerShape(24.dp)).background(Palette.Bar).border(1.dp, Palette.Outline, RoundedCornerShape(24.dp))
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            },
            rightTitle = open?.let { "Task: ${waiting[it]}" } ?: "Needs you",
            right = {
                PaneContent(open, opensFromRight = true) { o ->
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (o == null) "Needs you" else waiting[o], color = Palette.Text, style = Type.Title)
                        Text(if (o == null) "Nothing picked: what a beginner sees without doing anything." else "Picked on the left; it opened here and the list stayed put.", color = Palette.TextSoft, style = Type.Body)
                        if (o != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GalleryButton("Don't allow") { open = null }
                            GalleryButton("Allow") { open = null }
                        }
                    }
                }
            },
        )
        if (asking) {
            ConfirmDialog(
                title = device.reword("Unpair this phone?"),
                body = device.reword("This phone will no longer reach your PC. You can pair it again from the PC."),
                risky = "Unpair", safe = "Keep paired", onRisky = { asking = false }, onSafe = { asking = false },
            )
        }
    }
}

@Composable
private fun GalleryButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.buttonLimit().heightIn(min = Limits.Target).focusRing(shape).clip(shape).background(Palette.Card)
            .border(1.dp, Palette.Outline, shape).hoverLight(shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Palette.Text, style = Type.Label) }
}
