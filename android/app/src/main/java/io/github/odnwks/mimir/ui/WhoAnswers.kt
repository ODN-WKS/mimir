package io.github.odnwks.mimir.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.online.OnlineModels
import io.github.odnwks.mimir.online.Setup
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.ui.theme.motionSpec

/** The words of the "Who should answer?" sheet (the redesign, screen 5). */
const val WHO_TITLE = "Who should answer?"
const val WHO_PHONE = "This phone"
const val WHO_PHONE_ABOUT = "Works anywhere, even with no signal. Your chats stay on this phone."
const val WHO_PHONE_CANT = "This phone can't run a model by itself"
const val WHO_PC = "Your PC"
const val WHO_PC_ABOUT = "Smarter and faster. Your chats stay between this phone and your PC."
const val WHO_CONNECT = "Connect your PC"
const val WHO_CLAUDE = "Online"
const val WHO_CLAUDE_ABOUT = "Uses your Anthropic plan through your PC. Your messages go to Anthropic."

/** What the top bar's "Your PC ▾" says to TalkBack. */
fun whoLabel(name: String) = "$name. Change who answers"

/** Which cards the sheet shows, in order, and what each says; pure so a check can read it. */
data class WhoCard(val who: Answerer, val title: String, val about: String, val enabled: Boolean, val chosen: Boolean, val online: Boolean = false)

/** The cards for now: This phone always (disabled if it can't run a model), Your PC and Claude only when paired and offered. */
fun whoCards(
    chosen: Answerer, phoneCan: Boolean, paired: Boolean, claudeOffered: Boolean,
    /** An online service the owner set up (a fourth card), and whether it is the one answering. */
    online: Setup? = null, onlineChosen: Boolean = false,
): List<WhoCard> = buildList {
    add(WhoCard(Answerer.Phone, WHO_PHONE, if (phoneCan) WHO_PHONE_ABOUT else WHO_PHONE_CANT, phoneCan, chosen == Answerer.Phone && phoneCan && !onlineChosen))
    if (paired) add(WhoCard(Answerer.Pc, WHO_PC, WHO_PC_ABOUT, true, chosen == Answerer.Pc && !onlineChosen))
    if (paired && claudeOffered) add(WhoCard(Answerer.Claude, WHO_CLAUDE, WHO_CLAUDE_ABOUT, true, chosen == Answerer.Claude && !onlineChosen))
    if (online != null) {
        val service = OnlineModels.serviceOf(online)
        add(WhoCard(Answerer.Online, "${service.name} \u00b7 ${online.model?.let(::shortModel) ?: "best free one"}", service.who, true, onlineChosen, online = true))
    }
}

/** A model id as people read it: no maker, no ":free" ("meta-llama/llama-3.3-70b-instruct:free" becomes "llama-3.3-70b-instruct"). */
fun shortModel(id: String) = id.substringAfterLast('/').removeSuffix(":free")

private val CardFill = Color(0xFF182236)
private val CardBorder = Color(0xFF3B6FD0)

/**
 * The bottom sheet opened from the top bar's "Your PC ▾" / "This phone ▾": up to three cards, the
 * chosen one in blue with a tick. Effort and model are in the chat box, so they are not here.
 */
@Composable
fun WhoAnswersSheet(
    open: Boolean,
    chosen: Answerer,
    phoneCan: Boolean,
    paired: Boolean,
    claudeOffered: Boolean,
    onChoose: (Answerer) -> Unit,
    onConnect: () -> Unit,
    onClose: () -> Unit,
) {
    val online = OnlineModels.state.collectAsStateWithLifecycle().value
    val onlineSetup = online.setups.firstOrNull { it.id == online.answering }
    // A set-up service is offered as a card; the last one used (or the one answering) is the card.
    val offered = onlineSetup ?: online.setups.firstOrNull()
    // The keyboard goes away first, so the sheet never opens underneath it (QA3 #1i).
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(open) { if (open) { keyboard?.hide(); focus.clearFocus() } }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(motionSpec(Motion.SHEET_MS)), exit = fadeOut(motionSpec(Motion.SHEET_MS))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onClose)
                    .semantics { contentDescription = "Close" },
            )
        }
        AnimatedVisibility(
            open, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(motionSpec(Motion.SHEET_MS)) { it }, exit = slideOutVertically(motionSpec(Motion.SHEET_MS)) { it },
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Palette.Card)
                    .border(1.dp, Palette.LineStrong, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .semantics { paneTitle = WHO_TITLE }
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.padding(bottom = 4.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Outline))
                }
                Text(
                    WHO_TITLE, style = Type.SheetTitle,
                    color = Palette.Text, modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
                )
                whoCards(chosen, phoneCan, paired, claudeOffered, offered, onlineSetup != null).forEach { c ->
                    WhoCardView(c) {
                        // One answerer at a time: choosing another stops the online one; choosing it stops the rest.
                        if (c.online) { OnlineModels.answerWith(offered?.id); onClose() }
                        else { OnlineModels.answerWith(null); onChoose(c.who) }
                    }
                }
                if (!paired) Text(
                    WHO_CONNECT, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.BlueText,
                    modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onConnect).padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun WhoCardView(c: WhoCard, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape)
            .background(if (c.chosen) CardFill else Palette.Ground)
            .border(1.dp, if (c.chosen) CardBorder else Palette.Line, shape)
            .then(if (c.enabled) Modifier.clickable(role = Role.RadioButton, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { selected = c.chosen; contentDescription = "${c.title}. ${c.about}" + if (c.enabled) "" else ". Not available" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.title, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = if (c.enabled) Palette.Text else Palette.Outline)
            Text(c.about, style = Type.Small, color = if (c.enabled) Palette.TextSoft else Palette.Outline)
        }
        if (c.chosen) Icon(Glyphs.Check, "Chosen", tint = Palette.BlueText, modifier = Modifier.padding(2.dp))
    }
}
