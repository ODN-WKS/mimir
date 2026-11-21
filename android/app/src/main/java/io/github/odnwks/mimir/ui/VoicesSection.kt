package io.github.odnwks.mimir.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.audio.PhoneVoice
import io.github.odnwks.mimir.audio.VoiceActions
import io.github.odnwks.mimir.audio.VoiceCatalog
import io.github.odnwks.mimir.audio.Voices
import io.github.odnwks.mimir.audio.VoicesState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import kotlinx.coroutines.flow.StateFlow

/** The reading voice as Settings shows it: the choice and its buttons. */
class VoicesUi(val state: StateFlow<VoicesState>, val actions: VoiceActions)

/** A stand-in for the voices (checks); null means the app's own. */
val LocalVoices = staticCompositionLocalOf<VoicesUi?> { null }

@Composable
fun rememberVoices(): VoicesUi {
    val given = LocalVoices.current
    val ctx = LocalContext.current
    return given ?: remember {
        val v = Voices.get(ctx)
        VoicesUi(v.store.state, v.actions { PhoneVoice.get(ctx).sample(it) })
    }
}

/**
 * The two reading voices, one tap each: Ingrid, built into the app (ready from the first launch,
 * nothing to download), and the phone's own voice. The one in use is marked; Ingrid has a
 * sample, played only when tapped.
 */
@Composable
fun VoiceCards(ui: VoicesUi) {
    val s by ui.state.collectAsStateWithLifecycle()
    VoiceCatalog.all.forEach { v ->
        val playing = s.sampling == v.id
        VoiceChoice(
            v.name, "${v.note} · built into the app, works without internet", s.active?.id == v.id,
            { ui.actions.choose(v.id) },
        ) {
            BigButton(
                if (playing) "Stop sample" else "Play a sample", Look.Quiet, { ui.actions.sample(v.id) }, height = 48.dp,
                description = if (playing) "Stop the sample" else "Play a sample of ${v.name}",
            )
        }
    }
    VoiceChoice(PHONE_VOICE, "Android's own voice, in British English when the phone has it", s.active == null, { ui.actions.choose(null) })
}

/** What the phone's own voice is called on the choices. */
const val PHONE_VOICE = "The phone's own voice"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceChoice(name: String, note: String, inUse: Boolean, use: () -> Unit, more: (@Composable () -> Unit)? = null) {
    val reduce = LocalReduceMotion.current
    val shape = RoundedCornerShape(16.dp)
    val bg by androidx.compose.animation.animateColorAsState(if (inUse) Palette.BlueCard else Palette.Card, io.github.odnwks.mimir.ui.theme.motionSpec(220), label = "voice")
    Column(
        Modifier.fillMaxWidth().clip(shape).background(bg)
            .border16(if (inUse) Palette.BlueBorder else Palette.Line)
            .then(if (reduce) Modifier else Modifier.animateContentSize(tween(220)))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
            Text(note, style = Type.Small, color = Palette.TextSoft)
            if (inUse) FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("In use", Palette.BlueText, Palette.BlueIcon)
            }
        }
        if (!inUse) BigButton("Use this voice", Look.Primary, use, height = 48.dp, description = "Use ${if (name == PHONE_VOICE) "the phone's own voice" else name} to read aloud")
        more?.invoke()
    }
}

/**
 * Settings › Voice › Voices: the same cards as in Models, on their own page (where people
 * looking for a voice look first).
 */
@Composable
fun VoicesScreen(nav: Navigator) {
    val ui = rememberVoices()
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader("Other voices", nav::back, backLabel = "Back", subtitle = "Read aloud on this phone, with no PC")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Messages are read aloud on this phone: nothing is sent anywhere to speak.",
                style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.45f), color = Palette.TextSoft,
            )
            VoiceCards(ui)
        }
    }
}
