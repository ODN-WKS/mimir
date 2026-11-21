package io.github.odnwks.mimir.ui

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.selection.selectableGroup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.online.LookupLog
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import kotlinx.coroutines.launch

const val LOOKUPS_SWITCH = "$AI_NAME may look things up online"
const val LOOKUPS_ABOUT = "I may look things up online, like the weather or a search. Only the few words needed are sent, never your chats. Never in a Whisper."
const val WEATHER_PLACE = "Weather place"
const val SEARCH_SWITCH = "Search the web"
const val SEARCH_ABOUT = "When you ask me to search, or for news or a price. Only the few words needed are sent."
const val SEARCH_ROUTE = "Goes through your PC when it is connected. A search may sometimes be blocked."
const val LOOKED_UP = "Looked up"
const val ENCYCLOPEDIAS = "Encyclopedias"
const val ENCYCLOPEDIAS_ABOUT = "For \u201cwhat is\u201d and \u201cwho was\u201d questions. With both, I say what they agree on and where they differ. A site may sometimes block this."

/**
 * "Use my rough location once": asks for Android's approximate location (never precise), takes
 * one reading, and hands it on; the Engine rounds it to about 10 km before it's used.
 */
@Composable
fun rememberRoughLocation(onGot: (Double, Double) -> Unit, onFail: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val read = { roughLocation(ctx, onGot, onFail) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) read() else onFail("Location wasn't allowed. Type a town instead.")
    }
    return {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) read()
        else ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

@android.annotation.SuppressLint("MissingPermission")
private fun roughLocation(ctx: Context, onGot: (Double, Double) -> Unit, onFail: (String) -> Unit) {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return onFail("This phone has no location.")
    val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return onFail("Location is off on this phone. Type a town instead.")
    val main = ContextCompat.getMainExecutor(ctx)
    try {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, main) { loc ->
                val l = loc ?: lm.getLastKnownLocation(provider)
                if (l != null) onGot(l.latitude, l.longitude) else onFail("The phone couldn't tell where it is. Type a town instead.")
            }
        } else {
            val l = lm.getLastKnownLocation(provider)
            if (l != null) onGot(l.latitude, l.longitude) else onFail("The phone couldn't tell where it is. Type a town instead.")
        }
    } catch (e: SecurityException) {
        onFail("Location wasn't allowed. Type a town instead.")
    }
}

/** Settings › Who answers › Looking things up: the switch, and the weather place. */
@Composable
fun LookupSettings(modifier: Modifier = Modifier) {
    val s by Online.settings.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Looking things up")
        SwitchRow(s.lookups, { Online.set(s.copy(lookups = it)) }) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(LOOKUPS_SWITCH, style = Type.BodyTight, color = Palette.Text)
                Text(LOOKUPS_ABOUT, style = Type.Small, color = Palette.TextDim)
            }
        }
        if (s.lookups) SwitchRow(s.search, { Online.set(s.copy(search = it)) }) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(SEARCH_SWITCH, style = Type.BodyTight, color = Palette.Text)
                Text(SEARCH_ABOUT, style = Type.Small, color = Palette.TextDim)
                Text(SEARCH_ROUTE, style = Type.Small, color = Palette.TextDim)
            }
        }
        if (s.lookups) {
            Column(Modifier.fillMaxWidth().selectableGroup().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ENCYCLOPEDIAS, style = Type.BodyTight, color = Palette.Text)
                Text(ENCYCLOPEDIAS_ABOUT, style = Type.Small, color = Palette.TextDim)
                io.github.odnwks.mimir.online.Encyclopedias.entries.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .choice(s.encyclopedias == e) { Online.set(s.copy(encyclopedias = e)) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        androidx.compose.material3.RadioButton(s.encyclopedias == e, onClick = null)
                        Text(e.label, style = Type.BodyTight, color = Palette.Text)
                    }
                }
            }
        }
        if (editing) {
            var text by rememberSaveable { mutableStateOf(s.place?.name.orEmpty()) }
            var problem by remember { mutableStateOf<String?>(null) }
            var finding by remember { mutableStateOf(false) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                MessageBox(text, { text = it }, "Town or city", WEATHER_PLACE, Modifier.weight(1f))
                LinkText(if (finding) "Finding…" else "Save", {
                    if (finding) return@LinkText
                    if (text.isBlank()) { Online.set(s.copy(place = null)); editing = false; return@LinkText }
                    finding = true
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val found = runCatching { io.github.odnwks.mimir.online.Weather.find(text, io.github.odnwks.mimir.online.ChatMode.Normal) }
                        finding = false
                        found.onSuccess { p -> if (p == null) problem = "No place called \"$text\" was found." else { Online.set(Online.settings.value.copy(place = p)); editing = false } }
                            .onFailure { problem = "Couldn't look that up. Check your internet and try again." }
                    }
                }, bold = true)
            }
            problem?.let { Text(it, style = Type.Small, color = Palette.Red) }
        } else {
            ListRow(WEATHER_PLACE, s.place?.name ?: "Not set yet. I ask the first time you want the weather.", { editing = true })
        }
    }
}

/** Privacy check › Looked up: every lookup, newest first (the words only while the app runs). */
@Composable
fun LookedUpPanel() {
    val all by LookupLog.entries.collectAsStateWithLifecycle()
    Panel(spacing = 6.dp) {
        Text(LOOKED_UP, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.semantics { heading() })
        if (all.isEmpty()) Text("Nothing yet. When I look something up for you (like the weather), it shows here: when, which site, and the few words sent.", style = Type.Small, color = Palette.TextSoft)
        val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        all.take(30).forEach { e ->
            Text(
                listOfNotNull(time.format(java.util.Date(e.at)), e.site, e.words.takeIf { it.isNotEmpty() }?.let { "\"$it\"" }, e.route.words, sizeWords(e.bytes)).joinToString(" · "),
                style = Type.Small, color = Palette.TextSoft,
            )
        }
        if (all.size > 30) Text("and ${all.size - 30} earlier", style = Type.Small, color = Palette.TextDim)
    }
}
